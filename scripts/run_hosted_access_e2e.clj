(ns run-hosted-access-e2e
  (:require [babashka.fs :as fs]
            [babashka.process :as process]))

(def data-root (str "target/hosted-access-e2e-" (random-uuid)))
(def port (or (System/getenv "PPP_HOSTED_E2E_PORT") "8799"))
(def base-url (str "http://localhost:" port))
(def shared-password "hosted-judge-password-for-browser-tests")

(def server-environment
  (merge (into {} (System/getenv))
         {"PPP_ENV" "production"
          "PPP_PORT" port
          "PPP_PUBLIC_BASE_URL" base-url
          "PPP_DATA_DIR" data-root
          "PPP_ACCESS_CODE" shared-password
          "PPP_COOKIE_SECRET" "hosted-browser-cookie-secret-with-more-than-thirty-two-characters"
          "PPP_COOKIE_SECURE" "true"
          "PPP_FRAGMENT_ACCESS_ENABLED" "false"
          "PPP_AI_PROVIDER" "fake"
          "PPP_REQUIRE_CLIENT_ACK" "true"}))

(defn- healthy?
  []
  (try
    (zero?
     (:exit
      @(process/process
        ["curl" "--silent" "--show-error" "--fail" "--max-time" "1"
         (str "http://127.0.0.1:" port "/healthz")]
        {:out :string :err :string})))
    (catch Exception _ false)))

(defn- wait-for-server!
  [server]
  (loop [attempt 0]
    (cond
      (not (process/alive? server))
      (throw (ex-info "Hosted access E2E server exited before readiness" {}))

      (healthy?) true

      (>= attempt 240)
      (throw (ex-info "Hosted access E2E server was not ready within 60 seconds" {}))

      :else
      (do
        (Thread/sleep 250)
        (recur (inc attempt))))))

(defn- start-server!
  []
  (let [server (process/process ["clojure" "-M:run"]
                                {:env server-environment
                                 :out :inherit
                                 :err :inherit})]
    (wait-for-server! server)
    server))

(defn- stop-server!
  [server]
  (when server
    (process/destroy-tree server)
    (loop [attempt 0]
      (when (and (process/alive? server) (< attempt 80))
        (Thread/sleep 100)
        (recur (inc attempt))))))

(defn- run-browser-phase!
  [phase]
  (process/shell
   {:extra-env {"PPP_E2E_BASE_URL" base-url
                "PPP_E2E_PASSWORD" shared-password
                "PPP_E2E_APP_ENV" "production"
                "PPP_HOSTED_ACCESS_PHASE" phase}}
   "npx" "playwright" "test" "--reporter=line"
   "e2e/hosted_access.spec.mjs"))

(fs/create-dirs data-root)
(try
  (let [first-server (start-server!)]
    (try
      (run-browser-phase! "before-restart")
      (finally
        (stop-server! first-server))))
  (let [second-server (start-server!)]
    (try
      (run-browser-phase! "after-restart")
      (finally
        (stop-server! second-server))))
  (finally
    (fs/delete-tree data-root)))
