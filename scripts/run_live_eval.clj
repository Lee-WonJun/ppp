(ns run-live-eval
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.time Instant)
           (java.time.format DateTimeFormatter)))

(def port (or (System/getenv "PPP_LIVE_PORT") "8798"))
(def base-url (str "http://127.0.0.1:" port))
(def access-code "ppp-live-eval")
(def model (or (System/getenv "PPP_CODEX_MODEL") "gpt-5.6-terra"))
(def keep-data? (contains? #{"1" "true" "yes"}
                           (some-> (System/getenv "PPP_LIVE_KEEP_DATA")
                                   str/lower-case)))
(def data-root (str (fs/absolutize (str "target/live-eval-data-" (random-uuid)))))
(def timestamp
  (-> (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")
      (.withZone java.time.ZoneOffset/UTC)
      (.format (Instant/now))))
(def output-root (str (fs/absolutize (str "artifacts/live-eval/" timestamp))))
(def observations-path (str (fs/path output-root "observations.json")))
(def project-root (fs/absolutize "."))
(def work-root (fs/create-temp-dir {:prefix "ppp-live-eval-work-"}))

(def server-environment
  (merge (into {} (System/getenv))
         {"PPP_ENV" "test"
          "PPP_HOST" "127.0.0.1"
          "PPP_PORT" port
          "PPP_PUBLIC_BASE_URL" base-url
          "PPP_DATA_DIR" data-root
          "PPP_ACCESS_CODE" access-code
          "PPP_COOKIE_SECRET" "live-eval-only-cookie-secret-do-not-share"
          "PPP_AI_PROVIDER" "codex"
          "PPP_CODEX_MODEL" model
          "PPP_PROVIDER_TIMEOUT_MS" "240000"
          "PPP_CLIENT_ACK_TIMEOUT_MS" "20000"}))

(defn curl-json
  [path]
  (let [result
        @(process/process
          ["curl" "--silent" "--show-error" "--fail" "--max-time" "2"
           (str base-url path)]
          {:out :string :err :string})]
    (when (zero? (:exit result))
      (json/parse-string (:out result) true))))

(defn wait-for-server!
  [server]
  (loop [attempt 0]
    (cond
      (not (process/alive? server))
      (throw (ex-info "Live-eval server exited before readiness" {}))

      (curl-json "/healthz")
      true

      (>= attempt 240)
      (throw (ex-info "Live-eval server did not start within 60 seconds" {}))

      :else
      (do
        (Thread/sleep 250)
        (recur (inc attempt))))))

(defn assert-codex-readiness!
  []
  (let [readiness (curl-json "/readyz")
        provider (:provider readiness)]
    (when-not (and (:ready? readiness)
                   (:ready? provider)
                   (= "codex" (:provider provider))
                   (= "chatgpt-oauth" (:auth provider)))
      (throw (ex-info
              "Live evaluation requires a ready Codex ChatGPT OAuth provider"
              {:readiness readiness})))
    (println (pr-str {:live-eval :ready
                      :provider (:provider provider)
                      :auth (:auth provider)
                      :model model}))))

(defn run-command
  [command options]
  @(process/process command (merge {:out :inherit :err :inherit} options)))

(defn prepare-work-root!
  []
  (let [result
        (run-command
         ["rsync" "-a"
          "--exclude=.git"
          "--exclude=.shadow-cljs"
          "--exclude=target"
          "--exclude=data"
          "--exclude=artifacts"
          "--exclude=node_modules"
          "--exclude=test-results"
          "--exclude=playwright-report"
          (str project-root "/")
          (str work-root "/")]
         {})]
    (when-not (zero? (:exit result))
      (throw (ex-info "Could not prepare an isolated live-eval work tree"
                      {:exit (:exit result)}))))
  (fs/create-sym-link (fs/path work-root "node_modules")
                      (fs/path project-root "node_modules"))
  work-root)

(fs/create-dirs output-root)
(println (pr-str {:live-eval :starting
                  :output output-root
                  :scenarios 8
                  :runs 3
                  :records 24}))

(prepare-work-root!)

(let [release-result (run-command ["bb" "client-release"] {:dir (str work-root)})]
  (when-not (zero? (:exit release-result))
    (throw (ex-info "The client release build failed" {:exit (:exit release-result)}))))

(let [server (process/process ["clojure" "-M:run"]
                              {:dir (str work-root)
                               :env server-environment
                               :out :inherit
                               :err :inherit})
      browser-exit (atom 1)
      report-exit (atom 1)]
  (try
    (try
      (wait-for-server! server)
      (assert-codex-readiness!)
      (let [result
            (run-command
             ["npx" "playwright" "test"
              "--config=live-eval/playwright.config.mjs"
              "live-eval.spec.mjs"
              "--reporter=line"]
             {:dir (str work-root)
              :extra-env {"PPP_LIVE_BASE_URL" base-url
                          "PPP_LIVE_ACCESS_CODE" access-code
                          "PPP_LIVE_OBSERVATIONS" observations-path}})]
        (reset! browser-exit (:exit result)))
      (finally
        (process/destroy-tree server)))
    (when-not (fs/regular-file? observations-path)
      (throw (ex-info "The browser evaluation produced no observations"
                      {:path observations-path})))
    (let [report-result
          (run-command
           ["clojure" "-M" "-m" "ppp.eval.live"
            data-root observations-path output-root model]
           {})]
      (reset! report-exit (:exit report-result)))
    (finally
      (when (process/alive? server)
        (process/destroy-tree server))
      (if keep-data?
        (println (pr-str {:live-eval :kept-debug-data :path data-root}))
        (fs/delete-tree data-root))
      (fs/delete-tree work-root)))
  (when-not (and (zero? @browser-exit) (zero? @report-exit))
    (System/exit 1)))
