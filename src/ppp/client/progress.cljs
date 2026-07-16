(ns ppp.client.progress)

(def phase-copy
  {:generating {:label "Generating"
                :detail "Thinking through your request"}
   :validating {:label "Validating"
                :detail "Checking the proposed product"}
   :applying {:label "Applying"
              :detail "Updating the live product"}
   :applied {:label "Applied"
             :detail "Your product is ready"}})

(def neutral-copy
  {:label "Working"
   :detail "Keeping your product moving"})

(defn presentation
  ([phase]
   (presentation phase nil))
  ([phase detail]
   (cond-> (get phase-copy phase neutral-copy)
     detail (assoc :detail detail))))

(defn accessible-label
  [{:keys [label detail]}]
  (str label ". " detail))
