(ns ppp.provider.queue-test
  (:require [clojure.test :refer [deftest is testing]]
            [ppp.provider.queue :as queue])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(defn- exception-code
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo cause
      (:code (ex-data cause)))))

(deftest provider-queue-is-global-fifo-with-eight-waiting-slots
  (let [provider-queue (queue/create-queue {:queue-capacity 8
                                            :provider-timeout-ms 5000})
        started (CountDownLatch. 1)
        release (CountDownLatch. 1)
        order (atom [])
        active (atom 0)
        maximum-active (atom 0)
        session-id (random-uuid)
        task (fn [index block?]
               (fn []
                 (let [current (swap! active inc)]
                   (swap! maximum-active max current)
                   (try
                     (when block?
                       (.countDown started)
                       (.await release 3 TimeUnit/SECONDS))
                     (swap! order conj index)
                     index
                     (finally
                       (swap! active dec))))))]
    (try
      (let [running (queue/submit! provider-queue session-id (task 0 true))]
        (is (.await started 2 TimeUnit/SECONDS))
        (let [waiting (mapv #(queue/submit! provider-queue session-id (task % false))
                            (range 1 9))]
          (is (= 8 (:queued (queue/stats provider-queue))))
          (is (= :provider/queue-full
                 (exception-code
                  #(queue/submit! provider-queue session-id (task 9 false)))))
          (.countDown release)
          (is (= 0 (queue/await! running 5000)))
          (is (= (vec (range 1 9))
                 (mapv #(queue/await! % 5000) waiting)))
          (is (= (vec (range 9)) @order))
          (is (= 1 @maximum-active))
          (is (= 0 (:active (queue/stats provider-queue))))))
      (finally
        (.countDown release)
        (queue/stop! provider-queue)))))

(deftest queue-timeout-and-stop-have-stable-failures
  (testing "timeout interrupts a blocking provider task"
    (let [provider-queue (queue/create-queue {:queue-capacity 8
                                              :provider-timeout-ms 50})]
      (try
        (let [submission (queue/submit! provider-queue (random-uuid)
                                        #(Thread/sleep 1000))]
          (is (= :provider/timeout
                 (exception-code #(queue/await! submission 1000))))
          (is (= :timed-out
                 (:status (queue/job-status provider-queue (:job-id submission))))))
        (finally
          (queue/stop! provider-queue)))))
  (testing "closed queues reject new work"
    (let [provider-queue (queue/create-queue {})]
      (queue/stop! provider-queue)
      (is (= :provider/queue-closed
             (exception-code #(queue/submit! provider-queue (random-uuid) identity)))))))

(deftest observable-job-metadata-never-retains-provider-input
  (let [provider-queue (queue/create-queue {})]
    (try
      (let [submission (queue/submit! provider-queue (random-uuid)
                                      #(identity "TOP_SECRET_PROMPT_AND_SOURCE"))]
        (is (= "TOP_SECRET_PROMPT_AND_SOURCE" (queue/await! submission 1000)))
        (let [metadata (queue/job-status provider-queue (:job-id submission))]
          (is (= #{:job-id :session-id :status :submitted-at :started-at :completed-at}
                 (set (keys metadata))))
          (is (not (re-find #"TOP_SECRET" (pr-str metadata))))))
      (finally
        (queue/stop! provider-queue)))))
