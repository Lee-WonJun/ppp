(ns run-demo-live
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.time Instant)
           (java.time.format DateTimeFormatter)))

(def port (or (System/getenv "PPP_DEMO_LIVE_PORT") "8798"))
(def base-url (str "http://127.0.0.1:" port))
(def access-code "ppp-demo-live")
(def model (or (System/getenv "PPP_CODEX_MODEL") "gpt-5.6-terra"))
(def capture? (contains? #{"1" "true" "yes"}
                         (some-> (System/getenv "PPP_DEMO_LIVE_CAPTURE")
                                 str/lower-case)))
(def keep-data? (contains? #{"1" "true" "yes"}
                           (some-> (System/getenv "PPP_DEMO_LIVE_KEEP_DATA")
                                   str/lower-case)))
(def resume-data-root (some-> (System/getenv "PPP_DEMO_LIVE_RESUME_DATA_ROOT")
                              str/trim
                              not-empty))
(def resume-output-root (some-> (System/getenv "PPP_DEMO_LIVE_RESUME_OUTPUT_ROOT")
                                str/trim
                                not-empty))
(def resume? (and resume-data-root resume-output-root))

(when (not= (boolean resume-data-root) (boolean resume-output-root))
  (throw (ex-info
          "Demo-live resume requires both data and output roots"
          {:code :demo-live/resume-arguments-invalid})))

(def data-root
  (str (fs/absolutize
        (or resume-data-root
            (str "target/demo-live-data-" (random-uuid))))))
(def timestamp
  (-> (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")
      (.withZone java.time.ZoneOffset/UTC)
      (.format (Instant/now))))
(def output-root
  (str (fs/absolutize
        (or resume-output-root
            (str (if capture?
                   "artifacts/demo-capture/"
                   "artifacts/demo-live/")
                 timestamp)))))
(def observations-path (str (fs/path output-root "observations.json")))
(def playwright-output (str (fs/path output-root "playwright")))
(def project-root (fs/absolutize "."))
(def work-root (fs/create-temp-dir {:prefix "ppp-demo-live-work-"}))

(def server-environment
  (merge (into {} (System/getenv))
         {"PPP_ENV" "test"
          "PPP_HOST" "127.0.0.1"
          "PPP_PORT" port
          "PPP_PUBLIC_BASE_URL" base-url
          "PPP_DATA_DIR" data-root
          "PPP_ACCESS_CODE" access-code
          "PPP_COOKIE_SECRET" "demo-live-only-cookie-secret-do-not-share"
          "PPP_AI_PROVIDER" "codex"
          "PPP_CODEX_MODEL" model
          "PPP_PROVIDER_TIMEOUT_MS" "600000"
          "PPP_CLIENT_ACK_TIMEOUT_MS" "45000"
          "PPP_CHANGE_GENERATION_ATTEMPTS" "3"}))

(defn- curl-json
  [path]
  (let [result
        @(process/process
          ["curl" "--silent" "--show-error" "--fail" "--max-time" "2"
           (str base-url path)]
          {:out :string :err :string})]
    (when (zero? (:exit result))
      (json/parse-string (:out result) true))))

(defn- wait-for-server!
  [server]
  (loop [attempt 0]
    (cond
      (not (process/alive? server))
      (throw (ex-info "Demo-live server exited before readiness" {}))

      (curl-json "/healthz") true

      (>= attempt 240)
      (throw (ex-info "Demo-live server did not start within 60 seconds" {}))

      :else
      (do (Thread/sleep 250)
          (recur (inc attempt))))))

(defn- assert-codex-readiness!
  []
  (let [readiness (curl-json "/readyz")
        provider (:provider readiness)]
    (when-not (and (:ready? readiness)
                   (:ready? provider)
                   (= "codex" (:provider provider))
                   (= "chatgpt-oauth" (:auth provider)))
      (throw (ex-info
              "The final demo rehearsal requires ready Codex ChatGPT OAuth"
              {:readiness readiness})))
    (println (pr-str {:demo-live :ready
                      :provider (:provider provider)
                      :auth (:auth provider)
                      :model model}))))

(defn- run-command
  [command options]
  @(process/process command (merge {:out :inherit :err :inherit} options)))

(defn- prepare-work-root!
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
          "--exclude=playwright-report*"
          (str project-root "/")
          (str work-root "/")]
         {})]
    (when-not (zero? (:exit result))
      (throw (ex-info "Could not prepare an isolated demo-live tree"
                      {:exit (:exit result)}))))
  (fs/create-sym-link (fs/path work-root "node_modules")
                      (fs/path project-root "node_modules"))
  work-root)

(fs/create-dirs output-root)
(when (and resume?
           (or (not (fs/directory? data-root))
               (not (fs/regular-file? observations-path))))
  (throw (ex-info "Demo-live resume state is incomplete"
                  {:code :demo-live/resume-state-missing})))

(println (pr-str {:demo-live :starting
                  :output output-root
                  :scenarios 6
                  :sessions 1
                  :provider :codex
                  :capture capture?
                  :resume-existing (boolean resume?)}))

(prepare-work-root!)

(let [release-result (run-command ["bb" "client-release"]
                                  {:dir (str work-root)})]
  (when-not (zero? (:exit release-result))
    (throw (ex-info "The client release build failed"
                    {:exit (:exit release-result)}))))

(let [server (process/process ["clojure" "-M:run"]
                              {:dir (str work-root)
                               :env server-environment
                               :out :inherit
                               :err :inherit})
      browser-exit (atom 1)
      report-exit (atom 1)
      editor-exit (atom (if capture? 1 0))]
  (try
    (try
      (wait-for-server! server)
      (assert-codex-readiness!)
      (let [result
            (run-command
             ["npx" "playwright" "test"
              "--config=live-eval/demo-story.config.mjs"
              "demo-story.spec.mjs"
              "--reporter=line"]
             {:dir (str work-root)
              :extra-env
              (cond-> {"PPP_LIVE_BASE_URL" base-url
                       "PPP_DEMO_LIVE_ACCESS_CODE" access-code
                       "PPP_DEMO_LIVE_OBSERVATIONS" observations-path
                       "PPP_DEMO_LIVE_PLAYWRIGHT_OUTPUT" playwright-output}
                capture? (assoc "PPP_DEMO_LIVE_CAPTURE" "1")
                capture? (assoc "PPP_DEMO_SEMANTIC_REPAIRS" "8")
                resume? (assoc "PPP_DEMO_LIVE_RESUME_FINAL" "1"))})]
        (reset! browser-exit (:exit result)))
      (finally
        (process/destroy-tree server)))
    (if (fs/regular-file? observations-path)
      (let [report-result
            (run-command
             ["clojure" "-M" "-m" "ppp.eval.demo-story"
              data-root observations-path output-root model]
             {})]
        (reset! report-exit (:exit report-result)))
      (println (pr-str {:demo-live :report-skipped
                        :reason :no-browser-observations
                        :path observations-path})))
    (when (and capture?
               (zero? @browser-exit)
               (zero? @report-exit))
      (let [videos (->> (fs/glob playwright-output "**/*.webm")
                        (filter fs/regular-file?)
                        vec)
            raw-video (when (seq videos)
                        (apply max-key fs/size videos))]
        (if raw-video
          (let [edit-result
                (run-command
                 ["bb" "scripts/edit_demo_video.clj"
                  (str raw-video) observations-path output-root]
                 {})]
            (reset! editor-exit (:exit edit-result)))
          (println (pr-str {:demo-live :capture-missing
                            :path playwright-output})))))
    (finally
      (when (process/alive? server)
        (process/destroy-tree server))
      (let [failed? (not (and (zero? @browser-exit)
                              (zero? @report-exit)
                              (zero? @editor-exit)))
            retain? (or keep-data? failed?)]
        (if retain?
          (do
            (println (pr-str {:demo-live :kept-debug-data
                              :reason (if failed? :failure :requested)
                              :path data-root}))
            (println (pr-str {:demo-live :kept-debug-work
                              :reason (if failed? :failure :requested)
                              :path (str work-root)})))
          (do
            (fs/delete-tree data-root)
            (fs/delete-tree work-root))))))
  (when-not (and (zero? @browser-exit)
                 (zero? @report-exit)
                 (zero? @editor-exit))
    (System/exit 1)))
