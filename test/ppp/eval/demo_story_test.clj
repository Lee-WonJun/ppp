(ns ppp.eval.demo-story-test
  (:require [clojure.test :refer [deftest is testing]]
            [ppp.eval.demo-story :as demo-story]))

(defn- observation
  [scenario before]
  {:scenario scenario
   :before-version before
   :after-version (inc before)
   :duration-ms 42
   :browser-outcome true
   :client-stage-valid true
   :outcomes {:visible true}})

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
  [version thread-id]
  {:kind :change
   :runtime-version version
   :runtime-impact :server-data
   :provider-thread-id thread-id
   :generation-attempts 2
   :changes {:writes [{:path "src/server/runtime/server.clj" :content "hidden"}
                      {:path "test/runtime/domain_test.cljc" :content "hidden"}
                      {:path "src/client/runtime/client.cljs" :content "hidden"}]
             :deletes []
             :migrations [{:name "feature" :sql "hidden"}]}
   :validation {:source :passed
                :impact :server-data
                :server :passed
                :client :passed
                :domain-tests :passed
                :sql :passed}})

(defn- passing-input
  []
  (let [thread-id "11111111-1111-4111-8111-111111111111"]
    {:model "test-model"
     :observations
     {:records (mapv observation demo-story/scenario-order (range 6))}
     :events [(client-event 1 thread-id)
              (server-event 2 thread-id)
              (client-event 3 thread-id)
              (server-event 4 thread-id)
              (client-event 5 thread-id)
              (client-event 6 thread-id)]
     :database {:user-table-count 2 :logical-hash "bounded-hash"}}))

(deftest report-requires-the-complete-real-codex-story
  (let [report (demo-story/build-report (passing-input))]
    (is (demo-story/report-passes? report))
    (is (= 6 (:record-count report)))
    (is (= :passed (:thread-continuity report)))
    (is (= 6 (:passed report)))
    (is (every? :passed? (:records report)))
    (is (not-any? #(contains? (first (:records report)) %)
                  [:prompt :assistant :source :changes
                   :provider-thread-id]))))

(deftest report-fails-closed-on-fake-client-only-or-thread-drift
  (let [input (passing-input)]
    (testing "account creation cannot be reported as a client-only turn"
      (let [broken (-> input
                       (assoc-in [:events 1 :runtime-impact] :client-only)
                       (assoc-in [:events 1 :validation :impact] :client-only))
            report (demo-story/build-report broken)]
        (is (not (demo-story/report-passes? report)))
        (is (false? (get-in report [:records 1 :gates :impact])))))
    (testing "both server-data turns require a migration"
      (let [broken (assoc-in input [:events 3 :changes :migrations] [])
            report (demo-story/build-report broken)]
        (is (not (demo-story/report-passes? report)))
        (is (false? (get-in report [:records 3 :gates :migration-policy])))))
    (testing "all accepted changes must share one resumed thread"
      (let [broken (assoc-in input [:events 5 :provider-thread-id]
                             "22222222-2222-4222-8222-222222222222")
            report (demo-story/build-report broken)]
        (is (not (demo-story/report-passes? report)))
        (is (= :failed (:thread-continuity report)))))))
