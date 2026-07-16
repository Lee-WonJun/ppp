(ns ppp.outbound.policy
  (:require [clojure.string :as str])
  (:import (java.math BigInteger)
           (java.net IDN Inet4Address Inet6Address InetAddress URI)))

(def default-timeout-ms 5000)
(def max-timeout-ms 5000)
(def default-response-limit-bytes (* 1024 1024))
(def max-response-limit-bytes (* 1024 1024))
(def max-request-body-bytes (* 256 1024))
(def max-redirects 5)

(def allowed-methods #{:get :post :put :patch :delete :head})

(def public-request-header-names
  #{"accept" "accept-language" "content-type" "if-match" "if-none-match"})

(def response-header-names
  #{"cache-control" "content-language" "content-type" "etag" "last-modified"
    "location" "retry-after" "x-ratelimit-limit" "x-ratelimit-remaining"
    "x-ratelimit-reset"})

(def forbidden-request-header-names
  #{"authorization" "connection" "cookie" "forwarded" "host" "keep-alive"
    "proxy-authenticate" "proxy-authorization" "proxy-connection" "te" "trailer"
    "transfer-encoding" "upgrade" "via" "x-forwarded-for" "x-forwarded-host"
    "x-forwarded-port" "x-forwarded-proto" "x-real-ip"})

(def ^:private ipv4-blocked-cidrs
  [["0.0.0.0" 8]
   ["10.0.0.0" 8]
   ["100.64.0.0" 10]
   ["127.0.0.0" 8]
   ["169.254.0.0" 16]
   ["172.16.0.0" 12]
   ["192.0.0.0" 24]
   ["192.0.2.0" 24]
   ["192.31.196.0" 24]
   ["192.52.193.0" 24]
   ["192.88.99.0" 24]
   ["192.168.0.0" 16]
   ["192.175.48.0" 24]
   ["198.18.0.0" 15]
   ["198.51.100.0" 24]
   ["203.0.113.0" 24]
   ["224.0.0.0" 4]
   ["240.0.0.0" 4]])

(def ^:private ipv6-global-cidr ["2000::" 3])

(def ^:private ipv6-blocked-cidrs
  [["2001::" 23]
   ["2001:db8::" 32]
   ["2002::" 16]
   ["3fff::" 20]])

(defn- parsed-cidrs
  [cidrs]
  (mapv (fn [[address prefix]]
          [(InetAddress/getByName address) prefix])
        cidrs))

(def ^:private parsed-ipv4-blocked-cidrs (delay (parsed-cidrs ipv4-blocked-cidrs)))
(def ^:private parsed-ipv6-global-cidr (delay (first (parsed-cidrs [ipv6-global-cidr]))))
(def ^:private parsed-ipv6-blocked-cidrs (delay (parsed-cidrs ipv6-blocked-cidrs)))

(defn- unsigned-integer
  [^bytes value]
  (BigInteger. 1 value))

(defn address-in-cidr?
  "Return true when address belongs to the supplied numeric network/prefix."
  [^InetAddress address ^InetAddress network prefix]
  (let [address-bytes (.getAddress address)
        network-bytes (.getAddress network)
        width (* 8 (alength address-bytes))]
    (and (= (alength address-bytes) (alength network-bytes))
         (integer? prefix)
         (<= 0 prefix width)
         (let [shift (- width prefix)]
           (= (.shiftRight (unsigned-integer address-bytes) shift)
              (.shiftRight (unsigned-integer network-bytes) shift))))))

(defn public-address?
  "Conservatively allow only ordinary public unicast IP addresses."
  [value]
  (let [^InetAddress address
        (cond
          (instance? InetAddress value) value
          (string? value) (InetAddress/getByName value)
          :else nil)]
    (boolean
     (and address
          (not (.isAnyLocalAddress address))
          (not (.isLoopbackAddress address))
          (not (.isLinkLocalAddress address))
          (not (.isSiteLocalAddress address))
          (not (.isMulticastAddress address))
          (cond
            (instance? Inet4Address address)
            (not-any? (fn [[network prefix]]
                        (address-in-cidr? address network prefix))
                      @parsed-ipv4-blocked-cidrs)

            (instance? Inet6Address address)
            (and (let [[network prefix] @parsed-ipv6-global-cidr]
                   (address-in-cidr? address network prefix))
                 (not-any? (fn [[network prefix]]
                             (address-in-cidr? address network prefix))
                           @parsed-ipv6-blocked-cidrs))

            :else false)))))

(defn- clean-host!
  [host]
  (let [host (some-> host
                     (str/replace #"^\[|\]$" "")
                     str/lower-case)]
    (when (or (str/blank? host)
              (str/ends-with? host ".")
              (> (count host) 253))
      (throw (ex-info "Outbound URL host is invalid"
                      {:code :outbound/invalid-host})))
    (try
      (IDN/toASCII host IDN/USE_STD3_ASCII_RULES)
      (catch IllegalArgumentException _
        (throw (ex-info "Outbound URL host is invalid"
                        {:code :outbound/invalid-host}))))))

(defn parse-url!
  "Parse and normalize the URL boundary before DNS or network activity."
  ([url]
   (parse-url! url {}))
  ([url {:keys [allowed-ports]
         :or {allowed-ports #{443}}}]
   (when-not (and (string? url)
                  (<= 1 (count url) 4096)
                  (not (re-find #"[\u0000-\u0020\u007f]" url)))
     (throw (ex-info "Outbound URL is invalid"
                     {:code :outbound/invalid-url})))
   (let [^URI uri
         (try
           (URI. url)
           (catch Exception _
             (throw (ex-info "Outbound URL is invalid"
                             {:code :outbound/invalid-url}))))
         scheme (some-> (.getScheme uri) str/lower-case)
         host (clean-host! (.getHost uri))
         explicit-port (.getPort uri)
         port (if (neg? explicit-port) 443 explicit-port)]
     (when-not (= "https" scheme)
       (throw (ex-info "Only HTTPS outbound URLs are allowed"
                       {:code :outbound/https-required})))
     (when (.getRawUserInfo uri)
       (throw (ex-info "Outbound URL user information is forbidden"
                       {:code :outbound/userinfo-forbidden})))
     (when (.getRawFragment uri)
       (throw (ex-info "Outbound URL fragments are forbidden"
                       {:code :outbound/fragment-forbidden})))
     (when-not (contains? (set allowed-ports) port)
       (throw (ex-info "Outbound URL port is not allowed"
                       {:code :outbound/port-forbidden :port port})))
     {:uri uri
      :url (.toASCIIString uri)
      :scheme scheme
      :host host
      :port port
      :raw-path (or (.getRawPath uri) "/")
      :path (or (.getPath uri) "/")})))

(defn normalize-method!
  [value]
  (let [method (cond
                 (keyword? value) (keyword (str/lower-case (name value)))
                 (string? value) (keyword (str/lower-case value))
                 (nil? value) :get
                 :else nil)]
    (when-not (contains? allowed-methods method)
      (throw (ex-info "Outbound HTTP method is not allowed"
                      {:code :outbound/method-forbidden})))
    method))

(defn bounded-timeout!
  [value]
  (let [timeout (long (or value default-timeout-ms))]
    (when-not (<= 1 timeout max-timeout-ms)
      (throw (ex-info "Outbound timeout is outside the allowed range"
                      {:code :outbound/timeout-invalid})))
    timeout))

(defn bounded-response-limit!
  [value]
  (let [limit (long (or value default-response-limit-bytes))]
    (when-not (<= 1 limit max-response-limit-bytes)
      (throw (ex-info "Outbound response limit is outside the allowed range"
                      {:code :outbound/response-limit-invalid})))
    limit))

(defn body-bytes!
  [body]
  (let [bytes (cond
                (nil? body) nil
                (string? body) (.getBytes ^String body java.nio.charset.StandardCharsets/UTF_8)
                (= (class body) (Class/forName "[B")) body
                :else (throw (ex-info "Outbound request body must be text or bytes"
                                      {:code :outbound/body-invalid})))]
    (when (and bytes (> (alength ^bytes bytes) max-request-body-bytes))
      (throw (ex-info "Outbound request body is too large"
                      {:code :outbound/body-too-large})))
    bytes))

(def ^:private header-token-pattern #"^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

(defn normalize-header-name!
  [value]
  (let [header-name (-> (if (keyword? value) (name value) (str value))
                        str/lower-case)]
    (when-not (and (<= 1 (count header-name) 128)
                   (re-matches header-token-pattern header-name))
      (throw (ex-info "Outbound header name is invalid"
                      {:code :outbound/header-invalid})))
    header-name))

(defn normalize-header-value!
  [value]
  (when-not (string? value)
    (throw (ex-info "Outbound header value must be text"
                    {:code :outbound/header-invalid})))
  (when (or (> (count value) 8192)
            (re-find #"[\r\n\u0000]" value))
    (throw (ex-info "Outbound header value is invalid"
                    {:code :outbound/header-invalid})))
  value)

(defn normalize-public-headers!
  [headers]
  (when-not (or (nil? headers) (map? headers))
    (throw (ex-info "Outbound headers must be a map"
                    {:code :outbound/header-invalid})))
  (reduce-kv
   (fn [result name value]
     (let [normalized-name (normalize-header-name! name)]
       (when (contains? forbidden-request-header-names normalized-name)
         (throw (ex-info "Sensitive or routing headers are forbidden"
                         {:code :outbound/header-forbidden
                          :header normalized-name})))
       (when-not (contains? public-request-header-names normalized-name)
         (throw (ex-info "Outbound header is not in the public allowlist"
                         {:code :outbound/header-forbidden
                          :header normalized-name})))
       (when (contains? result normalized-name)
         (throw (ex-info "Outbound header is duplicated"
                         {:code :outbound/header-duplicate
                          :header normalized-name})))
       (assoc result normalized-name (normalize-header-value! value))))
   {}
   (or headers {})))

(defn normalize-secret-headers!
  "Validate developer-owned header names and resolved values. These values must
  never be placed in errors, logs, provider context, or generated source."
  [headers]
  (when-not (map? headers)
    (throw (ex-info "Connector secret headers are invalid"
                    {:code :connector/config-invalid})))
  (reduce-kv
   (fn [result name value]
     (let [normalized-name (normalize-header-name! name)]
       (when (and (contains? forbidden-request-header-names normalized-name)
                  (not (contains? #{"authorization" "cookie"} normalized-name)))
         (throw (ex-info "Connector secret header is unsafe"
                         {:code :connector/config-invalid})))
       (assoc result normalized-name (normalize-header-value! value))))
   {}
   headers))

(defn safe-response-headers
  [headers]
  (reduce
   (fn [result [name value]]
     (let [name (str/lower-case (str name))]
       (if (and (contains? response-header-names name)
                (not (contains? result name)))
         (assoc result name (str value))
         result)))
   {}
   headers))

(defn assert-safe-connector-path!
  [path]
  (when-not (and (string? path)
                 (str/starts-with? path "/")
                 (<= 1 (count path) 2048)
                 (not (str/includes? path "?"))
                 (not (str/includes? path "#"))
                 (not (str/includes? path "\\"))
                 (not (re-find #"(?i)%(?:2e|2f|5c|25)" path)))
    (throw (ex-info "Connector path is invalid"
                    {:code :connector/path-invalid})))
  (let [segments (str/split path #"/" -1)]
    (when (some #{"." ".."} segments)
      (throw (ex-info "Connector path traversal is forbidden"
                      {:code :connector/path-invalid}))))
  path)

(defn path-within-prefix?
  [path prefix]
  (or (= path prefix)
      (and (not= prefix "/")
           (str/starts-with? path (str prefix "/")))
      (= prefix "/")))
