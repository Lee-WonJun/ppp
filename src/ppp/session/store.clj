(ns ppp.session.store
  (:require [clojure.string :as str]
            [ppp.runtime.policy :as policy]
            [ppp.runtime.sqlite :as sqlite]
            [ppp.shared.protocol :as protocol]
            [ppp.util.fs :as fs])
  (:import (java.nio.file Files LinkOption Path Paths)
           (java.time Instant)
           (java.util UUID)
           (java.util.concurrent.locks ReentrantLock)))

(def format-version 1)

(def ^:private no-links (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(def ^:private initial-source-template
  {"src/server/runtime/server.clj"
   "(ns runtime.server\n  (:require [runtime.api :as api]))\n\n(api/register-action!\n :ping\n (fn [_request]\n   {:ok true :message \"The runtime is ready.\"}))\n"

   "src/client/runtime/client.cljs"
   "(ns runtime.client\n  (:require [runtime.api :as api]))\n\n(defn page [_context]\n  [:main {:aria-label \"Blank product canvas\"}])\n\n(api/register-page! :home page)\n"

   "src/client/runtime/sidebar.cljs"
   "(ns runtime.sidebar\n  (:require [runtime.api :as api]))\n\n(defn session-option [{:keys [id title]}]\n  [:option {:value id} title])\n\n(defn message-row [{:keys [role text status]}]\n  [:article {:class (str \"runtime-message runtime-message-\" (name role))}\n   [:p text]\n   (when status [:small status])])\n\n(defn sidebar [{:keys [sessions session-id messages checkpoints draft busy? progress\n                              select-session! new-session! restore! draft-change! send!]}]\n  [:aside.runtime-sidebar {:aria-label \"Product conversation\"}\n   [:header.runtime-sidebar-header\n    [:select {:aria-label \"Current session\"\n              :value (str session-id)\n              :on-change #(select-session! (api/event-value %))}\n     (for [session sessions]\n       ^{:key (:id session)} [session-option session])]\n    [:button {:type \"button\"\n              :aria-label \"New session\"\n              :on-click new-session!}\n     \"+\"]]\n   [:section.runtime-conversation {:aria-live \"polite\"}\n    (if (seq messages)\n      (for [message messages]\n        ^{:key (:id message)} [message-row message])\n      [:p.runtime-empty \"Describe what you want to make.\"])\n    (when progress\n      [:p.runtime-progress progress])]\n   (when (seq checkpoints)\n     [:section.runtime-checkpoints {:aria-label \"Checkpoints\"}\n      [:strong \"Checkpoints\"]\n      (for [{:keys [runtime-version title]} (reverse checkpoints)]\n        ^{:key runtime-version}\n        [:button {:type \"button\" :disabled busy?\n                  :on-click #(restore! runtime-version)}\n         title])])\n   [:form.runtime-composer\n    {:on-submit (fn [event]\n                  (api/prevent-default! event)\n                  (when-not busy? (send!)))}\n    [:textarea {:aria-label \"Message\"\n                :placeholder \"Ask, plan, or change the product\"\n                :value draft\n                :disabled busy?\n                :on-change #(draft-change! (api/event-value %))}]\n    [:button {:type \"submit\" :disabled (or busy? (empty? draft))}\n     (if busy? \"Working\" \"Send\")]]])\n\n(api/register-sidebar! sidebar)\n"

   "src/shared/runtime/domain.cljc"
   "(ns runtime.domain)\n\n(defn valid-title? [value]\n  (and (string? value) (not (empty? value))))\n"

   "styles/runtime.css"
   ":host { color: #171714; }\n.runtime-sidebar { pointer-events: auto; position: fixed; inset: 0 0 0 auto; display: grid; grid-template-rows: auto minmax(0, 1fr) auto auto; width: min(var(--ppp-sidebar-width), 100vw); min-height: 100dvh; border-left: 1px solid #deddd6; background: #fbfaf6; box-shadow: -18px 0 60px rgb(23 23 20 / 8%); }\n.runtime-sidebar-header { display: flex; gap: 8px; padding: 16px 58px 14px 16px; border-bottom: 1px solid #e5e3dc; }\n.runtime-sidebar-header select { min-width: 0; flex: 1; border: 1px solid #d6d4ca; border-radius: 9px; background: #fff; padding: 9px 10px; }\n.runtime-sidebar-header button { width: 38px; border: 1px solid #d6d4ca; border-radius: 9px; background: #fff; cursor: pointer; }\n.runtime-conversation { overflow: auto; padding: 24px 18px; }\n.runtime-empty { max-width: 230px; margin: 18vh auto 0; color: #77756d; text-align: center; line-height: 1.5; }\n.runtime-message { max-width: 340px; margin: 0 0 18px; line-height: 1.5; }\n.runtime-message p { margin: 0; white-space: pre-wrap; }\n.runtime-message-user { margin-left: auto; padding: 11px 13px; border-radius: 12px 12px 3px 12px; background: #eceae2; }\n.runtime-message-assistant { margin-right: auto; }\n.runtime-message small, .runtime-progress { color: #77756d; }\n.runtime-checkpoints { display: grid; gap: 7px; max-height: 150px; overflow: auto; padding: 12px 16px; border-top: 1px solid #e5e3dc; }\n.runtime-checkpoints strong { color: #77756d; font-size: 12px; }\n.runtime-checkpoints button { border: 1px solid #d6d4ca; border-radius: 8px; background: #fff; padding: 8px 9px; text-align: left; cursor: pointer; }\n.runtime-composer { display: grid; grid-template-columns: 1fr auto; gap: 9px; padding: 14px 16px 18px; border-top: 1px solid #e5e3dc; background: #fbfaf6; }\n.runtime-composer textarea { min-height: 68px; resize: none; border: 1px solid #cfcdc3; border-radius: 11px; background: #fff; padding: 11px 12px; color: inherit; }\n.runtime-composer button { align-self: end; min-height: 40px; border: 0; border-radius: 10px; background: #285a43; padding: 0 16px; color: #fff; cursor: pointer; }\n.runtime-composer button:disabled { cursor: default; opacity: .5; }\n.runtime-progress { margin-top: 16px; }\n"

   "test/runtime/domain_test.cljc"
   "(ns runtime.domain-test\n  (:require [clojure.test :refer [deftest is]]\n            [runtime.domain :as domain]))\n\n(deftest title-rule\n  (is (domain/valid-title? \"A product\"))\n  (is (not (domain/valid-title? \"\"))))\n"})

(def ^:private initial-progress-source
  (str
   "(defn progress-status [phase detail]\n"
   "  [:p.runtime-progress\n"
   "   {:role \"status\" :aria-label (str phase \". \" detail)}\n"
   "   [:span.runtime-progress-phase phase]\n"
   "   [:span.runtime-progress-dots {:aria-hidden true}\n"
   "    [:span.runtime-progress-dots-fill \"...\"]]\n"
   "   [:span.runtime-progress-separator {:aria-hidden true} \" · \"]\n"
   "   [:span.runtime-progress-detail detail]])\n\n"))

(def ^:private initial-progress-css
  (str
   ".runtime-progress { display: flex; align-items: baseline; min-width: 0; margin: 16px 0 0; overflow: hidden; white-space: nowrap; }\n"
   ".runtime-progress-phase, .runtime-progress-dots, .runtime-progress-separator { flex: none; }\n"
   ".runtime-progress-phase { font-weight: 600; }\n"
   ".runtime-progress-dots { display: inline-block; width: 1.5em; overflow: hidden; }\n"
   ".runtime-progress-dots-fill { display: inline-block; width: 0; overflow: hidden; animation: runtime-progress-dots 1.2s linear infinite; }\n"
   ".runtime-progress-detail { min-width: 0; overflow: hidden; text-overflow: ellipsis; }\n"
   "@keyframes runtime-progress-dots { 0%, 24% { width: 0; } 25%, 49% { width: .5em; } 50%, 74% { width: 1em; } 75%, 100% { width: 1.5em; } }\n"
   "@media (prefers-reduced-motion: reduce) { .runtime-progress-dots-fill { width: 1.5em; animation: none; } }\n"))

(defn- with-progress-status-contract
  [source]
  (-> source
      (str/replace "(defn sidebar "
                   (str initial-progress-source "(defn sidebar "))
      (str/replace "draft busy? progress\n"
                   "draft busy? progress progress-detail\n")
      (str/replace "(when progress\n      [:p.runtime-progress progress])"
                   "(when progress\n      [progress-status progress progress-detail])")))

(def initial-source
  (-> initial-source-template
      (update "src/client/runtime/sidebar.cljs" with-progress-status-contract)
      (update "styles/runtime.css" str initial-progress-css)))

(defrecord Store [^Path sessions-root config locks])

(declare create-checkpoint! checkpoint-root journal-path write-journal!
         read-checkpoint validate-checkpoint!)

(defn create-store
  [{:keys [data-dir] :as config}]
  (let [root (.resolve ^Path data-dir "workspaces/local/sessions")]
    (fs/ensure-dir! root)
    (fs/assert-no-symlinks! root)
    (->Store root config (atom {}))))

(defn parse-session-id
  [value]
  (try
    (cond
      (uuid? value) value
      (string? value) (UUID/fromString value)
      :else (throw (IllegalArgumentException. "Session identifier is not a UUID")))
    (catch IllegalArgumentException cause
      (throw (ex-info "Invalid session identifier"
                      {:code :session/invalid-id :value (str value)}
                      cause)))))

(defn- parse-transaction-id
  [value]
  (try
    (cond
      (uuid? value) value
      (string? value) (UUID/fromString value)
      :else (throw (IllegalArgumentException. "Transaction identifier is not a UUID")))
    (catch IllegalArgumentException cause
      (throw (ex-info "Invalid transaction identifier"
                      {:code :transaction/invalid-id :value (str value)}
                      cause)))))

(defn- parse-runtime-version
  [value]
  (when-not (nat-int? value)
    (throw (ex-info "Invalid runtime version"
                    {:code :runtime/invalid-version :value value})))
  value)

(defn session-root
  [^Store store session-id]
  (.resolve ^Path (:sessions-root store) (str (parse-session-id session-id))))

(defn current-root
  [store session-id]
  (.resolve (session-root store session-id) "current"))

(defn current-source-root
  [store session-id]
  (.resolve (current-root store session-id) "source"))

(defn current-db-path
  [store session-id]
  (.resolve (current-root store session-id) "app.sqlite"))

(defn current-migrations-root
  [store session-id]
  (.resolve (current-root store session-id) "migrations"))

(defn manifest-path
  [store session-id]
  (.resolve (current-root store session-id) "manifest.edn"))

(defn- lock-for
  [^Store store session-id]
  (let [session-id (parse-session-id session-id)]
    (or (get @(:locks store) session-id)
        (get (swap! (:locks store)
                    #(if (contains? % session-id)
                       %
                       (assoc % session-id (ReentrantLock. true))))
             session-id))))

(defn with-session-lock
  [store session-id thunk]
  (let [^ReentrantLock lock (lock-for store session-id)]
    (.lock lock)
    (try
      (thunk)
      (finally
        (.unlock lock)))))

(defn- source-manifest
  [^Path source-root version migrations created-at]
  (fs/assert-no-symlinks! source-root)
  {:format-version format-version
   :capability-version policy/capability-version
   :runtime-version version
   :files
   (into (sorted-map)
         (for [^Path path (fs/list-tree source-root)
               :when (fs/regular-file? path)]
           [(str/replace (str (.relativize source-root path)) "\\" "/")
            (fs/sha256-file path)]))
   :migrations (vec migrations)
   :created-at created-at
   :updated-at (Instant/now)})

(defn- ensure-entrypoints!
  [^Path source-root]
  (doseq [path (keys policy/fixed-entrypoints)]
    (when-not (fs/regular-file? (fs/safe-child source-root path))
      (throw (ex-info "A fixed runtime entrypoint is missing"
                      {:code :source/missing-entrypoint :path path}))))
  source-root)

(defn- write-source-map!
  [^Path source-root files]
  (fs/delete-tree! source-root)
  (fs/ensure-dir! source-root)
  (doseq [[path content] files]
    (fs/atomic-write-string! (fs/safe-child source-root path) content))
  source-root)

(defn create-session!
  ([store] (create-session! store {}))
  ([^Store store {:keys [title] :or {title "Untitled product"}}]
   (let [session-id (random-uuid)
         root (session-root store session-id)
         current (.resolve root "current")
         source (.resolve current "source")
         now (Instant/now)
         session {:id session-id
                  :workspace-id "local"
                  :title title
                  :transcript-summary nil
                  :format-version format-version
                  :current-version 0
                  :codex-thread-id nil
                  :created-at now
                  :updated-at now}]
     (doseq [directory [root current source
                        (.resolve current "migrations")
                        (.resolve root "history")
                        (.resolve root "checkpoints")
                        (.resolve root "journal")
                        (.resolve root ".staging")]]
       (fs/ensure-dir! directory))
     (write-source-map! source initial-source)
     (ensure-entrypoints! source)
     (let [manifest (source-manifest source 0 [] now)]
       (fs/atomic-write-edn! (.resolve root "session.edn") session)
       (fs/atomic-write-edn! (.resolve current "manifest.edn") manifest)
       (sqlite/init! (.resolve current "app.sqlite"))
       (create-checkpoint! store session-id {:title "Blank canvas" :kind :initial})
       session))))

(defn session-exists?
  [store session-id]
  (fs/regular-file? (.resolve (session-root store session-id) "session.edn")))

(defn get-session
  [store session-id]
  (let [path (.resolve (session-root store session-id) "session.edn")]
    (when-not (fs/regular-file? path)
      (throw (ex-info "Session not found"
                      {:code :session/not-found
                       :session-id (str session-id)})))
    (fs/read-edn path)))

(defn list-sessions
  [^Store store]
  (->> (fs/list-children (:sessions-root store))
       (keep (fn [^Path directory]
               (when (and (fs/directory? directory)
                          (not (fs/symbolic-link? directory)))
                 (let [session-path (.resolve directory "session.edn")]
                   (when (fs/regular-file? session-path)
                     (fs/read-edn session-path))))))
       (sort-by :updated-at #(compare %2 %1))
       vec))

(defn update-session!
  [store session-id updates]
  (with-session-lock
    store session-id
    (fn []
      (let [path (.resolve (session-root store session-id) "session.edn")
            session (merge (get-session store session-id)
                           updates
                           {:updated-at (Instant/now)})]
        (fs/atomic-write-edn! path session)
        session))))

(defn set-codex-thread!
  [store session-id thread-id]
  (let [thread-id
        (when thread-id
          (try
            (str (UUID/fromString (str thread-id)))
            (catch IllegalArgumentException cause
              (throw (ex-info "Invalid Codex thread identifier"
                              {:code :provider/thread-invalid}
                              cause)))))]
    (update-session! store session-id {:codex-thread-id thread-id})))

(defn reset-codex-thread!
  [store session-id]
  (set-codex-thread! store session-id nil))

(defn- validate-manifest-source!
  [^Path source-root manifest]
  (let [actual (:files (source-manifest source-root
                                        (:runtime-version manifest)
                                        (:migrations manifest)
                                        (:created-at manifest)))]
    (when-not (protocol/valid-manifest? manifest)
      (throw (ex-info "Source manifest is invalid"
                      {:code :manifest/schema-invalid})))
    (ensure-entrypoints! source-root)
    (when-not (= (:files manifest) actual)
      (throw (ex-info "Source does not match its manifest"
                      {:code :manifest/hash-mismatch})))
    manifest))

(defn current-manifest
  [store session-id]
  (let [source-root (current-source-root store session-id)
        manifest (fs/read-edn (manifest-path store session-id))]
    (validate-manifest-source! source-root manifest)))

(defn current-source-map
  [store session-id]
  (current-manifest store session-id)
  (let [root (current-source-root store session-id)]
    (fs/assert-no-symlinks! root)
    (into (sorted-map)
          (for [^Path path (fs/list-tree root)
                :when (fs/regular-file? path)]
            [(str/replace (str (.relativize root path)) "\\" "/")
             (fs/read-text path)]))))

(defn stage-source-map
  [{:keys [^Path source]}]
  (fs/assert-no-symlinks! source)
  (into (sorted-map)
        (for [^Path path (fs/list-tree source)
              :when (fs/regular-file? path)]
          [(str/replace (str (.relativize source path)) "\\" "/")
           (fs/read-text path)])))

(defn- next-event-sequence
  [store session-id]
  (let [history (.resolve (session-root store session-id) "history")]
    (inc
     (reduce max 0
             (keep (fn [^Path path]
                     (when (Files/isDirectory path no-links)
                       (some-> (re-find #"^(\d{6})-" (str (.getFileName path)))
                               second
                               parse-long)))
                   (fs/list-tree history))))))

(defn- append-history-unlocked!
  [store session-id {:keys [kind prompt assistant changes before after validation]
                     :as event}]
  (let [sequence (next-event-sequence store session-id)
        slug (-> (name (or kind :event)) (str/replace #"[^a-z0-9-]" "-"))
        history-root (.resolve (session-root store session-id) "history")
        root (.resolve history-root (format "%06d-%s" sequence slug))
        pending-root (.resolve history-root (str ".pending-" (random-uuid)))
        event (merge {:event-sequence sequence
                      :event-id (random-uuid)
                      :created-at (Instant/now)}
                     (dissoc event :prompt :assistant :changes :before :after :validation))]
    (when (fs/exists? root)
      (throw (ex-info "History is append-only" {:sequence sequence})))
    (try
      (fs/ensure-dir! pending-root)
      (fs/atomic-write-edn! (.resolve pending-root "event.edn") event)
      (when prompt (fs/atomic-write-string! (.resolve pending-root "prompt.md") prompt))
      (when assistant (fs/atomic-write-string! (.resolve pending-root "assistant.md") assistant))
      (when changes (fs/atomic-write-edn! (.resolve pending-root "changes.edn") changes))
      (when validation (fs/atomic-write-edn! (.resolve pending-root "validation.edn") validation))
      (doseq [[side files] [[:before before] [:after after]]
              [path content] files]
        (fs/atomic-write-string! (fs/safe-child (.resolve pending-root (name side)) path)
                                 content))
      (fs/move-replacing! pending-root root)
      (assoc event :path (str root))
      (finally
        (fs/delete-tree! pending-root)))))

(defn append-history!
  [store session-id event]
  (with-session-lock store session-id
    #(append-history-unlocked! store session-id event)))

(defn list-history
  [store session-id]
  (let [history (.resolve (session-root store session-id) "history")]
    (->> (fs/list-tree history)
         (filter #(= "event.edn" (str (.getFileName ^Path %))))
         (map fs/read-edn)
         (sort-by :event-sequence)
         vec)))

(defn- limit-or-max
  [value]
  (or value Long/MAX_VALUE))

(defn quota-status
  ([store session-id]
   (quota-status store session-id (current-source-root store session-id)))
  ([^Store store session-id ^Path source-root]
   (let [source-files (count (filter fs/regular-file? (fs/list-tree source-root)))
         source-bytes (fs/directory-size source-root)
         database (current-db-path store session-id)
         checkpoints (.resolve (session-root store session-id) "checkpoints")]
     {:source-files source-files
      :source-bytes source-bytes
      :database-bytes (if (fs/regular-file? database) (Files/size database) 0)
      :checkpoint-bytes (fs/directory-size checkpoints)
      :session-bytes (fs/directory-size (session-root store session-id))
      :instance-bytes (fs/directory-size (:data-dir (:config store)))})))

(defn assert-quota!
  [^Store store session-id source-root]
  (let [{:keys [source-files source-bytes database-bytes checkpoint-bytes instance-bytes]
         :as status} (quota-status store session-id source-root)
        {:keys [source-file-limit source-byte-limit session-db-limit
                checkpoint-limit instance-limit]} (:config store)
        violations
        (cond-> []
          (> source-files (limit-or-max source-file-limit))
          (conj {:resource :source-files :actual source-files :limit source-file-limit})

          (> source-bytes (limit-or-max source-byte-limit))
          (conj {:resource :source-bytes :actual source-bytes :limit source-byte-limit})

          (> database-bytes (limit-or-max session-db-limit))
          (conj {:resource :database :actual database-bytes :limit session-db-limit})

          (> checkpoint-bytes (limit-or-max checkpoint-limit))
          (conj {:resource :checkpoints :actual checkpoint-bytes :limit checkpoint-limit})

          (> instance-bytes (limit-or-max instance-limit))
          (conj {:resource :instance :actual instance-bytes :limit instance-limit}))]
    (when (seq violations)
      (throw (ex-info "Storage quota does not allow a new AI change"
                      {:code :storage/quota-exceeded
                       :violations violations
                       :status status})))
    status))

(defn stage-change!
  [^Store store session-id transaction-id
   {:keys [writes deletes migrations] :as change}]
  (let [transaction-id (parse-transaction-id transaction-id)
        session-root (session-root store session-id)
        stage-root (.resolve (.resolve session-root ".staging") (str transaction-id))
        stage-current (.resolve stage-root "current")
        stage-source (.resolve stage-current "source")
        stage-migrations (.resolve stage-current "migrations")
        old-manifest (current-manifest store session-id)
        old-source (current-source-map store session-id)
        target-version (inc (:runtime-version old-manifest))
        validation-errors (policy/validate-change change (:config store))]
    (fs/delete-tree! stage-root)
    (try
      (when (seq validation-errors)
        (throw (ex-info "Generated change violates source policy"
                        {:code :source/validation-failed
                         :errors validation-errors})))
      (fs/copy-tree! (current-source-root store session-id) stage-source)
      (fs/copy-tree! (.resolve (current-root store session-id) "migrations") stage-migrations)
      (doseq [{:keys [path content]} writes]
        (fs/atomic-write-string! (fs/safe-child stage-source (policy/normalize-source-path path))
                                 content))
      (doseq [path deletes]
        (Files/deleteIfExists
         (fs/safe-child stage-source (policy/normalize-source-path path))))
      (ensure-entrypoints! stage-source)
      (assert-quota! store session-id stage-source)
      (let [starting-index (count (:migrations old-manifest))
            assigned
            (mapv (fn [offset {:keys [name sql]}]
                    (let [name (policy/normalize-migration-name name)
                          file-name (format "%06d-%s.sql" (+ starting-index offset 1) name)]
                      (fs/atomic-write-string! (.resolve stage-migrations file-name) sql)
                      {:file-name file-name :name name :sql sql}))
                  (range)
                  migrations)
            migration-names (into (vec (:migrations old-manifest)) (map :file-name assigned))
            manifest (source-manifest stage-source target-version migration-names
                                      (:created-at old-manifest))
            new-source
            (into (sorted-map)
                  (for [^Path path (fs/list-tree stage-source)
                        :when (fs/regular-file? path)]
                    [(str/replace (str (.relativize stage-source path)) "\\" "/")
                     (fs/read-text path)]))
            changed-paths (set (concat (map :path writes) deletes))]
        (fs/atomic-write-edn! (.resolve stage-current "manifest.edn") manifest)
        {:transaction-id transaction-id
         :root stage-root
         :current stage-current
         :source stage-source
         :migrations stage-migrations
         :database (.resolve stage-current "app.sqlite")
         :manifest manifest
         :assigned-migrations assigned
         :before (select-keys old-source changed-paths)
         :after (select-keys new-source changed-paths)})
      (catch Exception cause
        (fs/delete-tree! stage-root)
        (throw cause)))))

(defn discard-stage!
  [{:keys [root]}]
  (fs/delete-tree! root))

(defn- copy-current-backup!
  [store session-id ^Path backup-current]
  (fs/delete-tree! backup-current)
  (fs/ensure-dir! backup-current)
  (fs/copy-tree! (current-source-root store session-id) (.resolve backup-current "source"))
  (fs/copy-tree! (.resolve (current-root store session-id) "migrations")
                 (.resolve backup-current "migrations"))
  (fs/atomic-write-edn! (.resolve backup-current "manifest.edn")
                        (current-manifest store session-id))
  (sqlite/clone-database! (current-db-path store session-id)
                          (.resolve backup-current "app.sqlite"))
  backup-current)

(defn assert-next-runtime-version!
  "The sole durable transition rule: a committed change or restore must move
  exactly one version beyond the active manifest."
  [base-version target-version]
  (when-not (and (nat-int? base-version)
                 (nat-int? target-version)
                 (= target-version (inc base-version)))
    (throw (ex-info "Staged runtime is not based on the active version"
                    {:code :runtime/base-version-conflict
                     :base-version base-version
                     :target-version target-version})))
  target-version)

(defn materialize-stage!
  [store session-id {:keys [root current database manifest transaction-id recovery]
                     :as stage}]
  (let [base-version (:runtime-version (current-manifest store session-id))
        target-version (:runtime-version manifest)
        backup-current (.resolve ^Path root "before-current")
        journal-file (journal-path store session-id transaction-id)
        moved? (atom false)]
    (assert-next-runtime-version! base-version target-version)
    (when-not (and (fs/regular-file? (.resolve ^Path current "manifest.edn"))
                   (fs/regular-file? database))
      (throw (ex-info "Staged runtime is incomplete"
                      {:code :runtime/stage-incomplete})))
    (try
      (sqlite/set-runtime-version! (sqlite/datasource database) target-version)
      (sqlite/checkpoint-wal! (sqlite/datasource database))
      (copy-current-backup! store session-id backup-current)
      (let [journal
            {:transaction-id transaction-id
             :session-id (parse-session-id session-id)
             :state :prepared
             :base-version base-version
             :target-version target-version
             :before {:manifest-hash (fs/sha256-file (.resolve backup-current "manifest.edn"))
                      :database-hash
                      (sqlite/logical-hash
                       (sqlite/datasource (.resolve backup-current "app.sqlite")))
                      :backup-path (str backup-current)}
             :after {:manifest-hash (fs/sha256-file (.resolve ^Path current "manifest.edn"))
                     :database-hash (sqlite/logical-hash (sqlite/datasource database))
                     :stage-path (str current)}
             :stage-root (str root)
             :commit recovery
             :created-at (Instant/now)}]
        (write-journal! store session-id transaction-id journal)
        (fs/move-replacing! current (current-root store session-id))
        (reset! moved? true)
        (let [committed-manifest (current-manifest store session-id)
              database-version
              (sqlite/runtime-version (sqlite/datasource (current-db-path store session-id)))]
          (when-not (and (= target-version (:runtime-version committed-manifest))
                         (= target-version database-version))
            (throw (ex-info "Materialized runtime versions do not agree"
                            {:code :runtime/materialization-mismatch
                             :manifest-version (:runtime-version committed-manifest)
                             :database-version database-version}))))
        (assoc stage
               :base-version base-version
               :target-version target-version
               :backup-current backup-current
               :journal-file journal-file))
      (catch Exception cause
        (try
          (when (and @moved? (fs/exists? backup-current))
            (fs/move-replacing! backup-current (current-root store session-id)))
          (Files/deleteIfExists journal-file)
          (catch Exception rollback-cause
            (throw (ex-info "Runtime materialization and rollback both failed"
                            {:code :runtime/materialization-recovery-required}
                            rollback-cause))))
        (throw cause)))))

(defn rollback-materialized!
  [store session-id {:keys [root backup-current transaction-id
                            base-version target-version]}]
  (when (fs/exists? backup-current)
    (fs/move-replacing! backup-current (current-root store session-id)))
  (update-session! store session-id {:current-version base-version})
  (fs/delete-tree! (checkpoint-root store session-id target-version))
  (Files/deleteIfExists (journal-path store session-id transaction-id))
  (fs/delete-tree! root)
  (when-not (= base-version
               (sqlite/runtime-version (sqlite/datasource (current-db-path store session-id))))
    (throw (ex-info "Rolled back database version does not match the base version"
                    {:code :runtime/rollback-mismatch})))
  true)

(defn finalize-materialized!
  [store session-id {:keys [root transaction-id]}]
  (Files/deleteIfExists (journal-path store session-id transaction-id))
  (fs/delete-tree! root)
  true)

(defn checkpoint-root
  [store session-id version]
  (.resolve (.resolve (session-root store session-id) "checkpoints")
            (str (parse-runtime-version version))))

(defn- migration-file-hashes
  [^Path migrations-root]
  (if-not (fs/exists? migrations-root)
    (sorted-map)
    (do
      (fs/assert-no-symlinks! migrations-root)
      (into (sorted-map)
            (for [^Path path (fs/list-tree migrations-root)
                  :when (fs/regular-file? path)]
              [(str/replace (str (.relativize migrations-root path)) "\\" "/")
               (fs/sha256-file path)])))))

(defn- validate-migration-snapshot!
  [^Path migrations-root manifest database]
  (let [expected (vec (:migrations manifest))
        files (migration-file-hashes migrations-root)
        records (sqlite/migration-records (sqlite/datasource database))]
    (when-not (= (set expected) (set (keys files)) (set (keys records)))
      (throw (ex-info "Checkpoint migrations are incomplete"
                      {:code :checkpoint/migration-mismatch
                       :expected expected
                       :files (vec (keys files))
                       :database (vec (keys records))})))
    (doseq [file-name expected]
      (when-not (= (get files file-name) (get records file-name))
        (throw (ex-info "Checkpoint migration content does not match SQLite metadata"
                        {:code :checkpoint/migration-hash-mismatch
                         :migration file-name}))))
    true))

(defn create-checkpoint!
  [store session-id {:keys [title kind] :or {kind :change}}]
  (with-session-lock
    store session-id
    (fn []
      (let [manifest (current-manifest store session-id)
            version (:runtime-version manifest)
            root (checkpoint-root store session-id version)
            checkpoints (.getParent root)
            pending (.resolve checkpoints
                              (str ".pending-" version "-" (random-uuid)))]
        (if (fs/exists? root)
          (:metadata (read-checkpoint store session-id version))
          (try
            (fs/ensure-dir! pending)
            (fs/copy-tree! (current-source-root store session-id)
                           (.resolve pending "source"))
            (fs/copy-tree! (current-migrations-root store session-id)
                           (.resolve pending "migrations"))
            (fs/atomic-write-edn! (.resolve pending "manifest.edn") manifest)
            (let [snapshot (.resolve pending "app.sqlite.snapshot")
                  archive (.resolve pending "app.sqlite.gz")]
              (sqlite/clone-database! (current-db-path store session-id) snapshot)
              (sqlite/checkpoint-wal! (sqlite/datasource snapshot))
              (sqlite/assert-integrity! (sqlite/datasource snapshot))
              (validate-migration-snapshot! (.resolve pending "migrations")
                                            manifest snapshot)
              (let [database-content-hash
                    (sqlite/logical-hash (sqlite/datasource snapshot))]
                (fs/gzip-file! snapshot archive)
                (Files/deleteIfExists snapshot)
                (fs/atomic-write-edn!
                 (.resolve pending "checkpoint.edn")
                 {:runtime-version version
                  :title (or title (str "Checkpoint " version))
                  :kind kind
                  :created-at (Instant/now)
                  :manifest-hash (fs/sha256-file (.resolve pending "manifest.edn"))
                  :database-content-hash database-content-hash
                  :database-archive-hash (fs/sha256-file archive)})))
            (fs/move-replacing! pending root)
            (:metadata (read-checkpoint store session-id version))
            (finally
              (fs/delete-tree! pending))))))))

(defn list-checkpoints
  [store session-id]
  (let [root (.resolve (session-root store session-id) "checkpoints")]
    (->> (fs/list-tree root)
         (filter #(= "checkpoint.edn" (str (.getFileName ^Path %))))
         (map fs/read-edn)
         (sort-by :runtime-version)
         vec)))

(defn read-checkpoint
  [store session-id version]
  (let [root (checkpoint-root store session-id version)
        metadata (.resolve root "checkpoint.edn")]
    (when-not (fs/regular-file? metadata)
      (throw (ex-info "Checkpoint not found"
                      {:code :checkpoint/not-found :version version})))
    {:root root
     :metadata (fs/read-edn metadata)
     :manifest (fs/read-edn (.resolve root "manifest.edn"))
     :source (.resolve root "source")
     :migrations (.resolve root "migrations")
     :archive (.resolve root "app.sqlite.gz")}))

(defn- copy-checkpoint-migrations!
  [store session-id {:keys [migrations manifest]} ^Path destination]
  (if (fs/exists? migrations)
    (fs/copy-tree! migrations destination)
    ;; Compatibility for checkpoints created before migration snapshots were
    ;; added. Every referenced file must still be available in current.
    (do
      (fs/ensure-dir! destination)
      (doseq [file-name (:migrations manifest)]
        (let [source (.resolve (current-migrations-root store session-id) file-name)]
          (when-not (fs/regular-file? source)
            (throw (ex-info "Checkpoint migration snapshot is missing"
                            {:code :checkpoint/migration-missing
                             :migration file-name})))
          (Files/copy source (.resolve destination file-name)
                      (into-array java.nio.file.CopyOption
                                  [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))))
  destination)

(defn- validate-checkpoint-database!
  [{:keys [metadata manifest migrations]} database]
  (let [database-ds (sqlite/datasource database)]
    (sqlite/assert-integrity! database-ds)
    (when-not (= (:runtime-version manifest) (sqlite/runtime-version database-ds))
      (throw (ex-info "Checkpoint source and database versions differ"
                      {:code :checkpoint/version-mismatch})))
    (when-not (= (:database-content-hash metadata)
                 (sqlite/logical-hash database-ds))
      (throw (ex-info "Checkpoint database content hash does not match"
                      {:code :checkpoint/database-hash-mismatch})))
    (validate-migration-snapshot! migrations manifest database)
    true))

(defn- validate-checkpoint-layout!
  [{:keys [root metadata manifest source archive] :as checkpoint} expected-version]
  (fs/assert-no-symlinks! root)
  (when-not (and (map? metadata)
                 (= expected-version (:runtime-version metadata))
                 (string? (:title metadata))
                 (string? (:database-content-hash metadata))
                 (protocol/valid-manifest? manifest)
                 (= expected-version (:runtime-version manifest))
                 (fs/regular-file? archive))
    (throw (ex-info "Checkpoint metadata is invalid"
                    {:code :checkpoint/schema-invalid
                     :version expected-version})))
  (validate-manifest-source! source manifest)
  (when-let [expected (:manifest-hash metadata)]
    (when-not (= expected (fs/sha256-file (.resolve root "manifest.edn")))
      (throw (ex-info "Checkpoint manifest hash does not match"
                      {:code :checkpoint/manifest-hash-mismatch}))))
  (when-let [expected (:database-archive-hash metadata)]
    (when-not (= expected (fs/sha256-file archive))
      (throw (ex-info "Checkpoint archive hash does not match"
                      {:code :checkpoint/archive-hash-mismatch}))))
  checkpoint)

(defn validate-checkpoint!
  [store session-id version]
  (let [version (parse-runtime-version version)
        checkpoint (validate-checkpoint-layout!
                    (read-checkpoint store session-id version) version)
        temp-root (.resolve (.resolve (session-root store session-id) ".staging")
                            (str ".checkpoint-validation-" (random-uuid)))
        database (.resolve temp-root "app.sqlite")]
    (try
      (fs/ensure-dir! temp-root)
      (fs/gunzip-file! (:archive checkpoint) database)
      (let [migrations (.resolve temp-root "migrations")]
        (copy-checkpoint-migrations! store session-id checkpoint migrations)
        (validate-checkpoint-database! (assoc checkpoint :migrations migrations) database))
      checkpoint
      (catch Exception cause
        (if (= :checkpoint/not-found (:code (ex-data cause)))
          (throw cause)
          (throw (ex-info "Checkpoint validation failed"
                          {:code :checkpoint/corrupt :version version}
                          cause))))
      (finally
        (fs/delete-tree! temp-root)))))

(defn stage-restore!
  [store session-id transaction-id checkpoint-version]
  (let [transaction-id (parse-transaction-id transaction-id)
        checkpoint-version (parse-runtime-version checkpoint-version)
        active-manifest (current-manifest store session-id)
        old-source (current-source-map store session-id)
        target-version (inc (:runtime-version active-manifest))
        stage-root (.resolve (.resolve (session-root store session-id) ".staging")
                             (str transaction-id))
        stage-current (.resolve stage-root "current")
        stage-source (.resolve stage-current "source")
        stage-migrations (.resolve stage-current "migrations")
        database (.resolve stage-current "app.sqlite")]
    (fs/delete-tree! stage-root)
    (try
      (let [{:keys [manifest source archive] :as checkpoint}
            (validate-checkpoint-layout!
             (read-checkpoint store session-id checkpoint-version)
             checkpoint-version)]
        (fs/copy-tree! source stage-source)
        (copy-checkpoint-migrations! store session-id checkpoint stage-migrations)
        (fs/gunzip-file! archive database)
        (validate-checkpoint-database! (assoc checkpoint :migrations stage-migrations)
                                       database)
        (let [target-manifest (source-manifest stage-source target-version
                                               (:migrations manifest)
                                               (:created-at active-manifest))
              new-source (stage-source-map {:source stage-source})
              changed-paths (set (concat (keys old-source) (keys new-source)))]
          (fs/atomic-write-edn! (.resolve stage-current "manifest.edn") target-manifest)
          (assert-quota! store session-id stage-source)
          {:transaction-id transaction-id
           :root stage-root
           :current stage-current
           :source stage-source
           :migrations stage-migrations
           :database database
           :manifest target-manifest
           :assigned-migrations []
           :checkpoint checkpoint
           :restored-from checkpoint-version
           :before (select-keys old-source changed-paths)
           :after (select-keys new-source changed-paths)}))
      (catch Exception cause
        (fs/delete-tree! stage-root)
        (if (contains? #{:checkpoint/not-found :storage/quota-exceeded}
                       (:code (ex-data cause)))
          (throw cause)
          (throw (ex-info "Checkpoint could not be staged"
                          {:code :checkpoint/corrupt
                           :version checkpoint-version}
                          cause)))))))

(defn journal-path
  [store session-id transaction-id]
  (.resolve (.resolve (session-root store session-id) "journal")
            (str (parse-transaction-id transaction-id) ".edn")))

(defn write-journal!
  [store session-id transaction-id journal]
  (fs/atomic-write-edn! (journal-path store session-id transaction-id) journal))

(defn list-journals
  [store session-id]
  (let [root (.resolve (session-root store session-id) "journal")]
    (->> (fs/list-tree root)
         (filter #(and (fs/regular-file? %)
                       (str/ends-with? (str (.getFileName ^Path %)) ".edn")))
         (map (fn [path] {:path path :journal (fs/read-edn path)}))
         vec)))

(defn- path-from-journal
  [value]
  (when (string? value)
    (Paths/get value (make-array String 0))))

(defn- journal-stage-root
  [journal]
  (or (path-from-journal (:stage-root journal))
      (some-> (get-in journal [:before :backup-path]) path-from-journal .getParent)
      (some-> (get-in journal [:after :stage-path]) path-from-journal .getParent)))

(defn- history-has-transaction?
  [store session-id transaction-id]
  (boolean (some #(= transaction-id (:transaction-id %))
                 (list-history store session-id))))

(defn- cleanup-journal-transaction!
  [_store _session-id path journal]
  (when-let [root (journal-stage-root journal)]
    (fs/delete-tree! root))
  (Files/deleteIfExists ^Path path)
  true)

(defn- recovery-event
  [journal]
  (or (get-in journal [:commit :event])
      {:kind :change
       :transaction-id (:transaction-id journal)
       :base-version (:base-version journal)
       :runtime-version (:target-version journal)
       :title (str "Recovered checkpoint " (:target-version journal))
       :assistant "A committed runtime was finalized during startup recovery."
       :validation {:recovery :passed}}))

(defn- recover-target!
  [store session-id path journal]
  (let [{:keys [transaction-id target-version commit]} journal
        event (assoc (recovery-event journal)
                     :transaction-id transaction-id
                     :runtime-version target-version)
        checkpoint-options (merge {:title (:title event)
                                   :kind (:kind event)}
                                  (:checkpoint commit))
        session-updates
        (cond-> {:current-version target-version}
          (= :reset (:thread-action commit)) (assoc :codex-thread-id nil)
          (= :set (:thread-action commit)) (assoc :codex-thread-id
                                                  (:codex-thread-id commit)))]
    (current-manifest store session-id)
    (sqlite/assert-integrity! (sqlite/datasource (current-db-path store session-id)))
    (update-session! store session-id session-updates)
    (create-checkpoint! store session-id checkpoint-options)
    (when-not (history-has-transaction? store session-id transaction-id)
      (append-history-unlocked! store session-id event))
    (cleanup-journal-transaction! store session-id path journal)
    {:transaction-id transaction-id :outcome :finalized :runtime-version target-version}))

(defn- recover-base!
  [store session-id path journal]
  (let [{:keys [transaction-id base-version target-version]} journal]
    (current-manifest store session-id)
    (sqlite/assert-integrity! (sqlite/datasource (current-db-path store session-id)))
    (update-session! store session-id {:current-version base-version})
    (fs/delete-tree! (checkpoint-root store session-id target-version))
    (cleanup-journal-transaction! store session-id path journal)
    {:transaction-id transaction-id :outcome :abandoned :runtime-version base-version}))

(defn- recover-mixed!
  [store session-id path journal]
  (let [{:keys [transaction-id base-version target-version]} journal
        backup (path-from-journal (get-in journal [:before :backup-path]))]
    (when-not (and backup (fs/exists? backup))
      (throw (ex-info "Crash recovery backup is missing"
                      {:code :recovery/backup-missing
                       :transaction-id transaction-id})))
    (fs/move-replacing! backup (current-root store session-id))
    (let [manifest (current-manifest store session-id)
          database (current-db-path store session-id)]
      (sqlite/assert-integrity! (sqlite/datasource database))
      (when-not (and (= base-version (:runtime-version manifest))
                     (= base-version
                        (sqlite/runtime-version (sqlite/datasource database)))
                     (= (get-in journal [:before :manifest-hash])
                        (fs/sha256-file (manifest-path store session-id)))
                     (= (get-in journal [:before :database-hash])
                        (sqlite/logical-hash (sqlite/datasource database))))
        (throw (ex-info "Crash recovery backup failed verification"
                        {:code :recovery/backup-invalid
                         :transaction-id transaction-id}))))
    (update-session! store session-id {:current-version base-version})
    (fs/delete-tree! (checkpoint-root store session-id target-version))
    (cleanup-journal-transaction! store session-id path journal)
    {:transaction-id transaction-id :outcome :rolled-back :runtime-version base-version}))

(defn recovery-decision
  [base-version target-version manifest-version database-version]
  (cond
    (and (= target-version manifest-version)
         (= target-version database-version)) :finalize
    (and (= base-version manifest-version)
         (= base-version database-version)) :abandon
    :else :rollback))

(defn recover-session!
  [store session-id]
  (with-session-lock
    store session-id
    (fn []
      (let [results
            (mapv
             (fn [{:keys [path journal]}]
               (let [{:keys [transaction-id base-version target-version]} journal]
                 (when-not (and (uuid? transaction-id)
                                (nat-int? base-version)
                                (= target-version (inc base-version)))
                   (throw (ex-info "Crash journal is invalid"
                                   {:code :recovery/journal-invalid
                                    :path (str path)})))
                 (let [manifest-version
                       (try
                         (:runtime-version (current-manifest store session-id))
                         (catch Exception _ nil))
                       database-version
                       (try
                         (sqlite/runtime-version
                          (sqlite/datasource (current-db-path store session-id)))
                         (catch Exception _ nil))]
                   (case (recovery-decision base-version target-version
                                            manifest-version database-version)
                     :finalize
                     (recover-target! store session-id path journal)

                     :abandon
                     (recover-base! store session-id path journal)

                     :rollback
                     (recover-mixed! store session-id path journal)))))
             (sort-by #(get-in % [:journal :created-at])
                      (list-journals store session-id)))
            manifest (current-manifest store session-id)
            database-version
            (sqlite/runtime-version (sqlite/datasource (current-db-path store session-id)))]
        (when-not (= (:runtime-version manifest) database-version)
          (throw (ex-info "Recovered source and database versions do not agree"
                          {:code :recovery/version-mismatch
                           :manifest-version (:runtime-version manifest)
                           :database-version database-version})))
        (when-not (= (:runtime-version manifest)
                     (:current-version (get-session store session-id)))
          (update-session! store session-id
                           {:current-version (:runtime-version manifest)}))
        results))))

(defn recover-all!
  [store]
  (into {}
        (for [{:keys [id]} (list-sessions store)]
          [id (recover-session! store id)])))

(defn session-size
  [store session-id]
  (fs/directory-size (session-root store session-id)))
