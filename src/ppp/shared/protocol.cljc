(ns ppp.shared.protocol
  (:require [clojure.string :as str]
            [malli.core :as m]))

(def protocol-version 1)
(def workspace-id "local")
(def max-client-diagnostics 12)

(def ^:private diagnostic-text-limit 240)
(def ^:private diagnostic-identifier-limit 96)
(def ^:private diagnostic-url-limit 160)
(def ^:private diagnostic-kinds #{:action :runtime :console :network})
(def ^:private diagnostic-levels #{:warn :error})
(def ^:private diagnostic-methods
  #{"GET" "POST" "PUT" "PATCH" "DELETE" "HEAD" "OPTIONS"})

(defn- map-field
  [value & keys]
  (some (fn [key]
          (when (contains? value key)
            (get value key)))
        keys))

(defn- enum-keyword
  [value allowed]
  (let [candidate
        (cond
          (and (keyword? value) (nil? (namespace value))) value
          (and (string? value) (re-matches #"[a-z]+" value)) (keyword value)
          :else nil)]
    (when (contains? allowed candidate) candidate)))

(defn- one-line
  [value limit]
  (when (string? value)
    (let [value (-> value
                    (str/replace #"[\r\n\t]+" " ")
                    (str/replace #"\s+" " ")
                    str/trim)]
      (when-not (str/blank? value)
        (subs value 0 (min limit (count value)))))))

(defn- redact-diagnostic-text
  [value limit]
  (when-let [value (one-line value (* 2 limit))]
    (-> value
        (str/replace #"(?i)\b(bearer)\s+[A-Za-z0-9._~+/=-]+" "$1 [redacted]")
        (str/replace #"(?i)\b(password|passwd|authorization|cookie|token|secret|api[-_]?key)\s*[:=]\s*[^\s,;]+"
                     "$1=[redacted]")
        (str/replace #"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b"
                     "[redacted-email]")
        (str/replace #"[A-Za-z0-9_+/=-]{32,}" "[redacted]")
        (one-line limit))))

(defn- identifier-text
  [value]
  (let [value (cond
                (keyword? value)
                (if-let [ns-value (namespace value)]
                  (str ns-value "/" (name value))
                  (name value))

                (string? value) value
                :else nil)
        value (one-line value diagnostic-identifier-limit)]
    (when (and value (re-matches #"[A-Za-z0-9._/-]+" value)) value)))

(defn- status-value
  [value]
  (when (and (integer? value) (<= 0 value 599)) value))

(defn- network-url
  [value]
  (when-let [value (one-line value (* 2 diagnostic-url-limit))]
    (let [value (first (str/split value #"[?#]" 2))]
      (when (and (or (str/starts-with? value "/")
                     (str/starts-with? value "http://")
                     (str/starts-with? value "https://"))
                 (not (re-find #"^[A-Za-z][A-Za-z0-9+.-]*://[^/]*@" value)))
        (one-line value diagnostic-url-limit)))))

(defn normalize-client-diagnostic
  "Return one strict diagnostic record or nil. The result is safe to carry as
  volatile provider evidence, not safe to treat as instructions."
  [value]
  (when (map? value)
    (let [kind (enum-keyword (map-field value :kind "kind") diagnostic-kinds)
          message (redact-diagnostic-text (map-field value :message "message")
                                          diagnostic-text-limit)
          code (identifier-text (map-field value :code "code"))
          status (status-value (map-field value :status "status"))]
      (case kind
        :action
        (when-let [action-id
                   (identifier-text (map-field value :action-id :actionId
                                               "action-id" "actionId"))]
          (when (or code status message)
            (cond-> {:kind :action :action-id action-id}
              code (assoc :code code)
              status (assoc :status status)
              message (assoc :message message))))

        :runtime
        (when (or code message)
          (cond-> {:kind :runtime}
            code (assoc :code code)
            message (assoc :message message)))

        :console
        (when-let [level (enum-keyword (map-field value :level "level")
                                       diagnostic-levels)]
          (when message
            {:kind :console :level level :message message}))

        :network
        (let [method (some-> (map-field value :method "method") str str/upper-case)
              method (when (contains? diagnostic-methods method) method)
              url (network-url (map-field value :url "url"))]
          (when (and method url (or status message))
            (cond-> {:kind :network :method method :url url}
              status (assoc :status status)
              message (assoc :message message))))

        nil))))

(defn normalize-client-diagnostics
  "Normalize a complete diagnostic ring. Nil means invalid input; an empty
  vector is valid. Bounds apply before deduplication so duplicates cannot hide
  an oversized request."
  [value]
  (when (and (vector? value) (<= (count value) max-client-diagnostics))
    (let [normalized (mapv normalize-client-diagnostic value)]
      (when (every? some? normalized)
        (vec (distinct normalized))))))

(defn append-client-diagnostic
  "Append one normalized record to a volatile deduplicated bounded ring."
  [current value]
  (let [current (if (vector? current) current [])]
    (if-let [diagnostic (normalize-client-diagnostic value)]
      (if (some #{diagnostic} current)
        current
        (->> (conj current diagnostic)
             (take-last max-client-diagnostics)
             vec))
      current)))

(def source-write-schema
  [:map
   [:path string?]
   [:content string?]])

(def migration-schema
  [:map
   [:name string?]
   [:sql string?]])

(def change-schema
  [:map
   [:title string?]
   [:writes [:vector source-write-schema]]
   [:deletes [:vector string?]]
   [:migrations [:vector migration-schema]]])

(def provider-result-schema
  [:map
   [:kind [:enum :reply :clarify :change :restore]]
   [:assistant-message string?]
   [:clarification-question {:optional true} [:maybe string?]]
   [:restore-version {:optional true} [:maybe nat-int?]]
   [:change {:optional true} [:maybe change-schema]]])

(defn- present-text?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- kind-fields-valid?
  [{:keys [kind assistant-message clarification-question restore-version change]}]
  (and
   (present-text? assistant-message)
   (case kind
     :reply (and (nil? clarification-question)
                 (nil? restore-version)
                 (nil? change))
     :clarify (and (present-text? clarification-question)
                   (nil? restore-version)
                   (nil? change))
     :change (and (nil? clarification-question)
                  (nil? restore-version)
                  (map? change)
                  (present-text? (:title change)))
     :restore (and (nil? clarification-question)
                   (nat-int? restore-version)
                   (nil? change))
     false)))

(def ws-envelope-schema
  [:map
   [:protocol-version [:= protocol-version]]
   [:workspace-id [:= workspace-id]]
   [:session-id uuid?]
   [:request-id uuid?]
   [:runtime-version nat-int?]
   [:type keyword?]
   [:payload map?]])

(defn valid-envelope?
  [value]
  (m/validate ws-envelope-schema value))

(def client-message-types
  #{:session/subscribe :runtime/staged :runtime/rejected})

(def server-message-types
  #{:turn/queued :turn/progress :turn/completed :turn/failed
    :runtime/stage :runtime/activate :runtime/resync :product/event})

(defn- version-pair-valid?
  [{:keys [base-version target-version]}]
  (and (nat-int? base-version)
       (nat-int? target-version)
       (= target-version (inc base-version))))

(defn- rejection-details-valid?
  [details]
  (or (nil? details)
      (and (vector? details)
           (<= (count details) 4)
           (every? #(and (string? %) (<= (count %) 240)) details))))

(defn- client-payload-valid?
  [type payload]
  (case type
    :session/subscribe
    (and (uuid? (:tab-id payload))
         (nat-int? (:current-version payload)))

    :runtime/staged
    (and (uuid? (:tab-id payload))
         (uuid? (:transaction-id payload))
         (version-pair-valid? payload))

    :runtime/rejected
    (and (uuid? (:tab-id payload))
         (uuid? (:transaction-id payload))
         (version-pair-valid? payload)
         (keyword? (:code payload))
         (rejection-details-valid? (:details payload)))

    false))

(defn valid-client-envelope?
  [{:keys [type payload runtime-version] :as value}]
  (and (valid-envelope? value)
       (contains? client-message-types type)
       (client-payload-valid? type payload)
       (case type
         :session/subscribe (= runtime-version (:current-version payload))
         :runtime/staged (= runtime-version (:target-version payload))
         :runtime/rejected (= runtime-version (:target-version payload))
         false)))

(defn client-runtime-files
  [source]
  (->> source
       (filter (fn [[path _]]
                 (or (str/starts-with? path "src/client/")
                     (str/starts-with? path "src/shared/")
                     (str/starts-with? path "styles/"))))
       (sort-by key)
       (mapv (fn [[path content]] {:path path :content content}))))

(def source-manifest-schema
  [:map
   [:format-version pos-int?]
   [:capability-version pos-int?]
   [:runtime-version nat-int?]
   [:files [:map-of string? string?]]
   [:migrations [:vector string?]]
   [:created-at inst?]
   [:updated-at inst?]])

(defn valid-provider-result?
  [value]
  (and (m/validate provider-result-schema value)
       (kind-fields-valid? value)))

(defn valid-manifest?
  [value]
  (m/validate source-manifest-schema value))

(defn envelope
  [{:keys [session-id request-id runtime-version type payload]}]
  {:protocol-version protocol-version
   :workspace-id workspace-id
   :session-id session-id
   :request-id request-id
   :runtime-version runtime-version
   :type type
   :payload (or payload {})})
