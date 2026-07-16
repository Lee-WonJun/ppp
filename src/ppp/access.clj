(ns ppp.access
  (:require [clojure.string :as str])
  (:import (java.net URI)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.time Instant)
           (java.util Base64)
           (java.util.concurrent.locks ReentrantLock)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def cookie-name "ppp_access")
(def csrf-header "x-ppp-csrf")
(def ^:private token-version "v1")
(def ^:private token-lifetime-seconds (* 7 24 60 60))
(def ^:private secure-random (SecureRandom.))

(defrecord LoginLimiter [limit window-ms attempts ^ReentrantLock lock now-ms-fn])

(defn- utf8
  [value]
  (.getBytes (str value) StandardCharsets/UTF_8))

(defn- sha256
  [value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.digest digest (utf8 value))))

(defn constant-time=
  [left right]
  (and (some? left)
       (some? right)
       (MessageDigest/isEqual (sha256 left) (sha256 right))))

(defn valid-access-code?
  [config submitted]
  (constant-time= (:access-code config) submitted))

(defn- hmac
  [secret value]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (utf8 secret) "HmacSHA256"))
    (.doFinal mac (utf8 value))))

(defn- base64-url
  [^bytes value]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) value))

(defn- random-nonce
  []
  (let [bytes (byte-array 24)]
    (.nextBytes secure-random bytes)
    (base64-url bytes)))

(defn issue-token
  ([config] (issue-token config (Instant/now)))
  ([config ^Instant now]
   (let [expires (+ (.getEpochSecond now) token-lifetime-seconds)
         payload (str token-version "." expires "." (random-nonce))
         signature (base64-url (hmac (:cookie-secret config) payload))]
     (str payload "." signature))))

(defn verify-token
  ([config token] (verify-token config token (Instant/now)))
  ([config token ^Instant now]
   (try
     (let [[version expires nonce signature & extra] (str/split (or token "") #"\.")
           payload (str version "." expires "." nonce)
           expected (base64-url (hmac (:cookie-secret config) payload))
           expires (parse-long expires)]
       (when (and (empty? extra)
                  (= token-version version)
                  (some? expires)
                  (> expires (.getEpochSecond now))
                  (not (str/blank? nonce))
                  (constant-time= expected signature))
         {:version version :expires-at expires :nonce nonce :token token}))
     (catch Exception _ nil))))

(defn parse-cookies
  [request]
  (into {}
        (keep (fn [part]
                (let [[name value] (str/split (str/trim part) #"=" 2)]
                  (when (and (not (str/blank? name)) (some? value))
                    [name value]))))
        (str/split (get-in request [:headers "cookie"] "") #";")))

(defn request-token
  [request]
  (get (parse-cookies request) cookie-name))

(defn authorized-session
  [config request]
  (verify-token config (request-token request)))

(defn csrf-token
  [config {:keys [nonce]}]
  (base64-url (hmac (:cookie-secret config) (str "csrf:" nonce))))

(defn valid-csrf?
  [config request session]
  (constant-time= (csrf-token config session)
                  (get-in request [:headers csrf-header])))

(defn cookie-header
  [config token]
  (str cookie-name "=" token
       "; Path=/; Max-Age=" token-lifetime-seconds
       "; HttpOnly; SameSite=Strict"
       (when (:cookie-secure? config) "; Secure")))

(defn expired-cookie-header
  [config]
  (str cookie-name "=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT"
       "; HttpOnly; SameSite=Strict"
       (when (:cookie-secure? config) "; Secure")))

(defn create-login-limiter
  [{:keys [login-failure-limit login-failure-window-seconds login-now-ms-fn]}]
  (->LoginLimiter (long (or login-failure-limit 10))
                  (* 1000 (long (or login-failure-window-seconds 600)))
                  (atom {}) (ReentrantLock. true)
                  (or login-now-ms-fn #(System/currentTimeMillis))))

(defn- with-limiter-lock
  [^LoginLimiter limiter thunk]
  (let [^ReentrantLock lock (:lock limiter)]
    (.lock lock)
    (try
      (thunk)
      (finally
        (.unlock lock)))))

(defn- remote-key
  [remote-address]
  (let [value (str (or remote-address "unknown"))]
    (subs value 0 (min 128 (count value)))))

(defn- retained-failures
  [timestamps current-ms window-ms]
  (let [cutoff (- current-ms window-ms)]
    (->> timestamps (filter #(> (long %) cutoff)) sort vec)))

(defn- retry-after-seconds
  [timestamps current-ms window-ms]
  (if-let [oldest (first timestamps)]
    (max 1 (long (Math/ceil
                  (/ (double (max 1 (- (+ (long oldest) window-ms)
                                       current-ms)))
                     1000.0))))
    0))

(defn login-status
  [^LoginLimiter limiter remote-address]
  (with-limiter-lock
    limiter
    (fn []
      (let [current-ms (long ((:now-ms-fn limiter)))
            key (remote-key remote-address)
            retained
            (into {}
                  (keep (fn [[entry-key timestamps]]
                          (let [current (retained-failures timestamps current-ms
                                                           (:window-ms limiter))]
                            (when (seq current) [entry-key current]))))
                  @(:attempts limiter))
            timestamps (get retained key [])
            available? (< (count timestamps) (:limit limiter))]
        (reset! (:attempts limiter) retained)
        {:available? available?
         :retry-after-seconds
         (if available?
           0
           (retry-after-seconds timestamps current-ms (:window-ms limiter)))}))))

(defn record-login-failure!
  [^LoginLimiter limiter remote-address]
  (with-limiter-lock
    limiter
    (fn []
      (let [current-ms (long ((:now-ms-fn limiter)))
            key (remote-key remote-address)
            current (retained-failures (get @(:attempts limiter) key [])
                                       current-ms (:window-ms limiter))
            next-attempts (assoc @(:attempts limiter) key (conj current current-ms))
            bounded (if (> (count next-attempts) 10000)
                      (->> next-attempts
                           (sort-by (comp last val))
                           (take-last 10000)
                           (into {}))
                      next-attempts)]
        (reset! (:attempts limiter) bounded)
        nil))))

(defn clear-login-failures!
  [^LoginLimiter limiter remote-address]
  (with-limiter-lock
    limiter
    #(swap! (:attempts limiter) dissoc (remote-key remote-address)))
  nil)

(defn expected-origin
  [config]
  (let [uri (URI. (:public-base-url config))
        default-port? (or (= -1 (.getPort uri))
                          (and (= "https" (.getScheme uri)) (= 443 (.getPort uri)))
                          (and (= "http" (.getScheme uri)) (= 80 (.getPort uri))))]
    (str (.getScheme uri) "://" (.getHost uri)
         (when-not default-port? (str ":" (.getPort uri))))))

(defn allowed-origin?
  [config request]
  (= (expected-origin config) (get-in request [:headers "origin"])))
