(ns run-public-demo-showcase
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def public-origin "https://ppp.openai.slopbook.org")
(def base-url
  (-> (or (System/getenv "PPP_PUBLIC_DEMO_URL") public-origin)
      str/trim
      (str/replace #"/+$" "")))
(def password (some-> (System/getenv "PPP_DEMO_PUBLIC_PASSWORD") not-empty))
(def capture-root
  (some-> (System/getenv "PPP_PUBLIC_CAPTURE_ROOT") not-empty fs/absolutize str))

(when-not (= public-origin base-url)
  (throw (ex-info "Public showcase must use the judge origin"
                  {:code :public-demo/invalid-origin})))
(when-not password
  (throw (ex-info "PPP_DEMO_PUBLIC_PASSWORD is required"
                  {:code :public-demo/password-missing})))
(when-not capture-root
  (throw (ex-info "PPP_PUBLIC_CAPTURE_ROOT is required"
                  {:code :public-demo/capture-root-missing})))

(def observations-path (fs/path capture-root "observations.json"))
(when-not (fs/regular-file? observations-path)
  (throw (ex-info "Public capture observations are missing"
                  {:code :public-demo/observations-missing})))

(def observations (json/parse-string (slurp (str observations-path)) true))
(def session-id (:session-id observations))
(when-not (re-matches #"[0-9a-f-]{36}" (str session-id))
  (throw (ex-info "Public capture session identifier is invalid"
                  {:code :public-demo/session-invalid})))

(def output-root (str (fs/path capture-root "showcase")))
(fs/create-dirs output-root)

(let [result
      @(process/process
        ["npx" "playwright" "test"
         "--config=live-eval/demo-story.config.mjs"
         "public-demo-showcase.spec.mjs"
         "--reporter=line"]
        {:out :inherit
         :err :inherit
         :extra-env
         {"PPP_LIVE_BASE_URL" base-url
          "PPP_DEMO_PUBLIC_PASSWORD" password
          "PPP_DEMO_PUBLIC_SESSION_ID" session-id
          "PPP_DEMO_LIVE_CAPTURE" "1"
          "PPP_DEMO_LIVE_PLAYWRIGHT_OUTPUT" output-root}})]
  (when-not (zero? (:exit result))
    (throw (ex-info "The public showcase capture failed"
                    {:code :public-demo/showcase-failed
                     :exit (:exit result)}))))

(println (pr-str {:public-demo :showcase-captured
                  :origin base-url
                  :output output-root}))
