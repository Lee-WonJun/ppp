(ns run-demo-rehearsal
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def image "programmable-programming-page:demo")
(def run-count (or (some-> (System/getenv "PPP_DEMO_RUNS") parse-long) 3))
(def skip-build? (= "true" (System/getenv "PPP_DEMO_SKIP_BUILD")))
(def timestamp
  (.format (.withZone (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")
                      java.time.ZoneOffset/UTC)
           (java.time.Instant/now)))
(def artifact-root (fs/path "artifacts" "demo" timestamp))
(def records (atom []))
(def image-digest (atom nil))

(defn- command!
  [& command]
  (let [result @(process/process (vec command) {:out :string :err :string})]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "Command failed: " (str/join " " command))
                      {:command command
                       :exit (:exit result)
                       :stdout (:out result)
                       :stderr (:err result)})))
    result))

(defn- free-port
  []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- ready?
  [base-url]
  (try
    (zero? (:exit @(process/process
                    ["curl" "--silent" "--fail" "--max-time" "1"
                     (str base-url "/readyz")]
                    {:out :string :err :string})))
    (catch Exception _ false)))

(defn- wait-ready!
  [container-name base-url]
  (loop [attempt 0]
    (cond
      (ready? base-url) true
      (>= attempt 120)
      (throw (ex-info "Packaged demo container was not ready within 60 seconds"
                      {:container container-name
                       :state (:out (command! "docker" "inspect" container-name
                                              "--format" "{{json .State}}"))
                       :logs (:out (command! "docker" "logs" container-name))}))
      :else (do (Thread/sleep 500) (recur (inc attempt))))))

(defn- create-volume!
  [name run-id]
  (command! "docker" "volume" "create"
            "--label" "ppp.demo=true"
            "--label" (str "ppp.demo.run=" run-id)
            name))

(defn- run-browser!
  [base-url output-dir]
  (let [environment (merge (into {} (System/getenv))
                           {"PPP_DEMO_BASE_URL" base-url
                            "PPP_DEMO_OUTPUT_DIR" (str output-dir)})
        result @(process/process
                 ["npx" "playwright" "test"
                  "--config=demo/playwright.config.mjs"
                  "demo-rehearsal.spec.mjs"
                  "--reporter=line"]
                 {:env environment :out :inherit :err :inherit})]
    (when-not (zero? (:exit result))
      (throw (ex-info "Packaged demo browser story failed"
                      {:exit (:exit result)})))))

(defn- rehearse-once!
  [run-number]
  (let [run-id (str run-number "-" (random-uuid))
        container-name (str "ppp-demo-" run-id)
        data-volume (str "ppp-demo-data-" run-id)
        codex-volume (str "ppp-demo-codex-" run-id)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        output-dir (fs/path artifact-root (str "run-" run-number))
        started (System/nanoTime)]
    (fs/create-dirs output-dir)
    (try
      (create-volume! data-volume run-id)
      (create-volume! codex-volume run-id)
      (command! "docker" "run" "--detach"
                "--name" container-name
                "--label" "ppp.demo=true"
                "--label" (str "ppp.demo.run=" run-id)
                "--read-only"
                "--tmpfs" "/tmp:rw,noexec,nosuid,nodev,size=256m"
                "--cap-drop" "ALL"
                "--security-opt" "no-new-privileges:true"
                "--publish" (str "127.0.0.1:" port ":8787")
                "--volume" (str data-volume ":/var/lib/ppp")
                "--volume" (str codex-volume ":/var/lib/codex")
                "--env" "PPP_ENV=test"
                "--env" "PPP_ACCESS_CODE=ppp-local"
                "--env" "PPP_COOKIE_SECRET=development-only-cookie-secret-change-before-sharing"
                "--env" "PPP_AI_PROVIDER=fake"
                "--env" "PPP_REQUIRE_CLIENT_ACK=true"
                "--env" (str "PPP_PUBLIC_BASE_URL=" base-url)
                image)
      (wait-ready! container-name base-url)
      (run-browser! base-url (fs/absolutize output-dir))
      {:run run-number
       :passed? true
       :duration-ms (quot (- (System/nanoTime) started) 1000000)}
      (finally
        (try (command! "docker" "rm" "--force" container-name)
             (catch Exception _ nil))
        (try (command! "docker" "volume" "rm" "--force" data-volume)
             (catch Exception _ nil))
        (try (command! "docker" "volume" "rm" "--force" codex-volume)
             (catch Exception _ nil))))))

(fs/create-dirs artifact-root)

(try
  (when-not skip-build?
    (command! "docker" "build" "--platform" "linux/amd64" "--tag" image "."))
  (reset! image-digest
          (str/trim (:out (command! "docker" "image" "inspect" image
                                    "--format" "{{.Id}}"))))
  (doseq [run-number (range 1 (inc run-count))]
    (let [record (try
                   (rehearse-once! run-number)
                   (catch Exception cause
                     {:run run-number
                      :passed? false
                      :error (ex-message cause)}))]
      (swap! records conj record)
      (when-not (:passed? record)
        (throw (ex-info "Consecutive packaged demo rehearsal failed" record)))))
  (println (str "Packaged demo rehearsal passed " run-count "/" run-count
                ". Report: " (fs/path artifact-root "report.edn")))
  (finally
    (let [passed (count (filter :passed? @records))]
      (spit (str (fs/path artifact-root "report.edn"))
            (str (pr-str {:format-version 1
                          :created-at (str (java.time.Instant/now))
                          :image image
                          :image-digest @image-digest
                          :requested-runs run-count
                          :completed-runs (count @records)
                          :passed passed
                          :failed (- (count @records) passed)
                          :records @records})
                 "\n")))))
