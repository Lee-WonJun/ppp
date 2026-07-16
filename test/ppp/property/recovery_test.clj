(ns ppp.property.recovery-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ppp.session.store :as store]))

(def state-gen
  (gen/elements [:base :target :other :invalid]))

(defn- state-version
  [state base target]
  (case state
    :base base
    :target target
    :other (+ target 1)
    :invalid nil))

(deftest recovery-decision-is-total-deterministic-and-idempotent
  (let [result
        (tc/quick-check
         1000
         (prop/for-all
          [base-version (gen/choose 0 1000000)
           manifest-state state-gen
           database-state state-gen]
          (let [target-version (inc base-version)
                manifest-version (state-version manifest-state base-version target-version)
                database-version (state-version database-state base-version target-version)
                decision (store/recovery-decision base-version target-version
                                                  manifest-version database-version)
                expected (cond
                           (and (= :target manifest-state)
                                (= :target database-state)) :finalize
                           (and (= :base manifest-state)
                                (= :base database-state)) :abandon
                           :else :rollback)]
            (and (= expected decision)
                 (= decision
                    (store/recovery-decision base-version target-version
                                             manifest-version database-version)))))
         :seed 8004)]
    (is (= 1000 (:num-tests result)))
    (is (:pass? result) (pr-str (dissoc result :result-data)))))
