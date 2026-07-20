(ns ppp.runtime.resources
  (:require [cognitect.transit :as transit]
            [clojure.string :as str]
            [ppp.runtime.sqlite :as sqlite]
            [ppp.util.fs :as fs])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.math BigInteger)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.time Instant)
           (java.util Arrays Base64 UUID)))

(def ^:private default-blob-limit (* 4 1024 1024))
(def ^:private default-blob-count-limit 64)
(def ^:private default-value-limit (* 64 1024))
(def ^:private default-event-limit (* 64 1024))
(def ^:private default-search-document-limit 10000)
(def ^:private default-search-candidate-limit 1000)
(def ^:private default-job-limit 1000)
(def ^:private maximum-job-delay-ms (* 30 24 60 60 1000))
(def ^:private maximum-job-attempts 5)
(def ^:private default-job-lease-ms 10000)

(defrecord Service [config now-fn random-uuid-fn])

(defn create-service
  ([config] (create-service config {}))
  ([config {:keys [now-fn random-uuid-fn]}]
   (->Service config
              (or now-fn #(Instant/now))
              (or random-uuid-fn random-uuid))))

(defn- now
  [^Service service]
  ((:now-fn service)))

(defn- now-ms
  [service]
  (.toEpochMilli ^Instant (now service)))

(defn- limit
  [service key default]
  (long (get (:config service) key default)))

(defn ensure-schema!
  [_service connectable]
  (doseq [statement
          ["CREATE TABLE IF NOT EXISTS _ppp_blobs (
               id TEXT PRIMARY KEY,
               name TEXT NOT NULL,
               content_type TEXT NOT NULL,
               size_bytes INTEGER NOT NULL,
               sha256 TEXT NOT NULL,
               content BLOB NOT NULL,
               created_by TEXT,
               created_at TEXT NOT NULL,
               updated_at TEXT NOT NULL
             )"
           "CREATE TABLE IF NOT EXISTS _ppp_jobs (
               id TEXT PRIMARY KEY,
               handler TEXT NOT NULL,
               payload BLOB NOT NULL,
               status TEXT NOT NULL,
               due_at INTEGER NOT NULL,
               attempt INTEGER NOT NULL,
               max_attempts INTEGER NOT NULL,
               idempotency_key TEXT,
               lease_until INTEGER,
               result BLOB,
               last_error_code TEXT,
               created_at TEXT NOT NULL,
               updated_at TEXT NOT NULL
             )"
           "CREATE UNIQUE INDEX IF NOT EXISTS _ppp_jobs_idempotency_idx
              ON _ppp_jobs(handler, idempotency_key)
            WHERE idempotency_key IS NOT NULL"
           "CREATE INDEX IF NOT EXISTS _ppp_jobs_due_idx
              ON _ppp_jobs(status, due_at, lease_until)"
           "CREATE TABLE IF NOT EXISTS _ppp_search_documents (
               row_id INTEGER PRIMARY KEY AUTOINCREMENT,
               collection TEXT NOT NULL,
               document_id TEXT NOT NULL,
               text TEXT NOT NULL,
               metadata BLOB NOT NULL,
               vector BLOB,
               updated_at TEXT NOT NULL,
               UNIQUE(collection, document_id)
             )"
           "CREATE VIRTUAL TABLE IF NOT EXISTS _ppp_search_fts USING fts5(
               collection UNINDEXED,
               document_id UNINDEXED,
               text,
               tokenize = 'unicode61 remove_diacritics 2'
             )"]]
    (sqlite/execute! connectable [statement]))
  connectable)

(defn- invalid!
  [code message & [data]]
  (throw (ex-info message (merge {:code code} data))))

(defn- bounded-name!
  [value kind maximum]
  (let [value (cond
                (keyword? value) (if-let [namespace (namespace value)]
                                   (str namespace "/" (name value))
                                   (name value))
                (string? value) value
                :else nil)]
    (when-not (and value
                   (<= 1 (count value) maximum)
                   (re-matches #"[A-Za-z0-9][A-Za-z0-9_/-]*" value)
                   (not (str/includes? value "//"))
                   (not (str/includes? value ".."))
                   (not (str/starts-with? value "/"))
                   (not (str/ends-with? value "/")))
      (invalid! (keyword (str (name kind) "/id-invalid"))
                (str "The " (name kind) " identifier is invalid.")))
    value))

(defn- blob-id!
  [value]
  ;; Blob identifiers are deliberately flatter than action/job names. A value
  ;; that resembles a relative or absolute path is not accepted.
  (let [value (cond
                (keyword? value) (name value)
                (string? value) value
                :else nil)]
    (when-not (and value
                   (<= 1 (count value) 96)
                   (re-matches #"[A-Za-z0-9][A-Za-z0-9_-]*" value))
      (invalid! :blob/id-invalid "The object identifier is invalid."))
    value))

(defn- control-character?
  [value]
  (boolean (some #(Character/isISOControl (int %)) value)))

(defn- display-name!
  [value]
  (when-not (and (string? value)
                 (<= 1 (count value) 255)
                 (not (control-character? value)))
    (invalid! :blob/name-invalid "The object name is invalid."))
  value)

(defn- content-type!
  [value]
  (let [value (some-> value str/lower-case)]
    (when-not (and value
                   (<= 3 (count value) 128)
                   (re-matches #"[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*"
                               value))
      (invalid! :blob/content-type-invalid "The object type is invalid."))
    value))

(defn- encode-value!
  [value maximum code]
  (try
    (let [output (ByteArrayOutputStream.)
          writer (transit/writer output :json)]
      (transit/write writer value)
      (let [bytes (.toByteArray output)]
        (when (> (alength bytes) maximum)
          (Arrays/fill bytes (byte 0))
          (invalid! code "The value is larger than the supported limit."
                    {:limit maximum}))
        bytes))
    (catch clojure.lang.ExceptionInfo cause
      (throw cause))
    (catch Exception cause
      (throw (ex-info "The value cannot be stored by this product resource."
                      {:code code}
                      cause)))))

(defn- decode-value
  [value]
  (when value
    (let [input (ByteArrayInputStream. ^bytes value)
          reader (transit/reader input :json)]
      (transit/read reader))))

(defn- sha256
  [^bytes value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest digest value)))))

(defn- decode-content!
  [service content]
  (when-not (string? content)
    (invalid! :blob/content-invalid "Object content must be base64 data."))
  (let [maximum (limit service :blob-object-limit default-blob-limit)
        maximum-characters (+ 4 (* 4 (quot (+ maximum 2) 3)))]
    (when (> (count content) maximum-characters)
      (invalid! :blob/too-large "The object is larger than the supported limit."
                {:limit maximum}))
    (try
      (let [bytes (.decode (Base64/getDecoder) ^String content)]
        (when (> (alength bytes) maximum)
          (Arrays/fill bytes (byte 0))
          (invalid! :blob/too-large "The object is larger than the supported limit."
                    {:limit maximum}))
        bytes)
      (catch IllegalArgumentException cause
        (throw (ex-info "Object content is not valid base64 data."
                        {:code :blob/content-invalid}
                        cause))))))

(defn- blob-metadata
  [row]
  (when row
    {:id (:id row)
     :name (:name row)
     :content-type (:content_type row)
     :size (:size_bytes row)
     :sha256 (:sha256 row)
     :created-by (:created_by row)
     :created-at (:created_at row)
     :updated-at (:updated_at row)}))

(defn blob-put!
  [^Service service connectable current-user
   {:keys [id name content-type content-base64]}]
  (ensure-schema! service connectable)
  (let [id (blob-id! (or id (str ((:random-uuid-fn service)))))
        name (display-name! name)
        content-type (content-type! content-type)
        bytes (decode-content! service content-base64)
        existing (sqlite/execute-one! connectable
                                      ["SELECT id, created_at FROM _ppp_blobs WHERE id = ?"
                                       id])
        count-row (when-not existing
                    (sqlite/execute-one! connectable
                                         ["SELECT COUNT(*) AS total FROM _ppp_blobs"]))
        maximum-count (limit service :blob-count-limit default-blob-count-limit)
        timestamp (str (now service))]
    (try
      (when (and count-row (>= (long (:total count-row)) maximum-count))
        (invalid! :blob/count-limit "This product has reached its object limit."
                  {:limit maximum-count}))
      (let [digest (sha256 bytes)
            created-at (or (:created_at existing) timestamp)
            created-by (some-> current-user :id str)]
        (sqlite/execute!
         connectable
         ["INSERT INTO _ppp_blobs
              (id, name, content_type, size_bytes, sha256, content,
               created_by, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              name = excluded.name,
              content_type = excluded.content_type,
              size_bytes = excluded.size_bytes,
              sha256 = excluded.sha256,
              content = excluded.content,
              created_by = excluded.created_by,
              updated_at = excluded.updated_at"
          id name content-type (alength bytes) digest bytes created-by created-at timestamp])
        (blob-metadata
         (sqlite/execute-one!
          connectable
          ["SELECT id, name, content_type, size_bytes, sha256, created_by,
                   created_at, updated_at
              FROM _ppp_blobs WHERE id = ?"
           id])))
      (finally
        (Arrays/fill bytes (byte 0))))))

(defn blob-get
  [^Service service connectable id]
  (ensure-schema! service connectable)
  (let [id (blob-id! id)
        row (sqlite/execute-one!
             connectable
             ["SELECT id, name, content_type, size_bytes, sha256, content,
                      created_by, created_at, updated_at
                 FROM _ppp_blobs WHERE id = ?"
              id])]
    (when-not row
      (invalid! :blob/not-found "That object does not exist."))
    (assoc (blob-metadata row)
           :content-base64 (.encodeToString (Base64/getEncoder)
                                            ^bytes (:content row)))))

(defn blob-list
  [^Service service connectable]
  (ensure-schema! service connectable)
  (mapv blob-metadata
        (sqlite/execute!
         connectable
         ["SELECT id, name, content_type, size_bytes, sha256, created_by,
                  created_at, updated_at
             FROM _ppp_blobs ORDER BY id"])))

(defn blob-delete!
  [^Service service connectable id]
  (ensure-schema! service connectable)
  (let [id (blob-id! id)
        result (sqlite/execute-one! connectable
                                    ["DELETE FROM _ppp_blobs WHERE id = ?" id])]
    {:id id :deleted? (pos? (long (or (:next.jdbc/update-count result) 0)))}))

(defn event-effect
  [^Service service topic payload]
  (let [topic (bounded-name! topic :event 96)
        bytes (encode-value! payload
                             (limit service :product-event-limit default-event-limit)
                             :event/payload-invalid)]
    (Arrays/fill bytes (byte 0))
    {:op :product-event :topic (keyword topic) :payload payload}))

(defn- handler-name!
  [value]
  (bounded-name! value :job 96))

(defn- job-id!
  [value]
  (try
    (str (UUID/fromString (str value)))
    (catch Exception cause
      (throw (ex-info "The job identifier is invalid."
                      {:code :job/id-invalid}
                      cause)))))

(defn- public-job
  [row]
  (when row
    (cond-> {:id (:id row)
             :handler (keyword (:handler row))
             :status (keyword (:status row))
             :due-at (:due_at row)
             :attempt (:attempt row)
             :max-attempts (:max_attempts row)
             :created-at (:created_at row)
             :updated-at (:updated_at row)}
      (:last_error_code row) (assoc :last-error-code
                                    (keyword (:last_error_code row)))
      (:result row) (assoc :result (decode-value (:result row))))))

(defn job-status
  [^Service service connectable id]
  (ensure-schema! service connectable)
  (let [id (job-id! id)
        row (sqlite/execute-one!
             connectable
             ["SELECT id, handler, status, due_at, attempt, max_attempts,
                      result, last_error_code, created_at, updated_at
                 FROM _ppp_jobs WHERE id = ?"
              id])]
    (when-not row
      (invalid! :job/not-found "That background task does not exist."))
    (public-job row)))

(defn schedule-job!
  [^Service service connectable handler payload options]
  (ensure-schema! service connectable)
  (when-not (or (nil? options) (map? options))
    (invalid! :job/options-invalid "The background task options are invalid."))
  (let [options (or options {})
        _ (when-not (every? #{:delay-ms :max-attempts :idempotency-key}
                            (keys options))
            (invalid! :job/options-invalid "The background task options are invalid."))
        handler (handler-name! handler)
        delay-ms (long (get options :delay-ms 0))
        attempts (long (get options :max-attempts 3))
        idempotency-key (:idempotency-key options)
        _ (when-not (<= 0 delay-ms maximum-job-delay-ms)
            (invalid! :job/delay-invalid "The background task delay is invalid."))
        _ (when-not (<= 1 attempts maximum-job-attempts)
            (invalid! :job/attempts-invalid "The background task retry limit is invalid."))
        _ (when-not (or (nil? idempotency-key)
                        (and (string? idempotency-key)
                             (<= 1 (count idempotency-key) 128)
                             (not (control-character? idempotency-key))))
            (invalid! :job/idempotency-invalid
                      "The background task idempotency key is invalid."))
        existing (when idempotency-key
                   (sqlite/execute-one!
                    connectable
                    ["SELECT id, handler, status, due_at, attempt, max_attempts,
                             result, last_error_code, created_at, updated_at
                        FROM _ppp_jobs
                       WHERE handler = ? AND idempotency_key = ?"
                     handler idempotency-key]))]
    (if existing
      (public-job existing)
      (let [count-row
            (sqlite/execute-one!
             connectable
             ["SELECT COUNT(*) AS total FROM _ppp_jobs
                WHERE status IN ('pending', 'running')"])
            maximum (limit service :job-count-limit default-job-limit)]
        (when (>= (long (:total count-row)) maximum)
          (invalid! :job/count-limit "This product has too many pending tasks."
                    {:limit maximum}))
        (let [payload-bytes
              (encode-value! payload
                             (limit service :resource-value-limit default-value-limit)
                             :job/payload-invalid)
              id (str ((:random-uuid-fn service)))
              due-at (+ (now-ms service) delay-ms)
              timestamp (str (now service))]
          (sqlite/execute!
           connectable
           ["INSERT INTO _ppp_jobs
                (id, handler, payload, status, due_at, attempt, max_attempts,
                 idempotency_key, lease_until, result, last_error_code,
                 created_at, updated_at)
              VALUES (?, ?, ?, 'pending', ?, 0, ?, ?, NULL, NULL, NULL, ?, ?)"
            id handler payload-bytes due-at attempts idempotency-key timestamp timestamp])
          (job-status service connectable id))))))

(defn cancel-job!
  [^Service service connectable id]
  (ensure-schema! service connectable)
  (let [id (job-id! id)
        timestamp (str (now service))]
    (sqlite/execute!
     connectable
     ["UPDATE _ppp_jobs
         SET status = 'cancelled', lease_until = NULL,
             last_error_code = 'job/cancelled', updated_at = ?
       WHERE id = ? AND status = 'pending'"
      timestamp id])
    (job-status service connectable id)))

(defn claim-next-job!
  [^Service service connectable registered-handlers]
  (ensure-schema! service connectable)
  (let [current (now-ms service)
        handler-names (when (some? registered-handlers)
                        (set (map handler-name! registered-handlers)))
        candidates
        (sqlite/execute!
         connectable
         ["SELECT id, handler, payload, status, due_at, attempt, max_attempts,
                  lease_until
             FROM _ppp_jobs
            WHERE (status = 'pending' AND due_at <= ?)
               OR (status = 'running' AND lease_until IS NOT NULL AND lease_until <= ?)
            ORDER BY due_at, id
            LIMIT 128"
          current current])
        row (first (if handler-names
                     (filter #(contains? handler-names (:handler %)) candidates)
                     candidates))]
    (when row
      (let [attempt (long (:attempt row))
            maximum-attempts (long (:max_attempts row))
            timestamp (str (now service))]
        (if (and (= "running" (:status row))
                 (>= attempt maximum-attempts))
          (do
            (sqlite/execute!
             connectable
             ["UPDATE _ppp_jobs
                 SET status = 'failed', lease_until = NULL,
                     last_error_code = 'job/lease-expired', updated_at = ?
               WHERE id = ? AND status = 'running' AND attempt = ?"
              timestamp (:id row) attempt])
            nil)
          (let [next-attempt (inc attempt)
                lease-until (+ current
                               (limit service :job-lease-ms default-job-lease-ms))]
            (sqlite/execute!
             connectable
             ["UPDATE _ppp_jobs
                 SET status = 'running', attempt = ?, lease_until = ?, updated_at = ?
               WHERE id = ?"
              next-attempt lease-until timestamp (:id row)])
            {:id (:id row)
             :handler (keyword (:handler row))
             :payload (decode-value (:payload row))
             :attempt next-attempt
             :max-attempts maximum-attempts}))))))

(defn complete-job!
  [^Service service connectable id result]
  (let [id (job-id! id)
        result-bytes (encode-value! result
                                    (limit service :resource-value-limit
                                           default-value-limit)
                                    :job/result-invalid)
        timestamp (str (now service))]
    (sqlite/execute!
     connectable
     ["UPDATE _ppp_jobs
         SET status = 'completed', lease_until = NULL, result = ?,
             last_error_code = NULL, updated_at = ?
       WHERE id = ? AND status = 'running'"
      result-bytes timestamp id])
    (job-status service connectable id)))

(defn fail-job!
  [^Service service connectable id error-code]
  (let [id (job-id! id)
        row (sqlite/execute-one!
             connectable
             ["SELECT attempt, max_attempts FROM _ppp_jobs WHERE id = ?" id])]
    (when-not row
      (invalid! :job/not-found "That background task does not exist."))
    (let [attempt (long (:attempt row))
          retry? (< attempt (long (:max_attempts row)))
          backoff (min 300000 (* 1000 (long (Math/pow 2 (max 0 (dec attempt))))))
          timestamp (str (now service))]
      (sqlite/execute!
       connectable
       ["UPDATE _ppp_jobs
           SET status = ?, due_at = ?, lease_until = NULL,
               last_error_code = ?, updated_at = ?
         WHERE id = ?"
        (if retry? "pending" "failed")
        (if retry? (+ (now-ms service) backoff) (now-ms service))
        (subs (str error-code) (if (str/starts-with? (str error-code) ":") 1 0))
        timestamp id])
      (job-status service connectable id))))

(defn cancel-operational-jobs!
  [^Service service connectable]
  (ensure-schema! service connectable)
  (sqlite/execute!
   connectable
   ["UPDATE _ppp_jobs
       SET status = 'cancelled', lease_until = NULL,
           last_error_code = 'job/restored', updated_at = ?
     WHERE status IN ('pending', 'running')"
    (str (now service))])
  nil)

(defn- collection!
  [value]
  (bounded-name! value :search 64))

(defn- document-id!
  [value]
  (bounded-name! value :search 128))

(defn- vector!
  [value]
  (when (some? value)
    (when-not (and (sequential? value) (<= 2 (count value) 1536))
      (invalid! :search/vector-invalid "The search vector is outside the supported bounds."))
    (mapv (fn [item]
            (let [number (when (number? item) (double item))]
              (when-not (and number
                             (Double/isFinite number)
                             (<= -1000000.0 number 1000000.0))
                (invalid! :search/vector-invalid
                          "The search vector is outside the supported bounds."))
              number))
          value)))

(defn search-upsert!
  [^Service service connectable collection document-id
   {:keys [text metadata vector]}]
  (ensure-schema! service connectable)
  (let [collection (collection! collection)
        document-id (document-id! document-id)
        _ (when-not (and (string? text)
                         (<= 1 (alength (.getBytes ^String text StandardCharsets/UTF_8))
                             (* 48 1024)))
            (invalid! :search/text-invalid
                      "The search document text is outside the supported bounds."))
        metadata-bytes (encode-value! (or metadata {}) (* 16 1024)
                                      :search/metadata-invalid)
        vector (vector! vector)
        vector-bytes (when vector
                       (encode-value! vector (* 16 1024) :search/vector-invalid))
        existing (sqlite/execute-one!
                  connectable
                  ["SELECT row_id FROM _ppp_search_documents
                     WHERE collection = ? AND document_id = ?"
                   collection document-id])
        count-row (when-not existing
                    (sqlite/execute-one! connectable
                                         ["SELECT COUNT(*) AS total
                                             FROM _ppp_search_documents"]))
        maximum (limit service :search-document-limit
                       default-search-document-limit)
        timestamp (str (now service))]
    (when (and count-row (>= (long (:total count-row)) maximum))
      (invalid! :search/document-limit "This product has reached its search limit."
                {:limit maximum}))
    (sqlite/execute!
     connectable
     ["INSERT INTO _ppp_search_documents
          (collection, document_id, text, metadata, vector, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(collection, document_id) DO UPDATE SET
          text = excluded.text,
          metadata = excluded.metadata,
          vector = excluded.vector,
          updated_at = excluded.updated_at"
      collection document-id text metadata-bytes vector-bytes timestamp])
    (sqlite/execute! connectable
                     ["DELETE FROM _ppp_search_fts
                        WHERE collection = ? AND document_id = ?"
                      collection document-id])
    (sqlite/execute! connectable
                     ["INSERT INTO _ppp_search_fts(collection, document_id, text)
                       VALUES (?, ?, ?)"
                      collection document-id text])
    {:collection (keyword collection)
     :id document-id
     :text text
     :metadata (or metadata {})
     :vector-dim (some-> vector count)
     :updated-at timestamp}))

(defn search-delete!
  [^Service service connectable collection document-id]
  (ensure-schema! service connectable)
  (let [collection (collection! collection)
        document-id (document-id! document-id)]
    (sqlite/execute! connectable
                     ["DELETE FROM _ppp_search_fts
                        WHERE collection = ? AND document_id = ?"
                      collection document-id])
    (let [result (sqlite/execute-one!
                  connectable
                  ["DELETE FROM _ppp_search_documents
                     WHERE collection = ? AND document_id = ?"
                   collection document-id])]
      {:collection (keyword collection)
       :id document-id
       :deleted? (pos? (long (or (:next.jdbc/update-count result) 0)))})))

(defn- query-terms!
  [query]
  (when-not (string? query)
    (invalid! :search/query-invalid "The search query is invalid."))
  (let [terms (vec (take 33 (re-seq #"[\p{L}\p{N}_]+" query)))]
    (when (> (count terms) 32)
      (invalid! :search/query-invalid "The search query has too many terms."))
    (str/join " " (map #(str "\"" % "\"") terms))))

(defn- cosine
  [left right]
  (when (= (count left) (count right))
    (let [dot (reduce + (map * left right))
          left-length (Math/sqrt (reduce + (map #(* % %) left)))
          right-length (Math/sqrt (reduce + (map #(* % %) right)))]
      (if (or (zero? left-length) (zero? right-length))
        0.0
        (/ dot (* left-length right-length))))))

(defn- search-row
  [row]
  {:collection (keyword (:collection row))
   :id (:document_id row)
   :text (:text row)
   :metadata (decode-value (:metadata row))})

(defn search-query
  [^Service service connectable collection query options]
  (ensure-schema! service connectable)
  (when-not (or (nil? options) (map? options))
    (invalid! :search/options-invalid "The search options are invalid."))
  (let [options (or options {})
        _ (when-not (every? #{:limit :vector :text-weight :vector-weight}
                            (keys options))
            (invalid! :search/options-invalid "The search options are invalid."))
        collection (collection! collection)
        terms (query-terms! (or query ""))
        vector (vector! (:vector options))
        result-limit (long (get options :limit 20))
        _ (when-not (<= 1 result-limit 100)
            (invalid! :search/limit-invalid "The search result limit is invalid."))
        text-weight (double (get options :text-weight (if vector 0.5 1.0)))
        vector-weight (double (get options :vector-weight (if (str/blank? terms) 1.0 0.5)))
        _ (when-not (and (Double/isFinite text-weight)
                         (Double/isFinite vector-weight)
                         (<= 0.0 text-weight 1.0)
                         (<= 0.0 vector-weight 1.0)
                         (pos? (+ text-weight vector-weight)))
            (invalid! :search/weight-invalid "The search weights are invalid."))
        text-rows
        (if (str/blank? terms)
          []
          (sqlite/execute!
           connectable
           ["SELECT d.collection, d.document_id, d.text, d.metadata,
                    bm25(_ppp_search_fts) AS text_rank
               FROM _ppp_search_fts
               JOIN _ppp_search_documents d
                 ON d.collection = _ppp_search_fts.collection
                AND d.document_id = _ppp_search_fts.document_id
              WHERE _ppp_search_fts MATCH ?
                AND _ppp_search_fts.collection = ?
              ORDER BY text_rank, d.document_id
              LIMIT 200"
            terms collection]))
        vector-rows
        (if vector
          (sqlite/execute!
           connectable
           ["SELECT collection, document_id, text, metadata, vector
               FROM _ppp_search_documents
              WHERE collection = ? AND vector IS NOT NULL
              ORDER BY document_id
              LIMIT ?"
            collection
            (limit service :search-candidate-limit
                   default-search-candidate-limit)])
          [])
        text-candidates
        (into {}
              (map (fn [row]
                     [(:document_id row)
                      (assoc (search-row row)
                             :text-score (/ 1.0
                                            (+ 1.0
                                               (Math/abs
                                                (double (:text_rank row))))))]))
              text-rows)
        vector-candidates
        (into {}
              (keep (fn [row]
                      (let [candidate (decode-value (:vector row))
                            score (cosine vector candidate)]
                        (when (some? score)
                          [(:document_id row)
                           (assoc (search-row row)
                                  :vector-score (/ (+ 1.0 score) 2.0))]))))
              vector-rows)
        ids (set (concat (keys text-candidates) (keys vector-candidates)))
        denominator (+ text-weight vector-weight)]
    (->> ids
         (map (fn [id]
                (let [text-candidate (get text-candidates id)
                      vector-candidate (get vector-candidates id)
                      candidate (or text-candidate vector-candidate)]
                  (assoc candidate
                         :score (/ (+ (* text-weight
                                         (double (or (:text-score text-candidate) 0.0)))
                                      (* vector-weight
                                         (double (or (:vector-score vector-candidate) 0.0))))
                                   denominator)))))
         (sort-by (juxt (comp - :score) :id))
         (take result-limit)
         vec)))

(defn resource-state
  [^Service service connectable]
  (ensure-schema! service connectable)
  {:blobs
   (mapv #(select-keys (blob-metadata %) [:id :size :sha256])
         (sqlite/execute!
          connectable
          ["SELECT id, name, content_type, size_bytes, sha256, created_by,
                   created_at, updated_at
              FROM _ppp_blobs ORDER BY id"]))
   :jobs
   (mapv #(select-keys (public-job %) [:id :handler :status :attempt :max-attempts])
         (sqlite/execute!
          connectable
          ["SELECT id, handler, status, due_at, attempt, max_attempts,
                   result, last_error_code, created_at, updated_at
              FROM _ppp_jobs ORDER BY id"]))
   :search
   (mapv (fn [row]
           {:collection (:collection row)
            :id (:document_id row)
            :text-hash (fs/sha256-string (:text row))
            :metadata-hash (sha256 (:metadata row))
            :vector-hash (when (:vector row) (sha256 (:vector row)))})
         (sqlite/execute!
          connectable
          ["SELECT collection, document_id, text, metadata, vector
              FROM _ppp_search_documents
             ORDER BY collection, document_id"]))})

(defn resource-hash
  [service connectable]
  (fs/sha256-string (pr-str (resource-state service connectable))))
