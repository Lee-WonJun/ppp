(ns ppp.access-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ppp.access :as access]
            [ppp.config :as config]
            [ppp.coordinator :as coordinator]
            [ppp.http :as http]
            [ppp.session.store :as store]
            [ppp.util.fs :as fs])
  (:import (java.io ByteArrayInputStream)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.time Instant)))

(def test-config
  {:environment :test
   :development? true
   :host "127.0.0.1"
   :port 8787
   :public-base-url "http://localhost:8787"
   :access-code "correct horse battery staple"
   :cookie-secret "a-test-cookie-secret-with-more-than-32-characters"
   :cookie-secure? false
   :provider :fake})

(defn- body-stream
  [value]
  (ByteArrayInputStream.
   (.getBytes (json/write-str value) StandardCharsets/UTF_8)))

(defn- request
  ([method uri] (request method uri {}))
  ([method uri {:keys [body cookie csrf origin]
                :or {origin "http://localhost:8787"}}]
   {:request-method method
    :uri uri
    :headers (cond-> {"origin" origin}
               cookie (assoc "cookie" cookie)
               csrf (assoc access/csrf-header csrf))
    :body (when body (body-stream body))}))

(defn- response-json
  [response]
  (json/read-str (:body response) :key-fn keyword))

(defn- cookie-pair
  [response]
  (first (str/split (get-in response [:headers "set-cookie"]) #";")))

(deftest stable-static-asset-urls-are-never-cached
  (let [resource-fn
        (fn [resource-name]
          (if (= "public/js/app.js" resource-name)
            (ByteArrayInputStream. (.getBytes "fixture" StandardCharsets/UTF_8))
            (io/resource resource-name)))
        handler (http/create-handler {:config test-config
                                      :resource-fn resource-fn})]
    (doseq [uri ["/" "/js/app.js" "/css/host.css"]]
      (let [response (handler (request :get uri))]
        (try
          (is (= 200 (:status response)) uri)
          (is (= "no-store" (get-in response [:headers "cache-control"])) uri)
          (finally
            (when (instance? java.io.Closeable (:body response))
              (.close ^java.io.Closeable (:body response)))))))))

(deftest signed-access-token
  (let [now (Instant/parse "2026-07-15T00:00:00Z")
        token (access/issue-token test-config now)]
    (is (some? (access/verify-token test-config token now)))
    (is (nil? (access/verify-token test-config (str token "x") now)))
    (is (nil? (access/verify-token test-config token (.plusSeconds now (* 8 24 60 60)))))
    (is (access/valid-access-code? test-config "correct horse battery staple"))
    (is (not (access/valid-access-code? test-config "wrong")))))

(deftest cookie-policy
  (let [development (access/cookie-header test-config "token")
        production (access/cookie-header (assoc test-config :cookie-secure? true) "token")]
    (is (re-find #"HttpOnly" development))
    (is (re-find #"SameSite=Strict" development))
    (is (not (re-find #"Secure" development)))
    (is (re-find #"Secure" production))))

(deftest production-secret-policy
  (is (thrown? clojure.lang.ExceptionInfo
               (config/validate! (assoc test-config
                                        :environment :production
                                        :development? false
                                        :access-code "short"))))
  (is (thrown? clojure.lang.ExceptionInfo
               (config/validate! (assoc test-config
                                        :environment :production
                                        :development? false
                                        :cookie-secret "short"))))
  (is (= :production
         (:environment
          (config/validate! (assoc test-config
                                   :environment :production
                                   :development? false
                                   :cookie-secure? true))))))

(deftest access-csrf-and-session-flow
  (let [data-root (Files/createTempDirectory "ppp-access-test"
                                             (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (let [session-store (store/create-store (assoc test-config :data-dir data-root))
            handler (http/create-handler {:config test-config
                                          :session-store session-store
                                          :readiness (fn [] {:ready? true})})]
        (testing "origin and code are both required"
          (is (= 403 (:status (handler (request :post "/api/access"
                                                {:origin "https://evil.example"
                                                 :body {:code (:access-code test-config)}})))))
          (is (= 401 (:status (handler (request :post "/api/access"
                                                {:body {:code "wrong"}}))))))

        (let [exchange (handler (request :post "/api/access"
                                         {:body {:code (:access-code test-config)}}))
              cookie (cookie-pair exchange)
              bootstrap (handler (request :get "/api/bootstrap" {:cookie cookie}))
              csrf (:csrf-token (response-json bootstrap))]
          (is (= 200 (:status exchange)))
          (is (= 200 (:status bootstrap)))
          (is (= 403 (:status (handler (request :post "/api/sessions"
                                                {:cookie cookie :body {}})))))
          (let [created (handler (request :post "/api/sessions"
                                          {:cookie cookie :csrf csrf :body {}}))
                session-id (:id (response-json created))
                reloaded (handler (request :get (str "/api/sessions/" session-id)
                                           {:cookie cookie}))
                client-runtime
                (handler (request :get (str "/api/sessions/" session-id "/runtime")
                                  {:cookie cookie}))
                runtime-body (response-json client-runtime)]
            (is (= 201 (:status created)))
            (is (= 200 (:status reloaded)))
            (is (= session-id (:id (response-json reloaded))))
            (is (= 200 (:status client-runtime)))
            (is (= 0 (:runtime-version runtime-body)))
            (is (= #{"src/client/runtime/client.cljs"
                     "src/client/runtime/sidebar.cljs"
                     "src/shared/runtime/domain.cljc"
                     "styles/runtime.css"}
                   (set (map :path (:files runtime-body)))))
            (is (not-any? #(str/starts-with? % "src/server/")
                          (map :path (:files runtime-body)))))))
      (finally
        (fs/delete-tree! data-root)))))

(deftest turn-route-returns-an-asynchronous-job
  (let [data-root (Files/createTempDirectory "ppp-turn-route-test"
                                             (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (let [session-store (store/create-store (assoc test-config :data-dir data-root))
            session-id (:id (store/create-session! session-store))
            captured (atom nil)
            handler (http/create-handler {:config test-config
                                          :session-store session-store
                                          :coordinator ::coordinator})
            exchange (handler (request :post "/api/access"
                                       {:body {:code (:access-code test-config)}}))
            cookie (cookie-pair exchange)
            bootstrap (handler (request :get "/api/bootstrap" {:cookie cookie}))
            csrf (:csrf-token (response-json bootstrap))]
        (with-redefs [coordinator/submit-turn!
                      (fn [coordinator-value submitted-session request-value]
                        (reset! captured
                                [coordinator-value submitted-session request-value])
                        {:job-id #uuid "11111111-1111-4111-8111-111111111111"
                         :request-id (:request-id request-value)})]
          (let [response
                (handler
                 (request :post (str "/api/sessions/" session-id "/turns")
                          {:cookie cookie
                           :csrf csrf
                           :body {:prompt "Build a gallery"
                                  :requestTabId
                                  "22222222-2222-4222-8222-222222222222"
                                  :baseVersion 0}}))
                body (response-json response)]
            (is (= 202 (:status response)))
            (is (= "11111111-1111-4111-8111-111111111111"
                   (:job-id body)))
            (is (= ::coordinator (first @captured)))
            (is (= (str session-id) (second @captured)))
            (is (= "Build a gallery" (get-in @captured [2 :prompt]))))))
      (finally
        (fs/delete-tree! data-root)))))
