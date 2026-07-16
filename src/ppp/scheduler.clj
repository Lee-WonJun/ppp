(ns ppp.scheduler
  (:require [ppp.coordinator :as coordinator])
  (:import (java.util.concurrent ScheduledThreadPoolExecutor ThreadFactory TimeUnit)
           (java.util.concurrent.atomic AtomicLong)))

(defrecord Scheduler [executor future closed?])

(defn- daemon-thread-factory
  []
  (let [sequence (AtomicLong.)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable
                       (str "ppp-session-job-" (.incrementAndGet sequence)))
          (.setDaemon true))))))

(defn start!
  [coordinator {:keys [job-scheduler-enabled? job-poll-interval-ms]
                :or {job-scheduler-enabled? true
                     job-poll-interval-ms 250}}]
  (let [executor (ScheduledThreadPoolExecutor. 1 (daemon-thread-factory))
        closed? (atom false)
        poll-ms (long job-poll-interval-ms)
        _ (when-not (<= 50 poll-ms 60000)
            (throw (ex-info "Job poll interval is outside the supported range"
                            {:code :scheduler/interval-invalid})))
        task (fn []
               (when-not @closed?
                 (try
                   (coordinator/run-one-due-job! coordinator)
                   ;; A generated job failure is already reduced to bounded
                   ;; durable status by the runtime. One failure must never
                   ;; terminate polling for other sessions.
                   (catch Throwable _ nil))))
        future (when job-scheduler-enabled?
                 (.scheduleWithFixedDelay executor ^Runnable task poll-ms poll-ms
                                          TimeUnit/MILLISECONDS))]
    (.setRemoveOnCancelPolicy executor true)
    (->Scheduler executor future closed?)))

(defn stop!
  [^Scheduler scheduler]
  (when (compare-and-set! (:closed? scheduler) false true)
    (when-let [future (:future scheduler)]
      (.cancel ^java.util.concurrent.Future future false))
    (.shutdownNow ^ScheduledThreadPoolExecutor (:executor scheduler)))
  nil)

(defn running?
  [^Scheduler scheduler]
  (and (not @(:closed? scheduler))
       (some? (:future scheduler))))
