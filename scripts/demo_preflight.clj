(ns demo-preflight
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def required-commands ["docker" "npx" "curl"])
(def required-files
  ["Dockerfile"
   "docker-compose.yml"
   "demo/playwright.config.mjs"
   "demo/demo-rehearsal.spec.mjs"])

(doseq [command required-commands]
  (when-not (fs/which command)
    (throw (ex-info (str "Missing demo command: " command) {:command command}))))

(doseq [path required-files]
  (when-not (fs/regular-file? path)
    (throw (ex-info (str "Missing demo file: " path) {:path path}))))

(doseq [command [["docker" "info" "--format" "{{.ServerVersion}}"]
                 ["docker" "compose" "version" "--short"]
                 ["npx" "playwright" "--version"]]]
  (let [result @(process/process command {:out :string :err :string})]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "Demo preflight failed: " (str/join " " command))
                      {:command command
                       :exit (:exit result)
                       :error (:err result)})))))

(println "Demo preflight passed: Docker, Compose, Playwright, and packaged rehearsal files are ready.")
