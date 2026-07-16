(ns secret-scan
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def excluded-directories
  #{".git" ".cpcache" ".clj-kondo" ".shadow-cljs" ".lsp" ".gstack"
    "node_modules" "target" "data" "playwright-report" "test-results"})

(def forbidden-file-names
  #{"auth.json" ".env" "connectors.edn"})

(defn- forbidden-candidate-path
  [path]
  (let [path (str/replace (str path) "\\" "/")]
    (cond
      (str/ends-with? path "/observations.json") "raw live observation"
      (str/ends-with? path "/.last-run.json") "transient browser result"
      (str/ends-with? path ".jsonl") "provider JSONL"
      (or (str/ends-with? path ".sqlite")
          (str/ends-with? path ".sqlite3")) "session database"
      (str/includes? path "/playwright-report") "transient Playwright report"
      (str/includes? path "resources/public/js/") "generated host bundle"
      (str/includes? path "resources/public/frame-js/") "generated frame bundle"
      :else nil)))

(def credential-patterns
  [{:label "private key"
    :pattern #"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"}
   {:label "OpenAI-style API key"
    :pattern #"\bsk-[A-Za-z0-9_-]{20,}\b"}
   {:label "GitHub token"
    :pattern #"\bgh[pousr]_[A-Za-z0-9]{20,}\b"}
   {:label "AWS access key"
    :pattern #"\bAKIA[0-9A-Z]{16}\b"}
   {:label "Slack token"
    :pattern #"\bxox[baprs]-[A-Za-z0-9-]{20,}\b"}
   {:label "OAuth token field"
    :pattern #"(?i)\"(?:access_token|refresh_token|id_token)\"\s*:\s*\"[^\"]{20,}\""}])

(defn- git-files
  []
  (try
    (let [{:keys [exit out]}
          @(process/process ["git" "ls-files" "--cached" "--others"
                             "--exclude-standard" "-z"]
                            {:out :string :err :string})]
      (when (zero? exit)
        (->> (str/split out #"\u0000")
             (remove str/blank?)
             (map fs/path))))
    (catch Exception _ nil)))

(defn- excluded-path?
  [path]
  (some excluded-directories
        (map str (iterator-seq (.iterator (fs/path path))))))

(defn- filesystem-files
  []
  (->> (fs/glob "." "**" {:hidden true})
       (filter fs/regular-file?)
       (remove excluded-path?)))

(defn- candidate-files
  []
  (or (seq (git-files)) (filesystem-files)))

(defn- text-content
  [path]
  (try
    (when (<= (fs/size path) (* 2 1024 1024))
      (slurp (str path)))
    (catch Exception _ nil)))

(defn- scan-file
  [path]
  (let [name (str (fs/file-name path))
        content (text-content path)
        path-finding (forbidden-candidate-path path)]
    (concat
     (when path-finding
       [{:path (str path) :finding path-finding}])
     (when (forbidden-file-names name)
       [{:path (str path) :finding (str "forbidden file name: " name)}])
     (when content
       (keep (fn [{:keys [label pattern]}]
               (when (re-find pattern content)
                 {:path (str path) :finding label}))
             credential-patterns)))))

(let [files (vec (candidate-files))
      findings (vec (mapcat scan-file files))]
  (if (seq findings)
    (do
      (binding [*out* *err*]
        (println "Secret scan failed:")
        (doseq [{:keys [path finding]} findings]
          (println (str "- " path ": " finding))))
      (System/exit 1))
    (println (str "Secret scan passed: " (count files) " project files inspected."))))
