(ns ppp.outbound.service
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [ppp.outbound.client :as client]
            [ppp.outbound.policy :as policy])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption Path Paths)))

(defrecord Service [client connectors env])

(def ^:private connector-config-keys
  #{:description :base-url :allow :secret-headers :timeout-ms
    :response-limit-bytes})

(def ^:private connector-allow-keys
  #{:methods :path-prefixes :query-parameters :body})

(defn- invalid-config!
  []
  (throw (ex-info "Connector configuration is invalid"
                  {:code :connector/config-invalid})))

(defn- connector-alias!
  [value]
  (let [alias (cond
                (keyword? value) value
                (string? value) (keyword value)
                :else nil)
        alias-name (some-> alias name)]
    (when-not (and alias-name
                   (<= 1 (count alias-name) 64)
                   (re-matches #"[a-z][a-z0-9._-]*" alias-name))
      (invalid-config!))
    alias))

(defn- normalize-prefix!
  [prefix]
  (policy/assert-safe-connector-path! prefix)
  (let [trimmed (if (and (> (count prefix) 1) (str/ends-with? prefix "/"))
                  (subs prefix 0 (dec (count prefix)))
                  prefix)]
    (when-not (= prefix trimmed)
      (invalid-config!))
    trimmed))

(defn- normalize-secret-config!
  [secret-headers]
  (when-not (or (nil? secret-headers) (map? secret-headers))
    (invalid-config!))
  (reduce-kv
   (fn [result header specification]
     (let [header (policy/normalize-header-name! header)
           env-name (:env specification)]
       (when-not (and (map? specification)
                      (= #{:env} (set (keys specification)))
                      (string? env-name)
                      (<= 1 (count env-name) 128)
                      (re-matches #"[A-Z_][A-Z0-9_]*" env-name))
         (invalid-config!))
       ;; Validate the name through the separate developer-owned header policy.
       (policy/normalize-secret-headers! {header "validation-placeholder"})
       (assoc result header env-name)))
   {}
   (or secret-headers {})))

(defn- normalize-contract!
  [allow]
  (let [query-parameters (:query-parameters allow)
        body (:body allow)]
    (when-not (or (nil? query-parameters)
                  (and (vector? query-parameters)
                       (<= (count query-parameters) 64)
                       (every? #(and (string? %) (<= 1 (count %) 96))
                               query-parameters)))
      (invalid-config!))
    (when-not (or (nil? body)
                  (and (string? body) (<= (count body) 500)))
      (invalid-config!))
    (cond-> {}
      query-parameters (assoc :query-parameters query-parameters)
      body (assoc :body body))))

(defn- normalize-connector!
  [allowed-ports alias value]
  (when-not (and (map? value)
                 (every? connector-config-keys (keys value)))
    (invalid-config!))
  (let [alias (connector-alias! alias)
        description (:description value)
        parsed (policy/parse-url! (:base-url value)
                                  {:allowed-ports allowed-ports})
        allow (:allow value)
        methods (try
                  (set (map policy/normalize-method! (:methods allow)))
                  (catch Exception _ (invalid-config!)))
        prefixes (try
                   (set (map normalize-prefix! (:path-prefixes allow)))
                   (catch Exception _ (invalid-config!)))
        timeout-ms (try
                     (policy/bounded-timeout! (:timeout-ms value))
                     (catch Exception _ (invalid-config!)))
        response-limit (try
                         (policy/bounded-response-limit!
                          (:response-limit-bytes value))
                         (catch Exception _ (invalid-config!)))]
    (when-not (and (string? description)
                   (<= 1 (count description) 500)
                   (map? allow)
                   (every? connector-allow-keys (keys allow))
                   (seq methods)
                   (seq prefixes)
                   (contains? #{"" "/"} (:raw-path parsed))
                   (nil? (.getRawQuery ^java.net.URI (:uri parsed))))
      (invalid-config!))
    {:alias alias
     :description description
     :scheme (:scheme parsed)
     :host (:host parsed)
     :port (:port parsed)
     :methods methods
     :path-prefixes prefixes
     :contract (normalize-contract! allow)
     :secret-env-by-header (normalize-secret-config! (:secret-headers value))
     :timeout-ms timeout-ms
     :response-limit-bytes response-limit}))

(defn- read-config-file!
  [path]
  (if-not path
    {}
    (let [^Path path (if (instance? Path path)
                       path
                       (Paths/get (str path) (make-array String 0)))]
      (if-not (Files/exists path (make-array LinkOption 0))
        {}
        (let [size (Files/size path)]
          (when (> size (* 128 1024))
            (invalid-config!))
          (try
            (edn/read-string (Files/readString path StandardCharsets/UTF_8))
            (catch Exception _ (invalid-config!))))))))

(defn load-connectors!
  [path allowed-ports]
  (let [value (read-config-file! path)]
    (when-not (and (map? value)
                   (every? #{:connectors} (keys value)))
      (invalid-config!))
    (let [connectors (or (:connectors value) {})]
      (when-not (and (map? connectors) (<= (count connectors) 64))
        (invalid-config!))
      (let [normalized
            (mapv (fn [[alias connector]]
                    (normalize-connector! allowed-ports alias connector))
                  connectors)]
        (when-not (= (count normalized)
                     (count (distinct (map :alias normalized))))
          (invalid-config!))
        (into {} (map (juxt :alias identity)) normalized)))))

(defn create-service
  [{:keys [connectors-file outbound-resolver outbound-transport
           outbound-allowed-ports outbound-env outbound-tls-socket-strategy]
    :or {outbound-allowed-ports #{443}
         outbound-env #(System/getenv %)}}]
  (let [http-client
        (client/create-client
         {:resolver (or outbound-resolver
                        (fn [host] (vec (java.net.InetAddress/getAllByName host))))
          :transport outbound-transport
          :tls-socket-strategy outbound-tls-socket-strategy
          :allowed-ports outbound-allowed-ports})]
    (->Service http-client
               (load-connectors! connectors-file outbound-allowed-ports)
               outbound-env)))

(defn catalog
  "Return only model-safe connector capabilities. Origins, secret values, and
  environment variable names are intentionally absent."
  [^Service service]
  (->> (:connectors service)
       vals
       (sort-by (comp name :alias))
       (mapv (fn [{:keys [alias description methods path-prefixes contract]}]
               (merge {:alias alias
                       :description description
                       :methods (vec (sort methods))
                       :path-prefixes (vec (sort path-prefixes))}
                      contract)))))

(defn readiness
  [^Service service]
  {:ready? true
   :connector-count (count (:connectors service))})

(defn public-request!
  [^Service service request]
  (client/request! (:client service) request))

(defn- encode-query-part
  [value]
  (-> (URLEncoder/encode (str value) StandardCharsets/UTF_8)
      (str/replace "+" "%20")))

(defn- query-pairs!
  [query allowed-parameters]
  (when-not (or (nil? query) (map? query))
    (throw (ex-info "Connector query must be a map"
                    {:code :connector/query-invalid})))
  (let [allowed-parameters (set (or allowed-parameters []))
        normalized
        (mapv
         (fn [[parameter-name value]]
           (let [parameter-name (if (keyword? parameter-name)
                                  (clojure.core/name parameter-name)
                                  parameter-name)]
             (when-not (and (string? parameter-name)
                            (<= 1 (count parameter-name) 128))
               (throw (ex-info "Connector query name is invalid"
                               {:code :connector/query-invalid})))
             (when-not (contains? allowed-parameters parameter-name)
               (throw (ex-info "Connector query is outside its declared contract"
                               {:code :connector/query-forbidden})))
             [parameter-name value]))
         query)
        names (map first normalized)
        _ (when-not (= (count names) (count (distinct names)))
            (throw (ex-info "Connector query name is duplicated"
                            {:code :connector/query-invalid})))
        pairs
        (mapcat
         (fn [[parameter-name value]]
           (let [values (if (sequential? value) value [value])]
             (for [item values]
               (do
                 (when-not (or (string? item)
                               (number? item)
                               (boolean? item)
                               (nil? item))
                   (throw (ex-info "Connector query value is invalid"
                                   {:code :connector/query-invalid})))
                 [(encode-query-part parameter-name)
                  (encode-query-part (or item ""))]))))
         normalized)]
    (when (> (count pairs) 100)
      (throw (ex-info "Connector query has too many values"
                      {:code :connector/query-invalid})))
    pairs))

(defn- connector-url
  [connector path query]
  (let [host (:host connector)
        display-host (if (str/includes? host ":") (str "[" host "]") host)
        port-suffix (if (= 443 (:port connector)) "" (str ":" (:port connector)))
        query-string (->> (query-pairs! query
                                        (get-in connector
                                                [:contract :query-parameters]))
                          (map (fn [[name value]] (str name "=" value)))
                          (str/join "&"))]
    (str "https://" display-host port-suffix path
         (when (seq query-string) (str "?" query-string)))))

(defn- authorize-connector-uri!
  [connector method parsed]
  (let [path (:path parsed)]
    (policy/assert-safe-connector-path! (:raw-path parsed))
    (when-not (and (= (:scheme connector) (:scheme parsed))
                   (= (:host connector) (:host parsed))
                   (= (:port connector) (:port parsed))
                   (contains? (:methods connector) method)
                   (some #(policy/path-within-prefix? path %)
                         (:path-prefixes connector)))
      (throw (ex-info "Connector target is outside its configured policy"
                      {:code :connector/target-forbidden})))
    true))

(defn- resolve-secret-headers!
  [^Service service connector]
  (reduce-kv
   (fn [result header env-name]
     (let [value ((:env service) env-name)]
       (when-not (and (string? value) (not (str/blank? value)))
         ;; Do not include the environment variable name in error data.
         (throw (ex-info "A connector credential is unavailable"
                         {:code :connector/credential-unavailable})))
       (assoc result header value)))
   {}
   (:secret-env-by-header connector)))

(defn connector-request!
  [^Service service alias request]
  (when-not (map? request)
    (throw (ex-info "Connector request must be a map"
                    {:code :connector/request-invalid})))
  (when (contains? request :url)
    (throw (ex-info "Connector requests cannot select an arbitrary URL"
                    {:code :connector/url-forbidden})))
  (let [alias (try
                (connector-alias! alias)
                (catch Exception _
                  (throw (ex-info "Named connector was not found"
                                  {:code :connector/not-found}))))
        connector (get (:connectors service) alias)
        _ (when-not connector
            (throw (ex-info "Named connector was not found"
                            {:code :connector/not-found})))
        _ (when (and (some? (:body request))
                     (nil? (get-in connector [:contract :body])))
            (throw (ex-info "Connector request body is outside its declared contract"
                            {:code :connector/body-forbidden})))
        path (policy/assert-safe-connector-path! (:path request))
        method (policy/normalize-method! (:method request))
        url (connector-url connector path (:query request))
        outbound-request
        (-> request
            (dissoc :path :query)
            (assoc :url url
                   :method method
                   :timeout-ms (:timeout-ms connector)
                   :response-limit-bytes (:response-limit-bytes connector)))]
    (client/request!
     (:client service)
     outbound-request
     {:authorize-uri! #(authorize-connector-uri! connector method %)
      :headers-after-validation
      (fn [_validated-uri]
        (resolve-secret-headers! service connector))})))
