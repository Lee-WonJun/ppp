(ns ppp.access
  (:require [clojure.string :as str])
  (:import (java.net URI)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.time Instant)
           (java.util Base64)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def cookie-name "ppp_access")
(def csrf-header "x-ppp-csrf")
(def ^:private token-version "v1")
(def ^:private token-lifetime-seconds (* 7 24 60 60))
(def ^:private secure-random (SecureRandom.))

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
