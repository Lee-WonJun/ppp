(ns ppp.runtime.auth
  (:require [clojure.string :as str]
            [ppp.access :as access]
            [ppp.runtime.sqlite :as sqlite])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.sql SQLException)
           (java.text Normalizer Normalizer$Form)
           (java.time Instant)
           (java.util Arrays Base64 Locale UUID)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)
           (org.bouncycastle.crypto.generators Argon2BytesGenerator)
           (org.bouncycastle.crypto.params Argon2Parameters Argon2Parameters$Builder)))

(def ^:private minimum-identifier-codepoints 3)
(def ^:private maximum-identifier-codepoints 254)
(def ^:private maximum-identifier-bytes 512)
(def ^:private minimum-password-codepoints 8)
(def ^:private maximum-password-bytes 1024)
(def ^:private salt-bytes 16)
(def ^:private hash-bytes 32)
(def ^:private token-bytes 32)
(def ^:private default-session-seconds (* 7 24 60 60))
(def ^:private attempt-window-seconds (* 15 60))
(def ^:private attempt-lock-seconds 60)
(def ^:private maximum-attempts 5)
(def ^:private maximum-sessions-per-user 10)

(def ^:private default-hash-options
  {:memory-kib 19456
   :iterations 2
   :parallelism 1
   :version Argon2Parameters/ARGON2_VERSION_13})

(defrecord Service
           [config now-fn random-bytes-fn hash-options dummy-hash])

(defn- auth-error
  [code message]
  (ex-info message {:code code}))

(defn- now
  [^Service service]
  ((:now-fn service)))

(defn- epoch-second
  [^Instant value]
  (.getEpochSecond value))

(defn- random-bytes
  [^Service service size]
  ((:random-bytes-fn service) size))

(defn- utf8
  [value]
  (.getBytes ^String (str value) StandardCharsets/UTF_8))

(defn- base64
  [^bytes value]
  (.encodeToString (.withoutPadding (Base64/getEncoder)) value))

(defn- base64-url
  [^bytes value]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) value))

(defn- decode-base64
  [value]
  (.decode (Base64/getDecoder) ^String value))

(defn- hmac-sha256
  [secret value]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (utf8 secret) "HmacSHA256"))
    (.doFinal mac (utf8 value))))

(defn- contains-control-character?
  [value]
  (boolean (some #(Character/isISOControl (int %)) value)))

(defn normalize-identifier
  "Return the display and lookup forms of a product-owned login identifier.
  The value is deliberately generic: generated products may label it username,
  handle, or email without granting the Kernel authority over profile fields."
  [value]
  (when-not (string? value)
    (throw (auth-error :auth/identifier-invalid
                       "A sign-in identifier is required.")))
  (let [display (-> value
                    (Normalizer/normalize Normalizer$Form/NFKC)
                    str/trim)
        codepoints (.codePointCount display 0 (.length display))
        bytes (alength (utf8 display))]
    (when (or (< codepoints minimum-identifier-codepoints)
              (> codepoints maximum-identifier-codepoints)
              (> bytes maximum-identifier-bytes)
              (contains-control-character? display))
      (throw (auth-error :auth/identifier-invalid
                         "Use a valid sign-in identifier.")))
    {:display display
     :key (.toLowerCase display Locale/ROOT)}))

(defn- password-bytes
  [password]
  (when-not (string? password)
    (throw (auth-error :auth/password-invalid
                       "A password is required.")))
  (let [bytes (utf8 password)
        codepoints (.codePointCount ^String password 0 (.length ^String password))]
    (when (or (< codepoints minimum-password-codepoints)
              (> (alength bytes) maximum-password-bytes))
      (Arrays/fill bytes (byte 0))
      (throw (auth-error :auth/password-invalid
                         "Use a password with at least 8 characters.")))
    bytes))

(defn- derive-argon2id
  [^bytes password ^bytes salt {:keys [memory-kib iterations parallelism version]}
   output-size]
  (let [parameters (-> (Argon2Parameters$Builder. Argon2Parameters/ARGON2_id)
                       (.withVersion (int version))
                       (.withMemoryAsKB (int memory-kib))
                       (.withIterations (int iterations))
                       (.withParallelism (int parallelism))
                       (.withSalt salt)
                       .build)
        generator (doto (Argon2BytesGenerator.) (.init parameters))
        output (byte-array output-size)]
    (.generateBytes generator password output)
    output))

(defn- encode-password
  [^Service service password]
  (let [password (password-bytes password)
        salt (random-bytes service salt-bytes)
        options (:hash-options service)]
    (try
      (let [derived (derive-argon2id password salt options hash-bytes)]
        (try
          (str "$argon2id$v=19$m=" (:memory-kib options)
               ",t=" (:iterations options)
               ",p=" (:parallelism options)
               "$" (base64 salt)
               "$" (base64 derived))
          (finally
            (Arrays/fill derived (byte 0)))))
      (finally
        (Arrays/fill password (byte 0))
        (Arrays/fill salt (byte 0))))))

(def ^:private encoded-password-pattern
  #"^\$argon2id\$v=19\$m=(\d+),t=(\d+),p=(\d+)\$([A-Za-z0-9+/]+)\$([A-Za-z0-9+/]+)$")

(defn- parsed-password
  [encoded]
  (when-let [[_ memory iterations parallelism salt derived]
             (and (string? encoded)
                  (re-matches encoded-password-pattern encoded))]
    (try
      (let [options {:memory-kib (parse-long memory)
                     :iterations (parse-long iterations)
                     :parallelism (parse-long parallelism)
                     :version Argon2Parameters/ARGON2_VERSION_13}
            salt (decode-base64 salt)
            derived (decode-base64 derived)]
        (when (and (<= 7168 (:memory-kib options) 65536)
                   (<= 1 (:iterations options) 10)
                   (<= 1 (:parallelism options) 4)
                   (<= 16 (alength salt) 64)
                   (<= 16 (alength derived) 64))
          {:options options :salt salt :derived derived}))
      (catch Exception _ nil))))

(defn- password-matches?
  [password encoded]
  (let [password (password-bytes password)]
    (try
      (if-let [{:keys [options salt derived]} (parsed-password encoded)]
        (try
          (let [candidate (derive-argon2id password salt options (alength derived))]
            (try
              (MessageDigest/isEqual derived candidate)
              (finally
                (Arrays/fill candidate (byte 0)))))
          (finally
            (Arrays/fill salt (byte 0))
            (Arrays/fill derived (byte 0))))
        false)
      (finally
        (Arrays/fill password (byte 0))))))

(defn create-service
  ([config]
   (create-service config {}))
  ([config {:keys [now-fn random-bytes-fn hash-options
                   allow-weak-test-parameters?]}]
   (let [secret (:cookie-secret config)
         secure-random (SecureRandom.)
         options (merge default-hash-options hash-options)]
     (when (str/blank? secret)
       (throw (ex-info "Product authentication requires the Kernel cookie secret"
                       {:code :kernel/auth-secret-missing})))
     (when (and (not allow-weak-test-parameters?)
                (or (< (:memory-kib options) 19456)
                    (< (:iterations options) 2)
                    (< (:parallelism options) 1)))
       (throw (ex-info "Product authentication hash parameters are too weak"
                       {:code :kernel/auth-parameters-weak})))
     (let [service (->Service
                    config
                    (or now-fn #(Instant/now))
                    (or random-bytes-fn
                        (fn [size]
                          (let [bytes (byte-array size)]
                            (.nextBytes secure-random bytes)
                            bytes)))
                    options
                    nil)]
       (assoc service :dummy-hash
              (delay (encode-password service "not-a-real-user-password")))))))

(defn ensure-schema!
  [_service connectable]
  (doseq [statement
          ["CREATE TABLE IF NOT EXISTS _ppp_auth_users (
               id TEXT PRIMARY KEY,
               identifier_key TEXT NOT NULL UNIQUE,
               identifier_display TEXT NOT NULL,
               password_hash TEXT NOT NULL,
               credential_version INTEGER NOT NULL,
               created_at TEXT NOT NULL,
               updated_at TEXT NOT NULL
             )"
           "CREATE TABLE IF NOT EXISTS _ppp_auth_sessions (
               token_hash TEXT PRIMARY KEY,
               user_id TEXT NOT NULL REFERENCES _ppp_auth_users(id) ON DELETE CASCADE,
               credential_version INTEGER NOT NULL,
               issued_at INTEGER NOT NULL,
               expires_at INTEGER NOT NULL
             )"
           "CREATE INDEX IF NOT EXISTS _ppp_auth_sessions_user_idx
              ON _ppp_auth_sessions(user_id, issued_at DESC)"
           "CREATE TABLE IF NOT EXISTS _ppp_auth_attempts (
               identifier_key TEXT PRIMARY KEY,
               failure_count INTEGER NOT NULL,
               window_started_at INTEGER NOT NULL,
               locked_until INTEGER NOT NULL
             )"]]
    (sqlite/execute! connectable [statement]))
  connectable)

(defn- public-user
  [row]
  (when row
    {:id (:id row)
     :identifier (:identifier_display row)
     :created-at (:created_at row)}))

(defn identity-state
  "Bounded public identity state used by restore and security tests."
  [connectable]
  (mapv public-user
        (sqlite/execute! connectable
                         ["SELECT id, identifier_display, created_at
                             FROM _ppp_auth_users
                         ORDER BY id"])))

(defn clear-operational-state!
  [connectable]
  (sqlite/execute! connectable ["DELETE FROM _ppp_auth_sessions"])
  (sqlite/execute! connectable ["DELETE FROM _ppp_auth_attempts"])
  nil)

(defn cookie-name
  [session-id]
  (str "ppp_product_" (str/replace (str (UUID/fromString (str session-id))) "-" "")))

(defn cookie-path
  [session-id]
  (str "/api/sessions/" (UUID/fromString (str session-id)) "/actions"))

(defn request-token
  [request session-id]
  (get (access/parse-cookies request) (cookie-name session-id)))

(defn- token-digest
  [^Service service session-id token]
  (base64-url
   (hmac-sha256 (get-in service [:config :cookie-secret])
                (str "product-auth:" (UUID/fromString (str session-id)) ":" token))))

(defn cookie-header
  [^Service service session-id {:keys [token expires-at]}]
  (let [remaining (max 0 (- (long expires-at) (epoch-second (now service))))]
    (str (cookie-name session-id) "=" token
         "; Path=" (cookie-path session-id)
         "; Max-Age=" remaining
         "; HttpOnly; SameSite=Strict"
         (when (get-in service [:config :cookie-secure?]) "; Secure"))))

(defn clear-cookie-header
  [^Service service session-id]
  (str (cookie-name session-id) "="
       "; Path=" (cookie-path session-id)
       "; Max-Age=0; HttpOnly; SameSite=Strict"
       (when (get-in service [:config :cookie-secure?]) "; Secure")))

(defn effect-headers
  [^Service service session-id effect]
  (case (:op effect)
    :set {"set-cookie" (cookie-header service session-id effect)}
    :clear {"set-cookie" (clear-cookie-header service session-id)}
    {}))

(defn- issue-session!
  [^Service service connectable session-id user-id credential-version]
  (let [issued-at (epoch-second (now service))
        expires-at (+ issued-at
                      (long (get-in service [:config :product-auth-session-seconds]
                                    default-session-seconds)))
        token-bytes-value (random-bytes service token-bytes)]
    (try
      (let [token (base64-url token-bytes-value)
            digest (token-digest service session-id token)]
        (sqlite/execute! connectable
                         ["DELETE FROM _ppp_auth_sessions WHERE expires_at <= ?"
                          issued-at])
        (sqlite/execute! connectable
                         ["INSERT INTO _ppp_auth_sessions
                              (token_hash, user_id, credential_version, issued_at, expires_at)
                            VALUES (?, ?, ?, ?, ?)"
                          digest user-id credential-version issued-at expires-at])
        (sqlite/execute! connectable
                         ["DELETE FROM _ppp_auth_sessions
                            WHERE user_id = ?
                              AND token_hash NOT IN (
                                SELECT token_hash FROM _ppp_auth_sessions
                                 WHERE user_id = ?
                              ORDER BY issued_at DESC, token_hash DESC
                                 LIMIT ?
                              )"
                          user-id user-id maximum-sessions-per-user])
        {:op :set :token token :expires-at expires-at})
      (finally
        (Arrays/fill token-bytes-value (byte 0))))))

(defn- attempt-row
  [connectable identifier-key]
  (sqlite/execute-one! connectable
                       ["SELECT failure_count, window_started_at, locked_until
                           FROM _ppp_auth_attempts
                          WHERE identifier_key = ?"
                        identifier-key]))

(defn- assert-not-locked!
  [connectable identifier-key now-second]
  (when (> (long (or (:locked_until (attempt-row connectable identifier-key)) 0))
           now-second)
    (throw (auth-error :auth/temporarily-locked
                       "Sign in is temporarily unavailable. Try again shortly."))))

(defn- record-failure!
  [connectable identifier-key now-second]
  (let [{:keys [failure_count window_started_at]} (attempt-row connectable identifier-key)
        same-window? (and window_started_at
                          (< (- now-second (long window_started_at))
                             attempt-window-seconds))
        failures (if same-window? (inc (long failure_count)) 1)
        window-start (if same-window? (long window_started_at) now-second)
        locked-until (if (>= failures maximum-attempts)
                       (+ now-second attempt-lock-seconds)
                       0)]
    (sqlite/execute! connectable
                     ["INSERT INTO _ppp_auth_attempts
                          (identifier_key, failure_count, window_started_at, locked_until)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT(identifier_key) DO UPDATE SET
                          failure_count = excluded.failure_count,
                          window_started_at = excluded.window_started_at,
                          locked_until = excluded.locked_until"
                      identifier-key failures window-start locked-until])
    failures))

(defn- clear-attempts!
  [connectable identifier-key]
  (sqlite/execute! connectable
                   ["DELETE FROM _ppp_auth_attempts WHERE identifier_key = ?"
                    identifier-key]))

(defn register!
  [^Service service connectable session-id {:keys [identifier password]}]
  (ensure-schema! service connectable)
  (let [{:keys [display key]} (normalize-identifier identifier)]
    (when (sqlite/execute-one! connectable
                               ["SELECT id FROM _ppp_auth_users
                                  WHERE identifier_key = ?"
                                key])
      (throw (auth-error :auth/identifier-taken
                         "That sign-in identifier is already in use.")))
    (let [encoded (encode-password service password)
          user-id (str (random-uuid))
          timestamp (str (now service))]
      (try
        (sqlite/execute! connectable
                         ["INSERT INTO _ppp_auth_users
                              (id, identifier_key, identifier_display, password_hash,
                               credential_version, created_at, updated_at)
                            VALUES (?, ?, ?, ?, 1, ?, ?)"
                          user-id key display encoded timestamp timestamp])
        (catch SQLException cause
          (throw (ex-info "That sign-in identifier is already in use."
                          {:code :auth/identifier-taken}
                          cause))))
      (let [user {:id user-id :identifier display :created-at timestamp}]
        {:user user
         :effect (issue-session! service connectable session-id user-id 1)}))))

(defn record-login-failure!
  [^Service service connectable {:keys [identifier-key at]}]
  (when (and (string? identifier-key) (integer? at))
    (ensure-schema! service connectable)
    (record-failure! connectable identifier-key at))
  nil)

(defn login!
  [^Service service connectable session-id {:keys [identifier password]}]
  (ensure-schema! service connectable)
  (let [now-second (epoch-second (now service))
        key (try
              (:key (normalize-identifier identifier))
              (catch clojure.lang.ExceptionInfo _
                "__invalid_identifier__"))]
    (assert-not-locked! connectable key now-second)
    (let [row (sqlite/execute-one!
               connectable
               ["SELECT id, identifier_display, password_hash,
                        credential_version, created_at
                   FROM _ppp_auth_users
                  WHERE identifier_key = ?"
                key])
          encoded (or (:password_hash row) @(:dummy-hash service))
          valid? (try
                   (password-matches? password encoded)
                   (catch clojure.lang.ExceptionInfo _ false))]
      (when-not (and row valid?)
        (throw (ex-info "Those sign-in details did not match."
                        {:code :auth/invalid-credentials
                         :auth/login-attempt {:identifier-key key
                                              :at now-second}})))
      (clear-attempts! connectable key)
      {:user (public-user row)
       :effect (issue-session! service connectable session-id (:id row)
                               (:credential_version row))})))

(defn resolve-context!
  [^Service service connectable session-id token]
  (ensure-schema! service connectable)
  (if (str/blank? token)
    {:user nil :token-hash nil :invalid-token? false}
    (let [now-second (epoch-second (now service))
          digest (token-digest service session-id token)
          row
          (sqlite/execute-one!
           connectable
           ["SELECT u.id, u.identifier_display, u.created_at,
                    s.token_hash, s.expires_at
               FROM _ppp_auth_sessions s
               JOIN _ppp_auth_users u ON u.id = s.user_id
                                 AND u.credential_version = s.credential_version
              WHERE s.token_hash = ? AND s.expires_at > ?"
            digest now-second])]
      (if row
        {:user (public-user row)
         :token-hash (:token_hash row)
         :invalid-token? false}
        {:user nil :token-hash digest :invalid-token? true}))))

(defn test-context!
  [^Service service connectable user-id]
  (ensure-schema! service connectable)
  (let [row (sqlite/execute-one!
             connectable
             ["SELECT id, identifier_display, created_at
                 FROM _ppp_auth_users WHERE id = ?"
              (str user-id)])]
    (when-not row
      (throw (auth-error :auth/test-user-not-found
                         "The generated test user does not exist.")))
    {:user (public-user row)
     :token-hash nil
     :invalid-token? false
     :test? true}))

(defn logout!
  [_service connectable {:keys [token-hash]}]
  (when token-hash
    (sqlite/execute! connectable
                     ["DELETE FROM _ppp_auth_sessions WHERE token_hash = ?"
                      token-hash]))
  {:effect {:op :clear}})

(defn change-password!
  [^Service service connectable session-id {:keys [user]} token-hash
   {:keys [current-password new-password]}]
  (when-not user
    (throw (auth-error :auth/required "Sign in before continuing.")))
  (let [row (sqlite/execute-one!
             connectable
             ["SELECT id, identifier_display, password_hash,
                      credential_version, created_at
                 FROM _ppp_auth_users WHERE id = ?"
              (:id user)])]
    (when-not (and row
                   (try
                     (password-matches? current-password (:password_hash row))
                     (catch clojure.lang.ExceptionInfo _ false)))
      (throw (auth-error :auth/invalid-credentials
                         "Those sign-in details did not match.")))
    (let [next-hash (encode-password service new-password)
          next-version (inc (long (:credential_version row)))
          timestamp (str (now service))]
      (sqlite/execute! connectable
                       ["UPDATE _ppp_auth_users
                           SET password_hash = ?, credential_version = ?, updated_at = ?
                         WHERE id = ?"
                        next-hash next-version timestamp (:id row)])
      (sqlite/execute! connectable
                       ["DELETE FROM _ppp_auth_sessions WHERE user_id = ?"
                        (:id row)])
      (when token-hash
        (sqlite/execute! connectable
                         ["DELETE FROM _ppp_auth_sessions WHERE token_hash = ?"
                          token-hash]))
      {:user (public-user row)
       :effect (issue-session! service connectable session-id (:id row)
                               next-version)})))

(defn delete-account!
  [^Service _service connectable {:keys [user]} {:keys [password]}]
  (when-not user
    (throw (auth-error :auth/required "Sign in before continuing.")))
  (let [row (sqlite/execute-one!
             connectable
             ["SELECT id, password_hash FROM _ppp_auth_users WHERE id = ?"
              (:id user)])]
    (when-not (and row
                   (try
                     (password-matches? password (:password_hash row))
                     (catch clojure.lang.ExceptionInfo _ false)))
      (throw (auth-error :auth/invalid-credentials
                         "Those sign-in details did not match.")))
    (sqlite/execute! connectable
                     ["DELETE FROM _ppp_auth_users WHERE id = ?" (:id user)])
    {:user nil :effect {:op :clear}}))
