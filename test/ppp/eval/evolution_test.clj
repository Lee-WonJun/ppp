(ns ppp.eval.evolution-test
  (:require [clojure.test :refer [deftest is testing]]
            [ppp.eval.evolution :as evolution]))

(defn- observation
  [scenario before]
  {:scenario scenario
   :before-version before
   :after-version (inc before)
   :duration-ms 42
   :browser-outcome true
   :client-stage-valid true})

(defn- client-event
  [version thread-id]
  {:kind :change
   :runtime-version version
   :runtime-impact :client-only
   :provider-thread-id thread-id
   :generation-attempts 1
   :changes {:writes [{:path "src/client/runtime/client.cljs" :content "hidden"}
                      {:path "styles/runtime.css" :content "hidden"}]
             :deletes []
             :migrations []}
   :validation {:source :passed
                :impact :client-only
                :server :not-applicable
                :client :passed
                :domain-tests :not-applicable
                :sql :not-applicable}})

(defn- server-event
  [version thread-id migration?]
  {:kind :change
   :runtime-version version
   :runtime-impact :server-data
   :provider-thread-id thread-id
   :generation-attempts 2
   :changes {:writes [{:path "src/server/runtime/server.clj" :content "hidden"}
                      {:path "src/shared/runtime/domain.cljc" :content "hidden"}
                      {:path "test/runtime/domain_test.cljc" :content "hidden"}
                      {:path "src/client/runtime/client.cljs" :content "hidden"}]
             :deletes []
             :migrations (if migration?
                           [{:name "ranking" :sql "hidden"}]
                           [])}
   :validation {:source :passed
                :impact :server-data
                :server :passed
                :client :passed
                :domain-tests :passed
                :sql (if migration? :passed :not-applicable)}})

(defn- passing-input
  []
  (let [thread-id "11111111-1111-4111-8111-111111111111"
        observations {:records (mapv observation evolution/scenario-order (range 6))}
        events [(client-event 1 thread-id)
                (client-event 2 thread-id)
                (client-event 3 thread-id)
                (server-event 4 thread-id true)
                (server-event 5 thread-id false)
                (client-event 6 thread-id)]]
    {:model "test-model"
     :observations observations
     :events events
     :database {:user-table-count 1 :logical-hash "bounded-hash"}}))

(deftest evolution-report-requires-the-complete-realistic-sequence
  (let [report (evolution/build-report (passing-input))]
    (is (evolution/report-passes? report))
    (is (= 6 (:record-count report)))
    (is (= :passed (:thread-continuity report)))
    (is (= 6 (:passed report)))
    (is (every? :passed? (:records report)))
    (is (not-any? #(contains? (first (:records report)) %)
                  [:prompt :assistant :source :changes :provider-thread-id]))))

(deftest evolution-report-fails-closed-on-surface-or-thread-drift
  (let [input (passing-input)]
    (testing "client-only work cannot smuggle a server write"
      (let [broken (update-in input [:events 2 :changes :writes]
                              conj {:path "src/server/runtime/server.clj"
                                    :content "hidden"})
            report (evolution/build-report broken)]
        (is (not (evolution/report-passes? report)))
        (is (false? (get-in report [:records 2 :gates :surfaces])))))
    (testing "server-data work cannot omit generated domain tests"
      (let [broken (assoc-in input [:events 4 :validation :domain-tests]
                             :not-applicable)
            report (evolution/build-report broken)]
        (is (not (evolution/report-passes? report)))
        (is (false? (get-in report [:records 4 :gates :server-stage])))))
    (testing "all turns must resume one Codex thread"
      (let [broken (assoc-in input [:events 5 :provider-thread-id]
                             "22222222-2222-4222-8222-222222222222")
            report (evolution/build-report broken)]
        (is (not (evolution/report-passes? report)))
        (is (= :failed (:thread-continuity report)))))
    (testing "a missing final observation is counted as a failure"
      (let [broken (-> input
                       (update-in [:observations :records] pop)
                       (update :events pop))
            report (evolution/build-report broken)]
        (is (not (evolution/report-passes? report)))
        (is (= 5 (:record-count report)))
        (is (= 1 (:failed report)))))))
