(ns ppp.integration.product-auth-http-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ppp.access :as access]
            [ppp.coordinator :as coordinator]
            [ppp.http :as http]
            [ppp.outbound.service :as outbound]
            [ppp.provider.fake :as fake]
            [ppp.provider.queue :as provider-queue]
            [ppp.runtime.auth :as auth]
            [ppp.runtime.server :as server]
            [ppp.session.store :as store]
            [ppp.util.fs :as fs]
            [ppp.websocket :as websocket])
  (:import (java.io ByteArrayInputStream)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- body-stream
  [value]
  (ByteArrayInputStream.
   (.getBytes (json/write-str value) StandardCharsets/UTF_8)))

(defn- request
  [method uri {:keys [body cookie csrf]}]
  {:request-method method
   :uri uri
   :headers (cond-> {"origin" "http://localhost:8787"}
              cookie (assoc "cookie" cookie)
              csrf (assoc access/csrf-header csrf))
   :body (when body (body-stream body))})

(defn- response-json
  [response]
  (json/read-str (:body response) :key-fn keyword))

(defn- cookie-pair
  [response]
  (first (str/split (get-in response [:headers "set-cookie"]) #";")))

(defn- create-context
  []
  (let [root (Files/createTempDirectory "ppp-auth-http-test"
                                        (make-array FileAttribute 0))
        config {:environment :test
                :development? true
                :host "127.0.0.1"
                :port 8787
                :public-base-url "http://localhost:8787"
                :access-code "correct horse battery staple"
                :cookie-secret
                "product-auth-http-test-secret-with-more-than-32-characters"
                :cookie-secure? false
                :product-auth-session-seconds 3600
                :data-dir root
                :prompt-limit 4000
                :queue-capacity 8
                :provider-timeout-ms 5000
                :client-ack-timeout-ms 100
                :require-client-ack? false
                :source-file-limit 32
                :source-byte-limit (* 256 1024)
                :session-db-limit (* 25 1024 1024)
                :checkpoint-limit (* 256 1024 1024)
                :instance-limit (* 2 1024 1024 1024)}
        session-store (store/create-store config)
        queue (provider-queue/create-queue config)
        registry (server/create-registry)
        product-auth (auth/create-service
                      config
                      {:hash-options {:memory-kib 7168
                                      :iterations 1
                                      :parallelism 1}
                       :allow-weak-test-parameters? true})
        sent (atom [])
        bundle-fn (atom nil)
        hub (websocket/create-hub
             (assoc config
                    :runtime-bundle-fn #(@bundle-fn %)
                    :send-fn (fn [channel frame]
                               (swap! sent conj [channel frame])
                               true)
                    :close-fn (constantly true)))
        coordinator
        (coordinator/create-coordinator
         {:config config
          :store session-store
          :provider (fake/create-provider)
          :provider-queue queue
          :registry registry
          :product-auth product-auth
          :outbound (outbound/create-service {})
          :hub hub})
        _ (reset! bundle-fn #(coordinator/runtime-bundle coordinator %))
        handler (http/create-handler {:config config
                                      :session-store session-store
                                      :coordinator coordinator
                                      :websocket hub
                                      :product-auth product-auth})]
    {:root root :config config :store session-store :queue queue :hub hub
     :coordinator coordinator :handler handler}))

(defn- close-context!
  [{:keys [root queue hub]}]
  (provider-queue/stop! queue)
  (websocket/stop! hub)
  (fs/delete-tree! root))

(deftest product-auth-effects-cross-http-only-as-session-scoped-cookies
  (let [context (create-context)]
    (try
      (let [handler (:handler context)
            access-response
            (handler (request :post "/api/access"
                              {:body {:code (get-in context [:config :access-code])}}))
            access-cookie (cookie-pair access-response)
            bootstrap (handler (request :get "/api/bootstrap"
                                        {:cookie access-cookie}))
            csrf (:csrf-token (response-json bootstrap))
            create-response
            (handler (request :post "/api/sessions"
                              {:cookie access-cookie :csrf csrf :body {}}))
            session-id (:id (response-json create-response))
            submission
            (coordinator/submit-turn!
             (:coordinator context) session-id
             {:prompt "로그인, 회원가입을 구현해줘"
              :request-tab-id (random-uuid)
              :base-version 0})]
        (is (= 201 (:status create-response)))
        (is (= :change
               (:kind (coordinator/await-turn! (:coordinator context)
                                               (:request-id submission) 10000))))

        (let [signup
              (handler
               (request :post
                        (str "/api/sessions/" session-id
                             "/actions/accounts%2Fsignup")
                        {:cookie access-cookie
                         :csrf csrf
                         :body {:identifier "browser-owner"
                                :password "browser password"
                                :display-name "Browser Owner"}}))
              signup-body (response-json signup)
              product-cookie (cookie-pair signup)
              combined-cookie (str access-cookie "; " product-cookie)]
          (is (= 200 (:status signup)))
          (is (str/includes? (get-in signup [:headers "set-cookie"]) "HttpOnly"))
          (is (str/includes? (get-in signup [:headers "set-cookie"]) "SameSite=Strict"))
          (is (str/includes? (get-in signup [:headers "set-cookie"])
                             (str "Path=/api/sessions/" session-id "/actions")))
          (is (nil? (:effects signup-body)))
          (is (not (str/includes? (:body signup) "browser password")))
          (is (not (str/includes? (:body signup) "token")))

          (testing "reload-style status requests resolve the HttpOnly cookie"
            (let [status
                  (handler
                   (request :post
                            (str "/api/sessions/" session-id
                                 "/actions/accounts%2Fstatus")
                            {:cookie combined-cookie :csrf csrf :body {}}))]
              (is (= true (get-in (response-json status)
                                  [:result :signed-in?])))
              (is (= "Browser Owner"
                     (get-in (response-json status)
                             [:result :profile :display_name])))))

          (testing "a fresh browser context has no product identity"
            (let [status
                  (handler
                   (request :post
                            (str "/api/sessions/" session-id
                                 "/actions/accounts%2Fstatus")
                            {:cookie access-cookie :csrf csrf :body {}}))]
              (is (false? (get-in (response-json status)
                                  [:result :signed-in?])))))

          (testing "logout clears the scoped cookie and protected behavior"
            (let [logout
                  (handler
                   (request :post
                            (str "/api/sessions/" session-id
                                 "/actions/accounts%2Flogout")
                            {:cookie combined-cookie :csrf csrf :body {}}))]
              (is (= 200 (:status logout)))
              (is (str/includes? (get-in logout [:headers "set-cookie"])
                                 "Max-Age=0"))))))
      (finally
        (close-context! context)))))
