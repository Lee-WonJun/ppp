(ns run-public-demo-capture
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.time Instant)
           (java.time.format DateTimeFormatter)))

(def public-origin "https://ppp.openai.slopbook.org")
(def base-url
  (-> (or (System/getenv "PPP_PUBLIC_DEMO_URL") public-origin)
      str/trim
      (str/replace #"/+$" "")))
(def password (some-> (System/getenv "PPP_DEMO_PUBLIC_PASSWORD") not-empty))
(def timestamp
  (-> (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")
      (.withZone java.time.ZoneOffset/UTC)
      (.format (Instant/now))))
(def output-root
  (str (fs/absolutize
        (or (System/getenv "PPP_PUBLIC_DEMO_RESUME_OUTPUT")
            (System/getenv "PPP_PUBLIC_DEMO_OUTPUT")
            (str "artifacts/public-demo-capture/" timestamp)))))
(def resume? (boolean (System/getenv "PPP_PUBLIC_DEMO_RESUME_OUTPUT")))
(def observations-path (str (fs/path output-root "observations.json")))
(def playwright-output
  (str (fs/path output-root (if resume?
                              (str "playwright-resume-" timestamp)
                              "playwright"))))

(when-not (= public-origin base-url)
  (throw (ex-info "Public demo capture must use the judge origin"
                  {:code :public-demo/invalid-origin})))
(when-not password
  (throw (ex-info "PPP_DEMO_PUBLIC_PASSWORD is required"
                  {:code :public-demo/password-missing})))

(defn- request-json
  [path]
  (let [result
        @(process/process
          ["curl" "--silent" "--show-error" "--fail" "--max-time" "15"
           (str base-url path)]
          {:out :string :err :string})]
    (when-not (zero? (:exit result))
      (throw (ex-info "Public judge endpoint is unavailable"
                      {:code :public-demo/http-unavailable
                       :path path
                       :exit (:exit result)})))
    (json/parse-string (:out result) true)))

(defn- assert-readiness!
  []
  (request-json "/healthz")
  (let [ready (request-json "/readyz")
        provider (:provider ready)]
    (when-not (and (:ready? ready)
                   (:ready? provider)
                   (= "codex" (:provider provider))
                   (= "chatgpt-oauth" (:auth provider)))
      (throw (ex-info "Public judge is not ready with real Codex OAuth"
                      {:code :public-demo/provider-not-ready
                       :provider (select-keys provider
                                              [:provider :auth :ready?])})))
    (println (pr-str {:public-demo :ready
                      :origin base-url
                      :provider (:provider provider)
                      :auth (:auth provider)}))))

(defn- run-capture!
  []
  (fs/create-dirs output-root)
  (let [result
        @(process/process
          ["npx" "playwright" "test"
           "--config=live-eval/demo-story.config.mjs"
           "demo-story.spec.mjs"
           "--reporter=line"]
          {:out :inherit
           :err :inherit
           :extra-env
           {"PPP_LIVE_BASE_URL" base-url
            "PPP_DEMO_PUBLIC_CAPTURE" "1"
            "PPP_DEMO_PUBLIC_PASSWORD" password
            "PPP_DEMO_LIVE_CAPTURE" "1"
            "PPP_DEMO_SEMANTIC_REPAIRS"
            (or (System/getenv "PPP_DEMO_SEMANTIC_REPAIRS") "1")
            "PPP_DEMO_LIVE_OBSERVATIONS" observations-path
            "PPP_DEMO_LIVE_PLAYWRIGHT_OUTPUT" playwright-output
            "PPP_DEMO_LIVE_RESUME_FINAL" (if resume? "1" "0")}})]
    (when-not (zero? (:exit result))
      (throw (ex-info "The public live capture failed"
                      {:code :public-demo/capture-failed
                       :exit (:exit result)})))))

(assert-readiness!)
(println (pr-str {:public-demo :starting
                  :origin base-url
                  :output output-root
                  :provider :codex
                  :scenarios 5
                  :resume resume?}))
(run-capture!)
(println (pr-str {:public-demo :captured
                  :output output-root
                  :observations observations-path}))
