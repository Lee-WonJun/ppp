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

(def client-forbidden-source-fragments
  ["js/eval" "js/Function" "js/process"])

(defn- forbidden-fragments-for
  [path]
  (if (str/starts-with? path "src/client/")
    client-forbidden-source-fragments
    forbidden-source-fragments))

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
        expected-ns (get fixed-entrypoints path)
        forbidden-fragments (forbidden-fragments-for path)]
    (cond
      (not (allowed-source-path? path))
      {:code :source/path-not-allowed :path path}

      (not (string? content))
      {:code :source/content-not-string :path path}

      (and expected-ns (not (namespace-declared? content expected-ns)))
      {:code :source/entrypoint-namespace :path path :expected expected-ns}

      (some #(str/includes? content %) forbidden-fragments)
      {:code :source/forbidden-capability
       :path path
       :fragment (some #(when (str/includes? content %) %) forbidden-fragments)}

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
     ['register-action! 'query! 'execute! 'public-http! 'connector-http!
      'auth-register! 'auth-login! 'auth-logout! 'auth-current-user
      'auth-require-user! 'auth-change-password! 'auth-delete-account!
      'blob-put! 'blob-get 'blob-list 'blob-delete! 'publish!
      'register-job! 'schedule-job! 'job-status 'cancel-job!
      'register-ingress! 'search-upsert! 'search-delete! 'search-query]
     'runtime.test
     ['invoke! 'invoke-as! 'invoke-job! 'invoke-ingress!]
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
      'initialize-state! 'start-interval! 'stop-interval! 'page-state
      'register-event-handler! 'event-value 'prevent-default!]
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
     "Product accounts are session-owned Kernel resources. Use auth-register!, auth-login!, auth-logout!, auth-current-user, auth-require-user!, auth-change-password!, and auth-delete-account!; never implement password storage or session tokens in generated tables."
     "auth-register! and auth-login! accept {:identifier string :password string} and return only public {:id :identifier :created-at} claims. Login cookies are applied by the host only after the entire action transaction succeeds."
     "Keep roles, profiles, preferences, ownership, and authorization rules in ordinary generated tables keyed by the public user :id. auth-require-user! supplies that id without exposing credentials, cookies, or Kernel tables."
     "Use blob-put!, blob-get, blob-list, and blob-delete! for durable session-owned binary objects. Content crosses the action bridge as bounded base64; no filesystem path is exposed."
     "blob-put! accepts {:id :name :content-type :content-base64}; blob-get and blob-delete! accept an id; blob-list takes no arguments. Objects are limited to 4 MiB and 64 per session by default."
     "publish! accepts a keyword topic and bounded plain payload. It is an effect: the Kernel broadcasts it only after the enclosing action, job, or ingress transaction commits. Never use it as durable state; reconnect through a read action."
     "Register a job once at source evaluation with (register-job! :reports/build handler). Inside an action, job, or ingress call (schedule-job! :reports/build payload {:delay-ms n :max-attempts n :idempotency-key string}). schedule-job! returns a public job map such as {:id string :handler keyword :status keyword}; pass (:id scheduled-job), never the whole map, to job-status or cancel-job!. The Kernel owns timing, leases, retries, and threads."
     "Register a public route once at source evaluation with (register-ingress! :route-name options handler). The handler receives bounded {:method :query :headers :body} and returns exactly {:status integer :body plain-value}; options may name a developer-owned :verifier alias from the supplied catalog."
     "search-upsert! accepts collection, document id, and {:text string :metadata plain-map :vector optional-finite-number-vector}; search-delete! accepts collection and id; search-query accepts collection, query string, and optional {:vector :limit :text-weight :vector-weight}. Search is session-local and Unicode-aware."
     "Blob, job, ingress, event, and search operations share the enclosing SQLite transaction, database quota, checkpoint, and restore boundary. Do not duplicate them with generated tables merely to bypass their limits."
     "Return plain EDN/Transit values. Register every action exactly once with a keyword id."]
    :tests
    ["Keep test/runtime/domain_test.cljc and update it whenever domain or business behavior changes."
     "Use clojure.test deftest/is/testing for observable business rules and runtime.test/invoke! to exercise registered server actions against the staged SQLite database."
     "For authenticated behavior, create an account through its registered action, retain the returned public user id, and call runtime.test/invoke-as! with user-id, action-id, and payload."
     "Use runtime.test/invoke-job! and runtime.test/invoke-ingress! to prove observable generated handler rules inside the same rollback-only staged database."
     "Tests execute before commit in a transaction that is always rolled back. Do not test copy, CSS classes, DOM nesting, private call order, or incidental implementation details."
     "For persisted mutations, assert the initial result, one mutation delta, and reconstruction through the read action. For weighted voting: no votes score 0, public adds exactly 1, judge adds exactly 3, and ties have deterministic order."
     "A LEFT JOIN aggregate must explicitly map the synthetic NULL joined row to zero; never let CASE ELSE award points when the joined vote id or type is NULL."]
    :client
    ["The page component argument contains only host context such as :session-id; product state lives in @api/page-state."
     "api/page-state is the host-owned reactive atom value, not a function: dereference it with @api/page-state and update it with swap! or reset!."
     "Declare new product-state defaults once with api/initialize-state! at source evaluation. Activation fills only missing keys, preserves existing user values, and discards other staging-time state mutations."
     "Keep durable, replacement-safe product state in api/page-state. Local atoms are allowed for disposable implementation details, but changes that must redraw or survive replacement belong in api/page-state."
     "Use (swap! api/page-state assoc ...) for local navigation, form fields, and voter choice; do not invent server actions for local UI state."
     "Use (api/ensure-action! :items/list {} :items/data) during render to load once per runtime version."
     "Use (api/action! :votes/create {:project-id id :voter-type voter-type} :items/data) in events; the third argument stores the response at that page-state key."
     "Generated client code runs in a disposable opaque-origin iframe. Normal in-frame JavaScript interop, DOM, timers, requestAnimationFrame, keyboard events, Canvas/WebGL, audio, workers, WebAssembly, refs, and resource elements are available."
     "The frame has no authenticated cookies or direct parent DOM access. Never attempt to read or modify the parent/top document; use runtime.api action and sidebar callbacks for host operations."
     "Register product event callbacks once with register-event-handler! during source evaluation. Events are ephemeral hints; reconstruct durable truth through a server action after reconnect."
     "Prefer frame-lifetime browser listeners and timers for rich interactions. Replacing or rejecting the frame cleans them up automatically. Call api/start-interval! once at source evaluation when using the keyed convenience; it registers during staging, starts callbacks only after activation, and does not need to be called from render."
     "Preserve a working sidebar with session selector, new session, conversation, checkpoints, Message composer, and send/restore callbacks."]
    :safe-example
    {:server
     "(api/register-action! :projects/list (fn [_] {:projects (api/query! \"SELECT id, name FROM projects ORDER BY id\" [])}))\n(api/register-action! :votes/create (fn [{:keys [project-id voter-type]}] (api/execute! \"INSERT INTO votes (project_id, voter_type) VALUES (?, ?)\" [project-id voter-type]) {:projects (api/query! \"SELECT id, name FROM projects ORDER BY id\" [])}))"
     :client
     "(defn vote! [id] (api/action! :votes/create {:project-id id :voter-type (or (:voter-type @api/page-state) \"public\")} :projects/data))\n(defn page [_] (api/ensure-action! :projects/list {} :projects/data) (let [projects (:projects (:projects/data @api/page-state)) route (or (:route @api/page-state) :gallery) step (or (:local-step @api/page-state) 0)] [:main [:output (str step)] [:button {:on-click #(swap! api/page-state update :local-step (fnil inc 0))} \"Advance\"] [:button {:on-click #(swap! api/page-state assoc :route :leaderboard)} \"Leaderboard\"] (for [[rank project] (map-indexed vector projects)] ^{:key (:id project)} [:article [:span (str (inc rank))] [:button {:on-click #(vote! (:id project))} \"Vote\"]])]))"
     :migration
     "{:name \"create-projects\" :sql \"CREATE TABLE projects (...); INSERT INTO projects (...) VALUES (...);\"}"
     :resources
     "(defn process! [{:keys [id]}] (api/search-upsert! :objects id {:text (str id \" processed\") :metadata {:processed true}}) (api/publish! :objects/processed {:id id}) {:processed id})\n(defn upload! [{:keys [id name content-type content-base64]}] (let [object (api/blob-put! {:id id :name name :content-type content-type :content-base64 content-base64})] (api/search-upsert! :objects id {:text name :metadata {:name name}}) (api/publish! :objects/changed {:id id}) {:object object}))\n(api/register-action! :objects/upload upload!)\n(api/register-job! :objects/process process!)"}}})
