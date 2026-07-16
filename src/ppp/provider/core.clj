(ns ppp.provider.core
  (:require [ppp.shared.protocol :as protocol]))

(defprotocol Provider
  (ready? [provider]
    "Return a non-secret readiness description.")
  (generate! [provider request]
    "Return {:result provider-result :thread-id string?}."))

(defn error
  ([code message]
   (error code message nil))
  ([code message data]
   (ex-info message (merge {:code code} data))))

(defn normalize-result
  [value]
  (cond-> value
    (string? (:kind value)) (update :kind keyword)))

(defn generation
  [result thread-id]
  (let [result (normalize-result result)]
    (when-not (protocol/valid-provider-result? result)
      (throw (error :provider/schema-invalid
                    "The provider returned a value outside the turn contract")))
    (when-not (or (nil? thread-id) (string? thread-id))
      (throw (error :provider/thread-invalid
                    "The provider returned an invalid thread identifier")))
    {:result result
     :thread-id thread-id}))

(defn reset-thread-request
  [request]
  (assoc request :thread-id nil))

(defn change-result
  [message title writes migrations]
  {:kind :change
   :assistant-message message
   :clarification-question nil
   :restore-version nil
   :change {:title title
            :writes (vec writes)
            :deletes []
            :migrations (vec migrations)}})
