(ns ppp.runtime.sqlite
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [ppp.runtime.policy :as policy]
            [ppp.util.fs :as fs])
  (:import (java.nio.file Files Path)
           (java.sql Connection)
           (java.time Instant)
           (org.sqlite ProgressHandler SQLiteConnection)))

(def ^:private builder rs/as-unqualified-lower-maps)

(defn datasource
  [^Path database-path]
  (jdbc/get-datasource {:dbtype "sqlite" :dbname (str database-path)}))

(defn- configure-connection!
  [^Connection connection]
  (jdbc/execute! connection ["PRAGMA foreign_keys = ON"])
  (jdbc/execute! connection ["PRAGMA busy_timeout = 5000"])
  connection)

(defn- with-connection
  [connectable thunk]
  (if (instance? Connection connectable)
    (thunk connectable)
    (with-open [connection (jdbc/get-connection connectable)]
      (configure-connection! connection)
      (thunk connection))))

(defn execute!
  ([connectable sql-params]
   (execute! connectable sql-params {}))
  ([connectable sql-params options]
   (with-connection
     connectable
     #(jdbc/execute! % sql-params (merge {:builder-fn builder} options)))))

(defn execute-one!
  [connectable sql-params]
  (with-connection
    connectable
    #(jdbc/execute-one! % sql-params {:builder-fn builder})))

(defn configure!
  [connectable]
  (if (instance? Connection connectable)
    (configure-connection! connectable)
    (with-connection connectable identity))
  connectable)

(defn init!
  [^Path database-path]
  (fs/ensure-dir! (.getParent database-path))
  (let [ds (datasource database-path)]
    (with-open [connection (jdbc/get-connection ds)]
      (configure-connection! connection)
      (jdbc/execute! connection ["PRAGMA journal_mode = WAL"])
      (jdbc/execute! connection
                     ["CREATE TABLE IF NOT EXISTS _ppp_runtime_meta (
                         singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                         runtime_version INTEGER NOT NULL,
                         updated_at TEXT NOT NULL
                       )"])
      (jdbc/execute! connection
                     ["CREATE TABLE IF NOT EXISTS _ppp_migrations (
                         migration_name TEXT PRIMARY KEY,
                         content_hash TEXT NOT NULL,
                         applied_at TEXT NOT NULL
                       )"])
      (jdbc/execute! connection
                     ["INSERT OR IGNORE INTO _ppp_runtime_meta
                         (singleton, runtime_version, updated_at)
                       VALUES (1, 0, ?)"
                      (str (Instant/now))]))
    ds))

(defn runtime-version
  [connectable]
  (or (:runtime_version
       (execute-one! connectable
                     ["SELECT runtime_version FROM _ppp_runtime_meta WHERE singleton = 1"]))
      0))

(defn set-runtime-version!
  [connectable version]
  (execute! connectable
            ["UPDATE _ppp_runtime_meta
                SET runtime_version = ?, updated_at = ?
              WHERE singleton = 1"
             version (str (Instant/now))])
  version)

(defn checkpoint-wal!
  [connectable]
  (execute! connectable ["PRAGMA wal_checkpoint(TRUNCATE)"])
  nil)

(defn- sidecar-path
  [^Path database-path suffix]
  (.resolveSibling database-path (str (.getFileName database-path) suffix)))

(defn delete-database!
  [^Path database-path]
  (doseq [path [database-path
                (sidecar-path database-path "-wal")
                (sidecar-path database-path "-shm")]]
    (Files/deleteIfExists path))
  nil)

(defn clone-database!
  [^Path source ^Path destination]
  (fs/ensure-dir! (.getParent destination))
  (delete-database! destination)
  (let [source-ds (init! source)]
    (with-open [^Connection connection (jdbc/get-connection source-ds)]
      (configure-connection! connection)
      (let [^SQLiteConnection sqlite-connection
            (if (instance? SQLiteConnection connection)
              connection
              (.unwrap connection SQLiteConnection))
            result (.backup (.getDatabase sqlite-connection)
                            "main" (str destination) nil)]
        (when-not (zero? result)
          (throw (ex-info "SQLite online backup failed"
                          {:code :sqlite/backup-failed :sqlite-code result}))))))
  (init! destination)
  destination)

(defn with-progress-deadline
  [^Connection connection deadline-nanos thunk]
  (ProgressHandler/setHandler
   connection 1000
   (proxy [ProgressHandler] []
     (progress []
       (if (> (System/nanoTime) deadline-nanos) 1 0))))
  (try
    (thunk)
    (finally
      (ProgressHandler/clearHandler connection))))

(defn- existing-migration
  [connectable migration-name]
  (execute-one! connectable
                ["SELECT migration_name, content_hash
                    FROM _ppp_migrations
                   WHERE migration_name = ?"
                 migration-name]))

(defn- migration-name-from-file
  [file-name]
  (some-> (re-matches #"^\d{6}-([a-z0-9][a-z0-9_-]{0,63})\.sql$"
                      (str file-name))
          second))

(defn- validate-migration!
  [{:keys [file-name sql] :as migration}]
  (let [declared-name (:name migration)
        file-name-value (migration-name-from-file file-name)
        name (or declared-name file-name-value)
        error (policy/validate-migration {:name name :sql sql})]
    (when (or (nil? name)
              (nil? file-name-value)
              (and declared-name (not= declared-name file-name-value))
              error)
      (throw (ex-info "Migration is outside the generated SQL capability"
                      {:code :sql/migration-not-allowed
                       :migration file-name
                       :error error})))
    (assoc migration :name name)))

(defn- apply-migration-batch!
  [transaction migrations]
  (doseq [{:keys [file-name sql]} migrations]
    (let [content-hash (fs/sha256-string sql)
          existing (existing-migration transaction file-name)]
      (cond
        (and existing (not= content-hash (:content_hash existing)))
        (throw (ex-info "A committed migration cannot be modified"
                        {:code :sql/committed-migration-modified
                         :migration file-name}))

        existing
        nil

        :else
        (do
          (doseq [statement (policy/split-sql-statements sql)]
            (jdbc/execute! transaction [statement]))
          (jdbc/execute!
           transaction
           ["INSERT INTO _ppp_migrations
               (migration_name, content_hash, applied_at)
            VALUES (?, ?, ?)"
            file-name content-hash (str (Instant/now))])))))
  migrations)

(defn apply-migrations!
  ([connectable migrations]
   (apply-migrations! connectable migrations {:timeout-ms 2000}))
  ([connectable migrations {:keys [timeout-ms] :or {timeout-ms 2000}}]
   (let [migrations (mapv validate-migration! migrations)]
     (with-connection
       connectable
       (fn [^Connection connection]
         (jdbc/with-transaction [transaction connection]
           (with-progress-deadline
             transaction
             (+ (System/nanoTime) (* (long timeout-ms) 1000000))
             #(apply-migration-batch! transaction migrations))))))
   migrations))

(defn stage-database!
  [^Path live-database ^Path stage-database migrations]
  (try
    (clone-database! live-database stage-database)
    (let [stage-ds (datasource stage-database)]
      (apply-migrations! stage-ds migrations)
      stage-database)
    (catch Exception cause
      (delete-database! stage-database)
      (if (instance? clojure.lang.ExceptionInfo cause)
        (throw cause)
        (throw (ex-info "Staged SQLite migration failed"
                        {:code :sql/migration-failed}
                        cause))))))

(def ^:private action-code-forbidden
  [#"(?i)\b(?:attach|detach|pragma|vacuum|load_extension)\b"
   #"(?i)\b(?:create|alter|drop|reindex|analyze)\b"
   #"(?i)\b(?:sqlite_[a-z0-9_]*|readfile|writefile)\b"])

(defn- placeholder-count
  [code]
  (count (re-seq #"\?(?:\d+)?" code)))

(defn validate-action-sql-template!
  "Validate the immutable SQL portion of a generated action. Runtime input is
  validated separately and must be supplied only through placeholders."
  [sql write?]
  (let [sql (str/trim (str sql))
        {:keys [balanced? statements]} (policy/analyze-sql sql)
        code (:code (first statements))
        allowed (if write?
                  #"(?is)^(?:insert\s+into|update|delete\s+from)\b"
                  #"(?is)^select\b")]
    (when (or (not balanced?)
              (not= 1 (count statements))
              (not (re-find allowed (or code "")))
              (some #(re-find % sql) policy/reserved-sql-patterns)
              (some #(re-find % (or code "")) action-code-forbidden))
      (throw (ex-info "SQL is outside the generated action capability"
                      {:code :action/sql-not-allowed})))
    {:sql sql
     :placeholder-count (placeholder-count (or code ""))}))

(defn validate-action-sql!
  [sql params write?]
  (let [{:keys [sql placeholder-count]}
        (validate-action-sql-template! sql write?)]
    (when (or (not (sequential? params))
              (> (count params) 128)
              (not= placeholder-count (count params)))
      (throw (ex-info "SQL parameters are outside the generated action capability"
                      {:code :action/sql-not-allowed})))
    sql))

(defn query!
  [connectable sql params]
  (let [params (vec params)
        sql (validate-action-sql! sql params false)]
    (execute! connectable (into [sql] params))))

(defn mutate!
  [connectable sql params]
  (let [params (vec params)
        sql (validate-action-sql! sql params true)]
    (execute! connectable (into [sql] params))))

(defn- user-table-names*
  [connectable]
  (mapv :name
        (execute! connectable
                  ["SELECT name
                      FROM sqlite_schema
                     WHERE type = 'table'
                       AND name NOT LIKE '_ppp_%'
                       AND name NOT LIKE 'sqlite_%'
                     ORDER BY name"])))

(defn user-table-names
  [connectable]
  (with-connection connectable user-table-names*))

(defn- logical-content*
  [connectable]
  (into (sorted-map)
        (for [table (user-table-names* connectable)]
          (let [quoted (str "\"" (str/replace table "\"" "\"\"") "\"")
                rows (mapv #(into (sorted-map) %)
                           (execute! connectable [(str "SELECT * FROM " quoted)]))]
            [table
             {:schema (:sql (execute-one! connectable
                                          ["SELECT sql FROM sqlite_schema WHERE name = ?"
                                           table]))
              :rows (vec (sort-by pr-str rows))}]))))

(defn logical-content
  [connectable]
  (with-connection
    connectable
    (fn [connection]
      (jdbc/with-transaction [transaction connection]
        (logical-content* transaction)))))

(defn logical-hash
  [connectable]
  (fs/sha256-string (pr-str (logical-content connectable))))

(defn assert-integrity!
  [connectable]
  (let [rows (execute! connectable ["PRAGMA integrity_check"])
        results (mapv #(or (:integrity_check %) (first (vals %))) rows)]
    (when-not (= ["ok"] results)
      (throw (ex-info "SQLite integrity check failed"
                      {:code :sqlite/integrity-failed
                       :results results})))
    true))

(defn migration-records
  [connectable]
  (into (sorted-map)
        (map (juxt :migration_name :content_hash))
        (execute! connectable
                  ["SELECT migration_name, content_hash
                      FROM _ppp_migrations
                  ORDER BY migration_name"])))
