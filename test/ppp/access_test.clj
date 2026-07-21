(ns ppp.access-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
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
  ([method uri {:keys [body cookie csrf origin remote]
                :or {origin "http://localhost:8787"}}]
   {:request-method method
    :uri uri
    :headers (cond-> {"origin" origin}
               cookie (assoc "cookie" cookie)
               csrf (assoc access/csrf-header csrf))
    :body (when body (body-stream body))
    :remote-addr remote}))

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
                                   :cookie-secure? true)))))
  (is (= :shared-poc (:runtime-profile (config/validate! test-config))))
  (is (= :workspace-repl
         (:runtime-profile
          (config/validate! (assoc test-config
                                   :runtime-profile :workspace-repl)))))
  (is (thrown? clojure.lang.ExceptionInfo
               (config/validate! (assoc test-config
                                        :environment :production
                                        :development? false
                                        :runtime-profile :workspace-repl)))))

(deftest complex-live-turns-have-a-five-minute-provider-ceiling
  (is (= 300000 (config/default-provider-timeout-ms :shared-poc)))
  (is (= 300000 (config/default-provider-timeout-ms :workspace-repl))))

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

(deftest production-shared-password-projects-and-logout-flow
  (let [data-root (Files/createTempDirectory "ppp-login-test"
                                             (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (let [production-config (assoc test-config
                                     :environment :production
                                     :development? false
                                     :fragment-access-enabled? false
                                     :cookie-secure? true
                                     :data-dir data-root)
            session-store (store/create-store production-config)
            handler (http/create-handler {:config production-config
                                          :session-store session-store
                                          :readiness
                                          (fn [] {:ready? true
                                                  :change-capacity
                                                  {:available? true}})})]
        (is (= 404 (:status (handler (request :post "/api/access"
                                              {:body {:code (:access-code test-config)}})))))
        (is (= 403 (:status (handler (request :post "/api/login"
                                              {:origin "https://evil.example"
                                               :body {:password (:access-code test-config)}})))))
        (is (= 401 (:status (handler (request :post "/api/login"
                                              {:remote "judge-a"
                                               :body {:password "wrong"}})))))
        (let [login (handler (request :post "/api/login"
                                      {:remote "judge-a"
                                       :body {:password (:access-code test-config)}}))
              cookie (cookie-pair login)
              bootstrap (handler (request :get "/api/bootstrap" {:cookie cookie}))
              bootstrap-body (response-json bootstrap)
              csrf (:csrf-token bootstrap-body)]
          (is (= 200 (:status login)))
          (is (str/includes? (get-in login [:headers "set-cookie"]) "Secure"))
          (is (= [] (:sessions bootstrap-body)))
          (is (= {:available? true} (:change-capacity bootstrap-body)))
          (let [created (handler (request :post "/api/sessions"
                                          {:cookie cookie
                                           :csrf csrf
                                           :body {:title "Judge gallery"}}))]
            (is (= 201 (:status created)))
            (is (= "Judge gallery" (:title (response-json created))))
            (is (= ["Judge gallery"]
                   (mapv :title
                         (:sessions
                          (response-json
                           (handler (request :get "/api/bootstrap"
                                             {:cookie cookie}))))))))
          (let [logout (handler (request :post "/api/logout"
                                         {:cookie cookie :csrf csrf :body {}}))
                cleared (cookie-pair logout)]
            (is (= 200 (:status logout)))
            (is (str/includes? (get-in logout [:headers "set-cookie"])
                               "Max-Age=0"))
            (is (= 401 (:status (handler (request :get "/api/bootstrap"
                                                  {:cookie cleared}))))))))
      (finally
        (fs/delete-tree! data-root)))))

(deftest shared-password-failures-are-throttled-by-kernel-remote-address
  (let [handler (http/create-handler {:config test-config})]
    (dotimes [_ 10]
      (is (= 401 (:status (handler (request :post "/api/login"
                                            {:remote "judge-a"
                                             :body {:password "wrong"}}))))))
    (let [throttled (handler (request :post "/api/login"
                                      {:remote "judge-a"
                                       :body {:password (:access-code test-config)}}))]
      (is (= 429 (:status throttled)))
      (is (pos? (parse-long (get-in throttled [:headers "retry-after"])))))
    (is (= 200 (:status (handler (request :post "/api/login"
                                          {:remote "judge-b"
                                           :body {:password (:access-code test-config)}})))))))

(deftest login-throttle-sequence-property
  (let [operation-gen
        (gen/tuple (gen/elements ["judge-a" "judge-b"])
                   (gen/frequency [[6 (gen/return :fail)]
                                   [2 (gen/return :clear)]
                                   [2 (gen/return :advance)]])
                   (gen/choose 0 180))
        result
        (tc/quick-check
         1000
         (prop/for-all
          [limit (gen/choose 1 5)
           window-seconds (gen/choose 60 300)
           operations (gen/vector operation-gen 0 50)]
          (let [clock (atom 1000000)
                limiter (access/create-login-limiter
                         {:login-failure-limit limit
                          :login-failure-window-seconds window-seconds
                          :login-now-ms-fn #(deref clock)})
                window-ms (* 1000 window-seconds)
                prune-model
                (fn [model]
                  (let [cutoff (- @clock window-ms)]
                    (into {}
                          (keep (fn [[remote timestamps]]
                                  (let [current (vec (filter #(> % cutoff)
                                                             timestamps))]
                                    (when (seq current) [remote current]))))
                          model)))]
            (:valid?
             (reduce
              (fn [{:keys [model valid?]} [remote operation seconds]]
                (let [model (prune-model model)
                      status (access/login-status limiter remote)
                      expected? (< (count (get model remote [])) limit)
                      valid? (and valid? (= expected? (:available? status)))
                      model
                      (case operation
                        :fail
                        (if expected?
                          (do
                            (access/record-login-failure! limiter remote)
                            (update model remote (fnil conj []) @clock))
                          model)

                        :clear
                        (do
                          (access/clear-login-failures! limiter remote)
                          (dissoc model remote))

                        :advance
                        (do
                          (swap! clock + (* 1000 seconds))
                          model))
                      model (prune-model model)
                      matches?
                      (every?
                       (fn [candidate]
                         (= (< (count (get model candidate [])) limit)
                            (:available?
                             (access/login-status limiter candidate))))
                       ["judge-a" "judge-b"])]
                  {:model model :valid? (and valid? matches?)}))
              {:model {} :valid? true}
              operations))))
         :seed 22017)]
    (is (:pass? result) (pr-str (dissoc result :result-data)))))

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
                        (if (false? (:client-diagnostics request-value))
                          (throw
                           (ex-info "Client diagnostics did not match the bounded contract"
                                    {:code :turn/client-diagnostics-invalid}))
                          (do
                            (reset! captured
                                    [coordinator-value submitted-session request-value])
                            {:job-id #uuid "11111111-1111-4111-8111-111111111111"
                             :request-id (:request-id request-value)})))]
          (let [response
                (handler
                 (request :post (str "/api/sessions/" session-id "/turns")
                          {:cookie cookie
                           :csrf csrf
                           :body {:prompt "Build a gallery"
                                  :requestTabId
                                  "22222222-2222-4222-8222-222222222222"
                                  :baseVersion 0
                                  :clientDiagnostics
                                  [{:kind "action"
                                    :actionId "auth/register"
                                    :status 400
                                    :message "Use a valid sign-in identifier."}]}}))
                body (response-json response)]
            (is (= 202 (:status response)))
            (is (= "11111111-1111-4111-8111-111111111111"
                   (:job-id body)))
            (is (= ::coordinator (first @captured)))
            (is (= (str session-id) (second @captured)))
            (is (= "Build a gallery" (get-in @captured [2 :prompt])))
            (is (= "auth/register"
                   (get-in @captured [2 :client-diagnostics 0 :actionId])))
            (reset! captured nil)
            (let [malformed
                  (handler
                   (request :post (str "/api/sessions/" session-id "/turns")
                            {:cookie cookie
                             :csrf csrf
                             :body {:prompt "Try malformed evidence"
                                    :requestTabId
                                    "22222222-2222-4222-8222-222222222222"
                                    :baseVersion 0
                                    :clientDiagnostics false}}))]
              (is (= 400 (:status malformed)))
              (is (= "turn/client-diagnostics-invalid"
                     (get-in (response-json malformed) [:error :code])))
              (is (nil? @captured))))))
      (finally
        (fs/delete-tree! data-root)))))

(deftest exhausted-provider-capacity-is-a-bounded-http-rejection
  (let [data-root (Files/createTempDirectory "ppp-turn-capacity-test"
                                             (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (let [session-store (store/create-store (assoc test-config :data-dir data-root))
            session-id (:id (store/create-session! session-store))
            handler (http/create-handler {:config test-config
                                          :session-store session-store
                                          :coordinator ::coordinator})
            login (handler (request :post "/api/login"
                                    {:remote "judge"
                                     :body {:password (:access-code test-config)}}))
            cookie (cookie-pair login)
            csrf (:csrf-token
                  (response-json
                   (handler (request :get "/api/bootstrap" {:cookie cookie}))))]
        (with-redefs [coordinator/submit-turn!
                      (fn [& _]
                        (throw (ex-info "capacity"
                                        {:code :provider/capacity-exhausted
                                         :retry-after-seconds 37})))]
          (let [response
                (handler
                 (request :post (str "/api/sessions/" session-id "/turns")
                          {:cookie cookie
                           :csrf csrf
                           :body {:prompt "Change the product"
                                  :requestTabId
                                  "22222222-2222-4222-8222-222222222222"
                                  :baseVersion 0}}))
                body (response-json response)]
            (is (= 429 (:status response)))
            (is (= "37" (get-in response [:headers "retry-after"])))
            (is (= "provider/capacity-exhausted" (get-in body [:error :code])))
            (is (str/includes? (get-in body [:error :message])
                               "temporarily unavailable")))))
      (finally
        (fs/delete-tree! data-root)))))
