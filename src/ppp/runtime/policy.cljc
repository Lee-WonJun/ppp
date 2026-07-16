(ns ppp.runtime.policy
  (:require [clojure.string :as str]
            #?(:cljs [goog.crypt :as crypt])
            #?(:cljs [goog.string :as gstring])))

(def capability-version 1)

(def server-denied-symbols
  '[. .. new doto import
    eval load-string load-file compile
    future future-call pmap pcalls
    agent send send-off await await-for shutdown-agents
    alter-var-root intern ns-resolve resolve requiring-resolve
    slurp spit load-reader add-watch remove-watch])

(def client-denied-symbols
  '[eval load-string load-file compile
    future future-call pmap pcalls
    alter-var-root intern ns-resolve resolve requiring-resolve
    add-watch remove-watch])

(def nonreactive-client-state-pattern
  #"(?s)\(\s*(?:cljs\.core/)?(?:atom|volatile!)(?:\s|\))")

(def allowed-source-patterns
  [#"^src/server/[a-z0-9_/-]+\.clj$"
   #"^src/client/[a-z0-9_/-]+\.cljs$"
   #"^src/shared/[a-z0-9_/-]+\.cljc$"
   #"^styles/[a-z0-9_/-]+\.css$"
   #"^test/[a-z0-9_/-]+_test\.clj[sc]?$"])

(def fixed-entrypoints
  {"src/server/runtime/server.clj" "runtime.server"
   "src/client/runtime/client.cljs" "runtime.client"
   "src/client/runtime/sidebar.cljs" "runtime.sidebar"
   "src/shared/runtime/domain.cljc" "runtime.domain"})

(def forbidden-source-fragments
  ["java.lang"
   "java.io"
   "java.nio"
   "clojure.java.shell"
   "clojure.java.io"
   "babashka.process"
   "Runtime/getRuntime"
   "System/"
   "Class/forName"
   "js/eval"
   "js/Function"
   "js/process"
   "load-string"
   "load-file"
   "slurp"
   "spit"
   "future-call"
   "pmap"])

(def forbidden-style-patterns [])

(defn validate-runtime-css
  [css]
  (when-let [pattern (some #(when (re-find % (str css)) %)
                           forbidden-style-patterns)]
    {:code :source/forbidden-style-capability
     :pattern (str pattern)}))

(def forbidden-sql-code-patterns
  [#"(?i)\battach\b"
   #"(?i)\bdetach\b"
   #"(?i)\bload_extension\b"
   #"(?i)\bpragma\b"
   #"(?i)\bvacuum\b"
   #"(?i)\btemp(?:orary)?\b"
   #"(?i)\breadfile\s*\("
   #"(?i)\bwritefile\s*\("])

(def reserved-sql-patterns
  [#"(?i)\b_ppp_[a-z0-9_]*\b"])

(def allowed-sql-prefixes
  [#"(?is)^create\s+table\b"
   #"(?is)^create\s+(?:unique\s+)?index\b"
   #"(?is)^alter\s+table\b.+\badd\s+column\b"
   #"(?is)^insert\s+into\b"
   #"(?is)^update\b"
   #"(?is)^delete\s+from\b"])

(defn normalize-source-path
  [path]
  (-> (str path)
      (str/replace "\\" "/")
      (str/replace #"^\./" "")))

(defn allowed-source-path?
  [path]
  (let [path (normalize-source-path path)]
    (and (not (str/blank? path))
         (not (str/starts-with? path "/"))
         (not-any? #{".."} (str/split path #"/"))
         (some #(re-matches % path) allowed-source-patterns))))

(defn- namespace-declared?
  [content expected]
  (boolean
   (re-find (re-pattern (str "(?s)\\(ns\\s+"
                             #?(:clj (java.util.regex.Pattern/quote expected)
                                :cljs (gstring/regExpEscape expected))
                             "(?:\\s|\\)|\\[|\\()"))
            content)))

(defn utf8-size
  [value]
  #?(:clj (alength (.getBytes ^String (str value) "UTF-8"))
     :cljs (count (crypt/stringToUtf8ByteArray (str value)))))

(defn validate-write
  [{:keys [path content]}]
  (let [path (normalize-source-path path)
        expected-ns (get fixed-entrypoints path)]
    (cond
      (not (allowed-source-path? path))
      {:code :source/path-not-allowed :path path}

      (not (string? content))
      {:code :source/content-not-string :path path}

      (and expected-ns (not (namespace-declared? content expected-ns)))
      {:code :source/entrypoint-namespace :path path :expected expected-ns}

      (some #(str/includes? content %) forbidden-source-fragments)
      {:code :source/forbidden-capability
       :path path
       :fragment (some #(when (str/includes? content %) %) forbidden-source-fragments)}

      (and (str/starts-with? path "styles/")
           (validate-runtime-css content))
      (assoc (validate-runtime-css content) :path path)

      :else nil)))

(defn- emit-sql-statement
  [statements statement code]
  (let [sql (str/trim (str/join "" statement))]
    (cond-> statements
      (not (str/blank? sql))
      (conj {:sql sql
             :code (str/trim (str/join "" code))}))))

(defn analyze-sql
  [sql]
  (loop [chars (seq (str sql))
         state :code
         statement []
         code []
         statements []]
    (if-let [ch (first chars)]
      (let [next-ch (second chars)]
        (case state
          :code
          (cond
            (= ch \')
            (recur (next chars) :single-quote (conj statement ch) (conj code " ") statements)

            (= ch \")
            (recur (next chars) :double-quote (conj statement ch) (conj code " ") statements)

            (= ch \`)
            (recur (next chars) :backtick (conj statement ch) (conj code " ") statements)

            (= ch \[)
            (recur (next chars) :bracket (conj statement ch) (conj code " ") statements)

            (and (= ch \-) (= next-ch \-))
            (recur (nnext chars) :line-comment
                   (conj statement "--") (conj code "  ") statements)

            (and (= ch \/) (= next-ch \*))
            (recur (nnext chars) :block-comment
                   (conj statement "/*") (conj code "  ") statements)

            (= ch \;)
            (recur (next chars) :code [] []
                   (emit-sql-statement statements statement code))

            :else
            (recur (next chars) :code (conj statement ch) (conj code ch) statements))

          :single-quote
          (if (= ch \')
            (if (= next-ch \')
              (recur (nnext chars) :single-quote
                     (conj statement "''") (conj code "  ") statements)
              (recur (next chars) :code (conj statement ch) (conj code " ") statements))
            (recur (next chars) :single-quote
                   (conj statement ch) (conj code " ") statements))

          :double-quote
          (if (= ch \")
            (if (= next-ch \")
              (recur (nnext chars) :double-quote
                     (conj statement "\"\"") (conj code "  ") statements)
              (recur (next chars) :code (conj statement ch) (conj code " ") statements))
            (recur (next chars) :double-quote
                   (conj statement ch) (conj code " ") statements))

          :backtick
          (if (= ch \`)
            (if (= next-ch \`)
              (recur (nnext chars) :backtick
                     (conj statement "``") (conj code "  ") statements)
              (recur (next chars) :code (conj statement ch) (conj code " ") statements))
            (recur (next chars) :backtick
                   (conj statement ch) (conj code " ") statements))

          :bracket
          (if (= ch \])
            (recur (next chars) :code (conj statement ch) (conj code " ") statements)
            (recur (next chars) :bracket
                   (conj statement ch) (conj code " ") statements))

          :line-comment
          (if (or (= ch \newline) (= ch \return))
            (recur (next chars) :code (conj statement ch) (conj code ch) statements)
            (recur (next chars) :line-comment
                   (conj statement ch) (conj code " ") statements))

          :block-comment
          (if (and (= ch \*) (= next-ch \/))
            (recur (nnext chars) :code
                   (conj statement "*/") (conj code "  ") statements)
            (recur (next chars) :block-comment
                   (conj statement ch) (conj code " ") statements))))
      {:balanced? (contains? #{:code :line-comment} state)
       :statements (emit-sql-statement statements statement code)})))

(defn split-sql-statements
  [sql]
  (mapv :sql (:statements (analyze-sql sql))))

(defn normalize-migration-name
  [name]
  (if (string? name)
    (str/replace name #"(?i)\.sql$" "")
    name))

(defn validate-migration
  [{:keys [name sql]}]
  (let [normalized-name (normalize-migration-name name)
        {:keys [balanced? statements]} (analyze-sql sql)
        code (str/join "\n" (map :code statements))]
    (cond
      (not (and (string? normalized-name)
                (re-matches #"[a-z0-9][a-z0-9_-]{0,63}" normalized-name)))
      {:code :sql/invalid-name :name name}

      (or (str/blank? sql) (empty? statements))
      {:code :sql/empty :name name}

      (not balanced?)
      {:code :sql/unbalanced :name name}

      (some #(re-find % sql) reserved-sql-patterns)
      {:code :sql/forbidden :name name}

      (some #(re-find % code) forbidden-sql-code-patterns)
      {:code :sql/forbidden :name name}

      (some (fn [{:keys [code]}]
              (not-any? #(re-find % code) allowed-sql-prefixes))
            statements)
      {:code :sql/statement-not-allowed :name name}

      :else nil)))

(defn validate-change
  [{:keys [writes deletes migrations]} {:keys [source-file-limit source-byte-limit]}]
  (let [source-file-limit (or source-file-limit #?(:clj Long/MAX_VALUE
                                                   :cljs js/Number.MAX_SAFE_INTEGER))
        source-byte-limit (or source-byte-limit #?(:clj Long/MAX_VALUE
                                                   :cljs js/Number.MAX_SAFE_INTEGER))
        paths (concat (map :path writes) deletes)
        bytes (reduce + 0 (map #(utf8-size (or (:content %) "")) writes))]
    (vec
     (remove nil?
             (concat
              (when (> (count (set paths)) source-file-limit)
                [{:code :source/too-many-files
                  :limit source-file-limit}])
              (when (> bytes source-byte-limit)
                [{:code :source/too-large
                  :limit source-byte-limit
                  :actual bytes}])
              (map validate-write writes)
              (map (fn [path]
                     (when-not (allowed-source-path? path)
                       {:code :source/delete-path-not-allowed :path path}))
                   deletes)
              (map validate-migration migrations))))))

(def capability-catalog
  {:version capability-version
   :source
   {:writable-roots ["src/server" "src/client" "src/shared" "styles" "test"]
    :entrypoints fixed-entrypoints
    :limits {:files 32 :bytes 262144}}
   :server
   {:namespaces
    {'runtime.api
     ['register-action! 'query! 'execute! 'public-http! 'connector-http!]
     'runtime.test
     ['invoke!]
     'clojure.string
     ['blank? 'trim 'triml 'trimr 'lower-case 'upper-case
      'includes? 'starts-with? 'ends-with? 'split 'split-lines 'join 'replace]
     'clojure.test
     ['deftest 'is 'testing
      'do-report 'test-var 'try-expr 'assert-expr 'function?
      '*testing-contexts* '*testing-vars*]}
    :denied-symbols server-denied-symbols}
   :client
   {:namespaces
    {'runtime.api
     ['register-page! 'register-sidebar! 'navigate! 'action! 'ensure-action!
      'start-interval! 'stop-interval! 'page-state 'event-value 'prevent-default!]
     'clojure.string
     ['blank? 'trim 'triml 'trimr 'lower-case 'upper-case
      'includes? 'starts-with? 'ends-with? 'split 'split-lines 'join 'replace]}
    :denied-symbols client-denied-symbols}
   :sql
   {:allowed ["CREATE TABLE" "CREATE INDEX" "ALTER TABLE ADD COLUMN"
              "INSERT" "UPDATE" "DELETE"]
    :reserved-prefix "_ppp_"}
   :contracts
   {:change
    ["Return complete contents only for files that actually change; preserve every other current file."
     "Every change must keep the four fixed entrypoint namespaces and register exactly :home plus one sidebar."
     "Migration :name is a lowercase slug without a path; the host assigns its sequence and .sql filename."
     "Never use Java interop, shell, filesystem, eval, or dependencies. JavaScript interop is allowed only in generated client source inside the opaque-origin product frame."]
    :server
    ["A registered action handler receives the submitted payload map directly, never a {:params ...} wrapper."
     "query! and execute! work only inside action handlers and only with literal SQL also present in source."
     "Use query! for one SELECT and execute! for INSERT, UPDATE, or DELETE with positional ? parameters."
     "Return plain EDN/Transit values. Register every action exactly once with a keyword id."]
    :tests
    ["Keep test/runtime/domain_test.cljc and update it whenever domain or business behavior changes."
     "Use clojure.test deftest/is/testing for observable business rules and runtime.test/invoke! to exercise registered server actions against the staged SQLite database."
     "Tests execute before commit in a transaction that is always rolled back. Do not test copy, CSS classes, DOM nesting, private call order, or incidental implementation details."
     "For persisted mutations, assert the initial result, one mutation delta, and reconstruction through the read action. For weighted voting: no votes score 0, public adds exactly 1, judge adds exactly 3, and ties have deterministic order."
     "A LEFT JOIN aggregate must explicitly map the synthetic NULL joined row to zero; never let CASE ELSE award points when the joined vote id or type is NULL."]
    :client
    ["The page component argument contains only host context such as :session-id; product state lives in @api/page-state."
     "api/page-state is the host-owned reactive atom value, not a function: dereference it with @api/page-state and update it with swap! or reset!."
     "Keep durable, replacement-safe product state in api/page-state. Local atoms are allowed for disposable implementation details, but changes that must redraw or survive replacement belong in api/page-state."
     "Use (swap! api/page-state assoc ...) for local navigation, form fields, and voter choice; do not invent server actions for local UI state."
     "Use (api/ensure-action! :items/list {} :items/data) during render to load once per runtime version."
     "Use (api/action! :votes/create {:project-id id :voter-type voter-type} :items/data) in events; the third argument stores the response at that page-state key."
     "Generated client code runs in a disposable opaque-origin iframe. Normal in-frame JavaScript interop, DOM, timers, requestAnimationFrame, keyboard events, Canvas/WebGL, audio, workers, WebAssembly, refs, and resource elements are available."
     "The frame has no authenticated cookies or direct parent DOM access. Never attempt to read or modify the parent/top document; use runtime.api action and sidebar callbacks for host operations."
     "Prefer frame-lifetime browser listeners and timers for rich interactions. Replacing or rejecting the frame cleans them up automatically; api/start-interval! remains an optional keyed convenience."
     "Preserve a working sidebar with session selector, new session, conversation, checkpoints, Message composer, and send/restore callbacks."]
    :safe-example
    {:server
     "(api/register-action! :projects/list (fn [_] {:projects (api/query! \"SELECT id, name FROM projects ORDER BY id\" [])}))\n(api/register-action! :votes/create (fn [{:keys [project-id voter-type]}] (api/execute! \"INSERT INTO votes (project_id, voter_type) VALUES (?, ?)\" [project-id voter-type]) {:projects (api/query! \"SELECT id, name FROM projects ORDER BY id\" [])}))"
     :client
     "(defn vote! [id] (api/action! :votes/create {:project-id id :voter-type (or (:voter-type @api/page-state) \"public\")} :projects/data))\n(defn page [_] (api/ensure-action! :projects/list {} :projects/data) (let [projects (:projects (:projects/data @api/page-state)) route (or (:route @api/page-state) :gallery) step (or (:local-step @api/page-state) 0)] [:main [:output (str step)] [:button {:on-click #(swap! api/page-state update :local-step (fnil inc 0))} \"Advance\"] [:button {:on-click #(swap! api/page-state assoc :route :leaderboard)} \"Leaderboard\"] (for [[rank project] (map-indexed vector projects)] ^{:key (:id project)} [:article [:span (str (inc rank))] [:button {:on-click #(vote! (:id project))} \"Vote\"]])]))"
     :migration
     "{:name \"create-projects\" :sql \"CREATE TABLE projects (...); INSERT INTO projects (...) VALUES (...);\"}"}}})
