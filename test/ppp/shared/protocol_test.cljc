(ns ppp.shared.protocol-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ppp.shared.protocol :as protocol]))

(def valid-reply
  {:kind :reply
   :assistant-message "The current product is unchanged."
   :clarification-question nil
   :restore-version nil
   :change nil})

(def valid-change
  {:title "Add voting"
   :writes [{:path "src/shared/runtime/domain.cljc"
             :content "(ns runtime.domain)"}]
   :deletes []
   :migrations []})

(deftest provider-result-contract
  (testing "all four kinds use one stable schema"
    (is (protocol/valid-provider-result? valid-reply))
    (is (protocol/valid-provider-result?
         (assoc valid-reply
                :kind :clarify
                :clarification-question "Which outcome matters?")))
    (is (protocol/valid-provider-result?
         (assoc valid-reply :kind :restore :restore-version 0)))
    (is (protocol/valid-provider-result?
         (assoc valid-reply
                :kind :change
                :change valid-change))))
  (testing "malformed or unknown results fail"
    (is (not (protocol/valid-provider-result? (dissoc valid-reply :kind))))
    (is (not (protocol/valid-provider-result? (assoc valid-reply :kind :execute))))
    (is (not (protocol/valid-provider-result?
              (assoc valid-reply :kind :change :change {:title "missing fields"}))))))

(deftest provider-kind-boundary-property
  (let [result
        (tc/quick-check
         1000
         (prop/for-all [kind (gen/elements [:reply :clarify :change :restore])
                        suffix gen/string-alphanumeric
                        version (gen/choose 0 100000)]
                       (let [base (assoc valid-reply
                                         :assistant-message (str "Outcome " suffix))
                             value (case kind
                                     :reply base
                                     :clarify (assoc base
                                                     :kind :clarify
                                                     :clarification-question
                                                     (str "Decision " suffix "?"))
                                     :change (assoc base :kind :change :change valid-change)
                                     :restore (assoc base
                                                     :kind :restore
                                                     :restore-version version))
                             contradictory
                             (case kind
                               :reply (assoc value :change valid-change)
                               :clarify (assoc value :clarification-question nil)
                               :change (assoc value :change nil)
                               :restore (assoc value :restore-version nil))]
                         (and (protocol/valid-provider-result? value)
                              (not (protocol/valid-provider-result?
                                    (dissoc value :assistant-message)))
                              (not (protocol/valid-provider-result? contradictory))))))]
    (is (:pass? result) (pr-str (dissoc result :result-data)))))

(deftest websocket-envelope-contract
  (let [value (protocol/envelope
               {:session-id (random-uuid)
                :request-id (random-uuid)
                :runtime-version 0
                :type :session/subscribe
                :payload {:tab-id (random-uuid)}})]
    (is (protocol/valid-envelope? value))
    (is (not (protocol/valid-envelope? (assoc value :protocol-version 2))))
    (is (not (protocol/valid-envelope? (assoc value :workspace-id "other"))))))

(deftest browser-rejection-details-are-bounded
  (let [base
        (protocol/envelope
         {:session-id (random-uuid)
          :request-id (random-uuid)
          :runtime-version 1
          :type :runtime/rejected
          :payload {:tab-id (random-uuid)
                    :transaction-id (random-uuid)
                    :base-version 0
                    :target-version 1
                    :code :runtime/client-stage-failed}})]
    (is (protocol/valid-client-envelope?
         (assoc-in base [:payload :details]
                   ["Generated client code failed staging"
                    "Unable to resolve symbol: js/parent"])))
    (is (not (protocol/valid-client-envelope?
              (assoc-in base [:payload :details] (vec (repeat 5 "error"))))))
    (is (not (protocol/valid-client-envelope?
              (assoc-in base [:payload :details] [(apply str (repeat 241 "x"))]))))))

(def diagnostic-shape-generator
  (gen/let [kind (gen/one-of [(gen/elements [:action :runtime :console :network
                                             :unknown])
                              gen/string-alphanumeric
                              gen/int])
            action-id gen/string
            code gen/string
            status gen/int
            level (gen/one-of [(gen/elements [:warn :error :info])
                               gen/string-alphanumeric])
            method gen/string
            url gen/string
            message gen/string]
    {:kind kind
     :actionId action-id
     :code code
     :status status
     :level level
     :method method
     :url url
     :message message
     :ignored {:arbitrary true}}))

(defn- bounded-one-line?
  [value limit]
  (or (nil? value)
      (and (string? value)
           (<= (count value) limit)
           (not (re-find #"[\r\n\t]" value)))))

(defn- safe-diagnostic?
  [value]
  (and (map? value)
       (contains? #{:action :runtime :console :network} (:kind value))
       (bounded-one-line? (:message value) 240)
       (bounded-one-line? (:action-id value) 96)
       (bounded-one-line? (:code value) 96)
       (bounded-one-line? (:url value) 160)
       (not (contains? value :ignored))
       (not (some #(str/includes? (or (:url value) "") %) ["?" "#"]))
       (every? #{:kind :action-id :code :status :level :method :url :message}
               (keys value))))

(deftest client-diagnostic-boundary-property
  (let [result
        (tc/quick-check
         1000
         (prop/for-all [records (gen/vector diagnostic-shape-generator 0 16)]
                       (let [normalized (protocol/normalize-client-diagnostics records)]
                         (if (nil? normalized)
                           true
                           (and (<= (count normalized) protocol/max-client-diagnostics)
                                (= (count normalized) (count (distinct normalized)))
                                (every? safe-diagnostic? normalized)))))
         :seed 23018)]
    (is (:pass? result) (pr-str (dissoc result :result-data)))))

(deftest client-diagnostics-redact-and-minimize-evidence
  (let [diagnostic
        (protocol/normalize-client-diagnostic
         {:kind "network"
          :method "post"
          :url "https://api.example.test/accounts?token=do-not-copy#member"
          :status 400
          :message (str "Authorization: Bearer private-value\n"
                        "email owner@example.test password=hunter2")})]
    (is (= :network (:kind diagnostic)))
    (is (= "POST" (:method diagnostic)))
    (is (= "https://api.example.test/accounts" (:url diagnostic)))
    (is (= 400 (:status diagnostic)))
    (is (str/includes? (:message diagnostic) "[redacted]"))
    (is (str/includes? (:message diagnostic) "[redacted-email]"))
    (is (not (str/includes? (:message diagnostic) "private-value")))
    (is (not (str/includes? (:message diagnostic) "hunter2")))
    (is (not (str/includes? (:message diagnostic) "owner@example.test"))))
  (is (nil? (protocol/normalize-client-diagnostics
             (vec (repeat (inc protocol/max-client-diagnostics)
                          {:kind :runtime :message "failure"})))))
  (is (nil? (protocol/normalize-client-diagnostic
             {:kind :network
              :method "GET"
              :url "https://user:password@example.test/private"
              :status 500})))
  (let [same {:kind :console :level :warn :message "same"}
        ring (reduce protocol/append-client-diagnostic []
                     (concat [same same]
                             (map (fn [index]
                                    {:kind :runtime
                                     :message (str "failure-" index)})
                                  (range 20))))]
    (is (= protocol/max-client-diagnostics (count ring)))
    (is (= (count ring) (count (distinct ring))))
    (is (= "failure-19" (:message (last ring))))))
