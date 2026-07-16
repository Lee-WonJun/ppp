(ns ppp.outbound.client
  (:require [clojure.string :as str]
            [ppp.outbound.policy :as policy])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.net InetAddress URI UnknownHostException)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent ArrayBlockingQueue Callable ExecutionException
                                 RejectedExecutionException ThreadFactory
                                 ThreadPoolExecutor ThreadPoolExecutor$AbortPolicy
                                 TimeUnit TimeoutException)
           (java.util.concurrent.atomic AtomicLong)
           (org.apache.hc.client5.http DnsResolver)
           (org.apache.hc.client5.http.classic.methods HttpUriRequestBase)
           (org.apache.hc.client5.http.config RequestConfig)
           (org.apache.hc.client5.http.impl.classic CloseableHttpClient HttpClients)
           (org.apache.hc.client5.http.impl.io PoolingHttpClientConnectionManagerBuilder)
           (org.apache.hc.core5.http ClassicHttpResponse ContentType Header HttpEntity)
           (org.apache.hc.core5.http.io.entity ByteArrayEntity)))

(defrecord Client [resolver address-policy allowed-ports transport tls-socket-strategy])

(defn- resolver-thread-factory
  []
  (let [sequence (AtomicLong.)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable
                       (str "ppp-outbound-dns-" (.incrementAndGet sequence)))
          (.setDaemon true))))))

(defonce ^ThreadPoolExecutor resolver-executor
  (doto (ThreadPoolExecutor.
         2 8 30 TimeUnit/SECONDS
         (ArrayBlockingQueue. 64)
         (resolver-thread-factory)
         (ThreadPoolExecutor$AbortPolicy.))
    (.allowCoreThreadTimeOut true)))

(defn create-client
  ([]
   (create-client {}))
  ([{:keys [resolver address-policy allowed-ports transport tls-socket-strategy]
     :or {resolver (fn [host]
                     (vec (InetAddress/getAllByName host)))
          address-policy (fn [_host address]
                           (policy/public-address? address))
          allowed-ports #{443}}}]
   (->Client resolver address-policy (set allowed-ports) transport tls-socket-strategy)))

(defn- bounded-resolve!
  [^Client client host timeout-ms]
  (let [task
        (try
          (.submit resolver-executor
                   ^Callable
                   (fn [] (vec ((:resolver client) host))))
          (catch RejectedExecutionException _
            (throw (ex-info "Outbound DNS capacity is busy"
                            {:code :outbound/dns-busy}))))]
    (try
      (.get task (long timeout-ms) TimeUnit/MILLISECONDS)
      (catch TimeoutException _
        (.cancel task true)
        (.purge resolver-executor)
        (throw (ex-info "Outbound DNS resolution timed out"
                        {:code :outbound/dns-timeout})))
      (catch ExecutionException _
        (throw (ex-info "Outbound host could not be resolved"
                        {:code :outbound/dns-failed})))
      (catch InterruptedException _
        (.cancel task true)
        (.interrupt (Thread/currentThread))
        (throw (ex-info "Outbound DNS resolution was interrupted"
                        {:code :outbound/dns-failed}))))))

(defn resolve-public!
  "Resolve once in the kernel, reject the complete set unless every answer is
  public, then return the exact addresses that the transport must pin."
  ([client host]
   (resolve-public! client host policy/default-timeout-ms))
  ([^Client client host timeout-ms]
   (let [addresses (bounded-resolve! client host timeout-ms)]
     (when (or (empty? addresses)
               (not-every? #(instance? InetAddress %) addresses))
       (throw (ex-info "Outbound host did not resolve to usable addresses"
                       {:code :outbound/dns-failed})))
     (when-not (every? #((:address-policy client) host %) addresses)
       (throw (ex-info "Outbound host resolved to a non-public address"
                       {:code :outbound/address-forbidden})))
     (vec (distinct addresses)))))

(defn- pinned-resolver
  [expected-host addresses]
  (reify DnsResolver
    (resolve [_ requested-host]
      (if (= (str/lower-case expected-host) (str/lower-case requested-host))
        (into-array InetAddress addresses)
        (throw (UnknownHostException. "Unapproved outbound hostname"))))
    (resolveCanonicalHostname [_ requested-host]
      (if (= (str/lower-case expected-host) (str/lower-case requested-host))
        expected-host
        (throw (UnknownHostException. "Unapproved outbound hostname"))))))

(defn- request-config
  [timeout-ms]
  (-> (RequestConfig/custom)
      (.setRedirectsEnabled false)
      (.setAuthenticationEnabled false)
      (.setContentCompressionEnabled true)
      (.setProtocolUpgradeEnabled false)
      (.setConnectionRequestTimeout timeout-ms TimeUnit/MILLISECONDS)
      (.setConnectTimeout timeout-ms TimeUnit/MILLISECONDS)
      (.setResponseTimeout timeout-ms TimeUnit/MILLISECONDS)
      (.build)))

(defn- apache-client
  [host addresses timeout-ms tls-socket-strategy]
  (let [connection-manager
        (cond-> (PoolingHttpClientConnectionManagerBuilder/create)
          true (.setDnsResolver (pinned-resolver host addresses))
          tls-socket-strategy (.setTlsSocketStrategy tls-socket-strategy)
          true (.setMaxConnTotal 1)
          true (.setMaxConnPerRoute 1)
          true (.build))]
    (-> (HttpClients/custom)
        (.setConnectionManager connection-manager)
        (.setDefaultRequestConfig (request-config timeout-ms))
        (.disableAutomaticRetries)
        (.disableRedirectHandling)
        (.disableCookieManagement)
        (.disableAuthCaching)
        (.setUserAgent "ProgrammableProgrammingPage/1")
        (.build))))

(defn- content-type
  [headers]
  (if-let [value (get headers "content-type")]
    (try
      (ContentType/parseLenient value)
      (catch Exception _ ContentType/APPLICATION_OCTET_STREAM))
    ContentType/APPLICATION_OCTET_STREAM))

(defn- apache-request
  [{:keys [^URI uri method headers body]}]
  (let [request (HttpUriRequestBase. (str/upper-case (name method)) uri)]
    (doseq [[name value] (dissoc headers "content-type")]
      (.setHeader request name value))
    (when body
      (.setEntity request (ByteArrayEntity. ^bytes body (content-type headers))))
    request))

(defn- bounded-read!
  [^InputStream input limit]
  (with-open [input input
              output (ByteArrayOutputStream.)]
    (let [buffer (byte-array 8192)]
      (loop [total 0]
        (let [read (.read input buffer)]
          (if (neg? read)
            (.toByteArray output)
            (let [next-total (+ total read)]
              (when (> next-total limit)
                (throw (ex-info "Outbound response is too large"
                                {:code :outbound/response-too-large
                                 :limit limit})))
              (.write output buffer 0 read)
              (recur next-total))))))))

(defn- response-headers
  [^ClassicHttpResponse response]
  (mapv (fn [^Header header]
          [(.getName header) (.getValue header)])
        (.getHeaders response)))

(defn- apache-transport!
  [^Client outbound-client
   {:keys [host addresses timeout-ms response-limit] :as request}]
  (try
    (with-open [^CloseableHttpClient client
                (apache-client host addresses timeout-ms
                               (:tls-socket-strategy outbound-client))
                ^ClassicHttpResponse response
                (.execute client (apache-request request))]
      (let [^HttpEntity entity (.getEntity response)
            bytes (if entity
                    (bounded-read! (.getContent entity) response-limit)
                    (byte-array 0))]
        {:status (.getCode response)
         :headers (response-headers response)
         :body (String. ^bytes bytes StandardCharsets/UTF_8)}))
    (catch clojure.lang.ExceptionInfo cause
      (throw cause))
    (catch java.net.SocketTimeoutException _
      (throw (ex-info "Outbound request timed out"
                      {:code :outbound/timeout})))
    (catch Exception _
      ;; Never retain the transport exception: an implementation is permitted
      ;; to include request metadata, while connector headers are secrets.
      (throw (ex-info "Outbound request failed"
                      {:code :outbound/request-failed})))))

(defn- redirect-status?
  [status]
  (contains? #{301 302 303 307 308} status))

(defn- redirect-location
  [headers]
  (some (fn [[name value]]
          (when (= "location" (str/lower-case (str name))) value))
        headers))

(defn- redirected-request
  [request status ^URI next-uri]
  (let [switch-to-get? (or (= status 303)
                           (and (contains? #{301 302} status)
                                (= :post (:method request))))]
    (cond-> (assoc request :uri next-uri :url (.toASCIIString next-uri))
      switch-to-get? (assoc :method :get :body nil
                            :headers (dissoc (:headers request) "content-type")))))

(defn- execute-hop!
  [^Client client request]
  (if-let [transport (:transport client)]
    (transport request)
    (apache-transport! client request)))

(defn- remaining-time!
  [deadline]
  (let [remaining-nanos (- deadline (System/nanoTime))]
    (when-not (pos? remaining-nanos)
      (throw (ex-info "Outbound request timed out"
                      {:code :outbound/timeout})))
    (max 1 (long (Math/ceil (/ remaining-nanos 1000000.0))))))

(defn request!
  "Execute a bounded outbound request. Internal options are host-owned hooks:
  authorize-uri! runs before DNS; headers-after-validation runs only after the
  URL and all resolved addresses pass policy. Both hooks run again on redirects."
  ([client request]
   (request! client request {}))
  ([^Client client request {:keys [authorize-uri! headers-after-validation]}]
   (when-not (map? request)
     (throw (ex-info "Outbound request must be a map"
                     {:code :outbound/request-invalid})))
   (let [method (policy/normalize-method! (:method request))
         body (policy/body-bytes! (:body request))
         headers (policy/normalize-public-headers! (:headers request))
         timeout-ms (policy/bounded-timeout! (:timeout-ms request))
         response-limit (policy/bounded-response-limit! (:response-limit-bytes request))
         _ (when (and body (contains? #{:get :head} method))
             (throw (ex-info "This outbound method cannot include a body"
                             {:code :outbound/body-forbidden})))
         deadline (+ (System/nanoTime) (* timeout-ms 1000000))
         initial (assoc request
                        :method method
                        :body body
                        :headers headers
                        :timeout-ms timeout-ms
                        :response-limit response-limit)]
     (loop [current initial
            redirect-count 0]
       (let [{:keys [uri host] :as parsed}
             (policy/parse-url! (:url current)
                                {:allowed-ports (:allowed-ports client)})
             _ (when authorize-uri! (authorize-uri! parsed))
             addresses (resolve-public! client host (remaining-time! deadline))
             secret-headers
             (if headers-after-validation
               (policy/normalize-secret-headers!
                (or (headers-after-validation parsed) {}))
               {})
             overlap (seq (filter #(contains? (:headers current) %)
                                  (keys secret-headers)))
             _ (when overlap
                 (throw (ex-info "Connector header conflicts with a public header"
                                 {:code :connector/header-conflict})))
             remaining-ms (remaining-time! deadline)
             response
             (execute-hop!
              client
              (assoc current
                     :uri uri
                     :host host
                     :addresses addresses
                     :headers (merge (:headers current) secret-headers)
                     :timeout-ms remaining-ms))
             status (:status response)
             location (when (redirect-status? status)
                        (redirect-location (:headers response)))]
         (if (and (redirect-status? status) location)
           (do
             (when (>= redirect-count policy/max-redirects)
               (throw (ex-info "Outbound redirect limit was exceeded"
                               {:code :outbound/redirect-limit})))
             (let [next-uri
                   (try
                     (.resolve ^URI uri ^String location)
                     (catch Exception _
                       (throw (ex-info "Outbound redirect is invalid"
                                       {:code :outbound/redirect-invalid}))))]
               (recur (redirected-request current status next-uri)
                      (inc redirect-count))))
           {:status status
            :headers (policy/safe-response-headers (:headers response))
            :body (or (:body response) "")
            :url (.toASCIIString ^URI uri)
            :redirects redirect-count}))))))
