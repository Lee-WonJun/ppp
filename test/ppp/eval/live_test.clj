(ns ppp.eval.live-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [ppp.eval.live :as live]
            [ppp.util.fs :as fs])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def successful-change
  {:kind :change
   :runtime-version 1
   :runtime-impact :server-data
   :generation-attempts 1
   :validation {:source :passed :server :passed :client :passed :sql :passed}})

(defn observation
  [scenario before after]
  {:scenario scenario
   :before-version before
   :after-version after
   :duration-ms 42
   :browser-status "completed"
   :browser-outcome true
   :client-stage-valid true
   :state-preserved true})

(deftest record-evaluation-is-sanitized-and-stage-aware
  (let [record (live/evaluate-record 1
                                     (observation "LIVE-03" 0 1)
                                     successful-change)]
    (is (:passed? record))
    (is (= :passed (:provider-schema record)))
    (is (= :passed (:source-security record)))
    (is (= :passed (:server-stage record)))
    (is (= :passed (:client-stage record)))
    (is (= :server-data (:runtime-impact record)))
    (is (= 1 (:generation-attempts record)))
    (is (not-any? #(contains? record %)
                  [:prompt :source :assistant :changes :validation]))))

(deftest client-only-live-record-does-not-require-a-server-stage
  (let [record (live/evaluate-record
                1
                (observation "LIVE-03" 0 1)
                {:kind :change
                 :runtime-version 1
                 :runtime-impact :client-only
                 :generation-attempts 2
                 :validation {:source :passed
                              :impact :client-only
                              :server :not-applicable
                              :client :passed
                              :domain-tests :not-applicable
                              :sql :not-applicable}})]
    (is (:passed? record))
    (is (= :not-applicable (:server-stage record)))
    (is (= 2 (:generation-attempts record)))))

(deftest safety-rejection-passes-only-when-state-is-preserved
  (let [event {:kind :rejected
               :runtime-version 4
               :error-code :source/validation-failed}
        accepted (live/evaluate-record 2
                                       (observation "LIVE-07" 4 4)
                                       event)
        mutated (live/evaluate-record 2
                                      (observation "LIVE-07" 4 5)
                                      event)]
    (is (:passed? accepted))
    (is (= :not-applicable (:client-stage accepted)))
    (is (not (:passed? mutated)))))

(deftest report-gate-requires-all-eight-scenarios-three-times
  (let [event-for
        (fn [scenario]
          (case scenario
            "LIVE-01" {:kind :reply}
            "LIVE-02" {:kind :clarify}
            "LIVE-06" {:kind :restore
                       :validation {:checkpoint :passed :source :passed
                                    :server :passed :client :passed}}
            (if (contains? #{"LIVE-07" "LIVE-08"} scenario)
              {:kind :reply}
              successful-change)))
        observations
        {:runs
         (mapv (fn [run]
                 {:run run
                  :session-id (str "session-" run)
                  :records
                  (mapv (fn [scenario]
                          (observation scenario
                                       (if (contains? #{"LIVE-01" "LIVE-02"
                                                        "LIVE-07" "LIVE-08"}
                                                      scenario)
                                         0
                                         (dec run))
                                       (if (contains? #{"LIVE-01" "LIVE-02"
                                                        "LIVE-07" "LIVE-08"}
                                                      scenario)
                                         0
                                         run)))
                        live/scenario-order)})
               (range 1 4))}
        events
        (into {} (map (fn [{:keys [session-id]}]
                        [session-id (mapv event-for live/scenario-order)]))
              (:runs observations))
        report (live/build-report {:model "test-model"
                                   :observations observations
                                   :events-by-session events
                                   :database-by-session {}})]
    (testing "the release gate counts exactly 24 sanitized records"
      (is (= 24 (:record-count report)))
      (is (zero? (:failed report)))
      (is (live/report-passes? report)))
    (testing "one missing history event fails closed"
      (let [broken (live/build-report
                    {:model "test-model"
                     :observations observations
                     :events-by-session (update events "session-3" pop)
                     :database-by-session {}})]
        (is (not (live/report-passes? broken)))
        (is (= 1 (:failed broken)))))))

(deftest disk-report-loads-validation-from-the-canonical-sibling-file
  (let [root (Files/createTempDirectory "ppp-live-report"
                                        (make-array FileAttribute 0))
        session-id (str (random-uuid))
        history-root (-> root
                         (.resolve "workspaces/local/sessions")
                         (.resolve session-id)
                         (.resolve "history/000001-change"))
        observations-path (.resolve root "observations.json")
        output-root (.resolve root "report")]
    (try
      (fs/ensure-dir! history-root)
      (fs/atomic-write-edn! (.resolve history-root "event.edn")
                            {:event-sequence 1
                             :kind :change
                             :runtime-version 1})
      (fs/atomic-write-edn! (.resolve history-root "validation.edn")
                            {:source :passed
                             :server :passed
                             :client :passed
                             :sql :passed})
      (fs/atomic-write-string!
       observations-path
       (json/write-str
        {:format-version 1
         :runs [{:run 1
                 :session-id session-id
                 :records [(observation "LIVE-03" 0 1)]}]}))
      (let [report (:report (live/generate-report! root observations-path
                                                   output-root "test-model"))
            record (first (:records report))]
        (is (= :passed (:source-security record)))
        (is (= :passed (:server-stage record)))
        (is (= :passed (:client-stage record)))
        (is (:passed? record))
        (is (fs/regular-file? (.resolve output-root "report.edn"))))
      (finally
        (fs/delete-tree! root)))))
