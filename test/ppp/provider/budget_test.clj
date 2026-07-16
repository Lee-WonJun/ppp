(ns ppp.provider.budget-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ppp.provider.budget :as budget]
            [ppp.util.fs :as fs])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-root
  []
  (Files/createTempDirectory "ppp-provider-budget"
                             (make-array FileAttribute 0)))

(defn- exception
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo cause cause)))

(defn- test-config
  [root clock overrides]
  (merge {:data-dir root
          :provider :codex
          :provider-calls-per-hour 3
          :provider-window-seconds 60
          :provider-budget-now-ms-fn #(deref clock)}
         overrides))

(deftest rolling-budget-is-persistent-and-boundary-exact
  (let [root (temp-root)
        clock (atom 100000)]
    (try
      (let [config (test-config root clock {})
            first-process (budget/create-budget config)]
        (doseq [timestamp [100000 110000 120000]]
          (reset! clock timestamp)
          (is (:counted? (budget/acquire! first-process))))
        (let [full (budget/status first-process)]
          (is (false? (:available? full)))
          (is (= 3 (:used full)))
          (is (= 40 (:retry-after-seconds full))))

        (testing "a restart reads the same accepted starts"
          (let [second-process (budget/create-budget config)]
            (is (= (select-keys (budget/status first-process)
                                [:available? :used :remaining
                                 :retry-after-seconds])
                   (select-keys (budget/status second-process)
                                [:available? :used :remaining
                                 :retry-after-seconds])))))

        (testing "the oldest start expires exactly at its rolling boundary"
          (reset! clock 159999)
          (is (false? (:available? (budget/status first-process))))
          (reset! clock 160000)
          (is (:available? (budget/status first-process)))
          (is (= 2 (:used (budget/status first-process))))
          (is (:counted? (budget/acquire! first-process)))
          (is (= 3 (:used (budget/status first-process)))))

        (testing "reset is explicit and durable"
          (is (= 0 (:used (budget/reset-ledger! first-process))))
          (is (= 0 (:used (budget/status (budget/create-budget config)))))))
      (finally
        (fs/delete-tree! root)))))

(deftest fake-provider-never-creates-or-consumes-a-ledger
  (let [root (temp-root)
        clock (atom 0)]
    (try
      (let [provider-budget
            (budget/create-budget (assoc (test-config root clock {})
                                         :provider :fake))]
        (dotimes [_ 200]
          (is (false? (:counted? (budget/acquire! provider-budget)))))
        (is (= {:available? true} (budget/public-status provider-budget)))
        (is (not (fs/exists? (.resolve root "kernel/provider-starts.edn")))))
      (finally
        (fs/delete-tree! root)))))

(deftest corrupt-or-unbounded-ledger-fails-closed
  (let [root (temp-root)
        clock (atom 0)
        config (test-config root clock {})
        ledger (.resolve root "kernel/provider-starts.edn")]
    (try
      (fs/ensure-dir! (.getParent ledger))
      (fs/atomic-write-string! ledger "{:format-version 1 :starts [:not-a-time]}\n")
      (is (= :provider/budget-state-invalid
             (:code (ex-data (exception #(budget/create-budget config))))))
      (fs/atomic-write-string! ledger (apply str (repeat 70000 "x")))
      (is (= :provider/budget-state-invalid
             (:code (ex-data (exception #(budget/create-budget config))))))
      (finally
        (fs/delete-tree! root)))))

(deftest owner-inspection-is-read-only-and-sees-another-process-start
  (let [root (temp-root)
        clock (atom 100000)
        config (test-config root clock {})]
    (try
      (let [inspector (budget/create-budget config)
            writer (budget/create-budget config)
            ledger (.resolve root "kernel/provider-starts.edn")]
        (budget/acquire! writer)
        (let [before (fs/read-edn ledger)
              inspected (budget/inspect-status inspector)
              after (fs/read-edn ledger)]
          (is (= 1 (:used inspected)))
          (is (= 2 (:remaining inspected)))
          (is (= before after))
          (is (= [] @(:starts inspector)))))
      (finally
        (fs/delete-tree! root)))))

(deftest rolling-window-property
  (let [root (temp-root)]
    (try
      (let [result
            (tc/quick-check
             1000
             (prop/for-all
              [limit (gen/choose 1 20)
               window-seconds (gen/choose 60 7200)
               offsets (gen/vector (gen/choose -10000 10000) 0 60)]
              (let [clock (atom 100000000)
                    config (test-config root clock
                                        {:provider-calls-per-hour limit
                                         :provider-window-seconds window-seconds})
                    provider-budget (budget/create-budget config)]
                (budget/reset-ledger! provider-budget)
                (let [initial @clock
                      timestamps (sort (map #(+ initial (* 1000 %)) offsets))
                      attempts (filter #(<= % initial) timestamps)
                      final-model
                      (reduce
                       (fn [{:keys [accepted valid?]} timestamp]
                         (let [cutoff (- timestamp (* 1000 window-seconds))
                               retained (vec (filter #(> % cutoff) accepted))
                               expected? (< (count retained) limit)]
                           (reset! clock timestamp)
                           (let [actual? (nil? (exception
                                                #(budget/acquire!
                                                  provider-budget)))]
                             {:accepted (cond-> retained expected? (conj timestamp))
                              :valid? (and valid? (= expected? actual?))})))
                       {:accepted [] :valid? true}
                       attempts)
                      state (budget/status provider-budget)
                      used (count (:accepted final-model))]
                  (and (:valid? final-model)
                       (<= used limit)
                       (= used (:used state))
                       (= (< used limit) (:available? state))))))
             :seed 22016)]
        (is (:pass? result) (pr-str (dissoc result :result-data))))
      (finally
        (fs/delete-tree! root)))))
