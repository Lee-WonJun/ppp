(ns ppp.provider.queue
  (:require [ppp.provider.core :as provider])
  (:import (java.time Instant)
           (java.util.concurrent ArrayBlockingQueue Callable RejectedExecutionException
                                 ScheduledThreadPoolExecutor ThreadFactory ThreadPoolExecutor
                                 ThreadPoolExecutor$AbortPolicy TimeUnit)
           (java.util.concurrent.atomic AtomicLong)))

(defn- daemon-thread-factory
  [prefix]
  (let [sequence (AtomicLong.)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable (str prefix "-" (.incrementAndGet sequence)))
          (.setDaemon true))))))

(defrecord ProviderQueue [^ThreadPoolExecutor executor
                          ^ScheduledThreadPoolExecutor scheduler
                          jobs
                          handles
                          timeout-ms
                          closed?])

(defn create-queue
  [{:keys [queue-capacity provider-timeout-ms]
    :or {queue-capacity 8 provider-timeout-ms 120000}}]
  (let [waiting (ArrayBlockingQueue. queue-capacity true)
        executor (ThreadPoolExecutor. 1 1 0 TimeUnit/MILLISECONDS waiting
                                      (daemon-thread-factory "ppp-provider")
                                      (ThreadPoolExecutor$AbortPolicy.))
        scheduler (ScheduledThreadPoolExecutor.
                   1 (daemon-thread-factory "ppp-provider-timeout"))]
    (.setRemoveOnCancelPolicy scheduler true)
    (->ProviderQueue executor scheduler (atom {}) (atom {})
                     provider-timeout-ms (atom false))))

(defn- update-job!
  [queue job-id updates]
  (swap! (:jobs queue) update job-id merge updates))

(defn submit!
  [^ProviderQueue queue session-id thunk]
  (when @(:closed? queue)
    (throw (provider/error :provider/queue-closed
                           "The provider queue is closed")))
  (let [job-id (random-uuid)
        completion (promise)
        settled? (atom false)
        timeout-ref (atom nil)
        callable
        (reify Callable
          (call [_]
            (update-job! queue job-id {:status :running :started-at (Instant/now)})
            (try
              (let [value (thunk)]
                (when (compare-and-set! settled? false true)
                  (deliver completion {:status :completed :value value})
                  (update-job! queue job-id {:status :completed
                                             :completed-at (Instant/now)}))
                value)
              (catch Throwable cause
                (when (compare-and-set! settled? false true)
                  (deliver completion {:status :failed :error cause})
                  (update-job! queue job-id {:status :failed
                                             :error-code (:code (ex-data cause))
                                             :completed-at (Instant/now)}))
                nil)
              (finally
                (when-let [timeout @timeout-ref]
                  (.cancel timeout false))))))
        metadata {:job-id job-id
                  :session-id session-id
                  :status :queued
                  :submitted-at (Instant/now)}]
    (try
      (swap! (:jobs queue) assoc job-id metadata)
      (let [future (.submit (:executor queue) ^Callable callable)
            timeout (.schedule
                     (:scheduler queue)
                     ^Runnable
                     (fn []
                       (let [timeout-error
                             (provider/error :provider/timeout
                                             "Provider work exceeded its timeout")]
                         (when (compare-and-set! settled? false true)
                           (deliver completion {:status :failed
                                                :error timeout-error})
                           (update-job! queue job-id
                                        {:status :timed-out
                                         :error-code :provider/timeout
                                         :completed-at (Instant/now)})
                           (.cancel future true))))
                     (long (:timeout-ms queue))
                     TimeUnit/MILLISECONDS)]
        (reset! timeout-ref timeout)
        (when @settled?
          (.cancel timeout false))
        (swap! (:handles queue) assoc job-id {:future future
                                              :timeout timeout
                                              :completion completion
                                              :settled? settled?})
        {:job-id job-id
         :session-id session-id
         :position (.size (.getQueue (:executor queue)))
         :completion completion})
      (catch RejectedExecutionException _
        (swap! (:jobs queue) dissoc job-id)
        (throw (provider/error :provider/queue-full
                               "The provider queue already has eight waiting jobs"))))))

(defn await!
  ([submission]
   (await! submission 125000))
  ([{:keys [completion]} timeout-ms]
   (let [outcome (deref completion timeout-ms ::wait-timeout)]
     (when (= ::wait-timeout outcome)
       (throw (provider/error :provider/wait-timeout
                              "Waiting for provider completion timed out")))
     (if (= :completed (:status outcome))
       (:value outcome)
       (throw (:error outcome))))))

(defn job-status
  [queue job-id]
  (get @(:jobs queue) job-id))

(defn stats
  [^ProviderQueue queue]
  {:closed? @(:closed? queue)
   :active (.getActiveCount (:executor queue))
   :queued (.size (.getQueue (:executor queue)))
   :capacity (+ (.size (.getQueue (:executor queue)))
                (.remainingCapacity (.getQueue (:executor queue))))
   :statuses (frequencies (map :status (vals @(:jobs queue))))})

(defn stop!
  [^ProviderQueue queue]
  (when (compare-and-set! (:closed? queue) false true)
    (let [stopped (provider/error :provider/queue-closed
                                  "The provider queue stopped before this job completed")]
      (doseq [[job-id {:keys [future timeout completion settled?]}] @(:handles queue)]
        (.cancel timeout false)
        (.cancel future true)
        (when (compare-and-set! settled? false true)
          (deliver completion {:status :failed :error stopped})
          (update-job! queue job-id {:status :stopped
                                     :error-code :provider/queue-closed
                                     :completed-at (Instant/now)})))
      (.shutdownNow (:executor queue))
      (.shutdownNow (:scheduler queue))))
  nil)
