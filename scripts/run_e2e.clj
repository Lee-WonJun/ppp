(ns run-e2e
  (:require [babashka.fs :as fs]
            [babashka.process :as process]))

(def data-root (str "target/e2e-data-" (random-uuid)))
(def e2e-port (or (System/getenv "PPP_E2E_PORT") "8797"))
(def base-url (str "http://127.0.0.1:" e2e-port))

(def server-environment
  (merge (into {} (System/getenv))
         {"PPP_ENV" "test"
          "PPP_PORT" e2e-port
          "PPP_PUBLIC_BASE_URL" base-url
          "PPP_DATA_DIR" data-root
          "PPP_ACCESS_CODE" "ppp-local"
          "PPP_COOKIE_SECRET" "development-only-cookie-secret-change-before-sharing"
          "PPP_AI_PROVIDER" "fake"}))

(defn ready?
  []
  (try
    (zero?
     (:exit
      @(process/process
        ["curl" "--silent" "--show-error" "--fail" "--max-time" "1"
         (str base-url "/healthz")]
        {:out :string :err :string})))
    (catch Exception _ false)))

(defn wait-for-server!
  [server]
  (loop [attempt 0]
    (cond
      (not (process/alive? server))
      (throw (ex-info "E2E server exited before becoming ready" {}))

      (ready?)
      true

      (>= attempt 240)
      (throw (ex-info "E2E server did not become ready within 60 seconds" {}))

      :else
      (do
        (Thread/sleep 250)
        (recur (inc attempt))))))

(let [server (process/process ["clojure" "-M:run"]
                              {:env server-environment
                               :out :inherit
                               :err :inherit})]
  (try
    (wait-for-server! server)
    (apply process/shell
           {:extra-env {"PPP_E2E_BASE_URL" base-url}}
           (into ["npx" "playwright" "test" "--reporter=line"]
                 *command-line-args*))
    (finally
      (process/destroy-tree server)
      (fs/delete-tree data-root))))
