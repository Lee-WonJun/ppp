(ns ppp.shared.protocol
  (:require [clojure.string :as str]
            [malli.core :as m]))

(def protocol-version 1)
(def workspace-id "local")

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
