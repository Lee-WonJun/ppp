(ns ppp.provider.budget
  (:require [ppp.provider.core :as provider]
            [ppp.util.fs :as fs])
  (:import (java.nio.file Files LinkOption Path)
           (java.util.concurrent.locks ReentrantLock)))

(def ^:private ledger-format-version 1)
(def ^:private max-ledger-bytes (* 64 1024))
(def ^:private no-links (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defrecord ProviderBudget [enabled? limit window-ms data-root ^Path path starts
                           ^ReentrantLock lock now-ms-fn])

(defn- now-ms
  [^ProviderBudget budget]
  (long ((:now-ms-fn budget))))

(defn- retained-starts
  [starts current-ms window-ms]
  (let [cutoff (- current-ms window-ms)]
    (->> starts
         (filter #(> (long %) cutoff))
         sort
         vec)))

(defn- valid-ledger?
  [value]
  (and (map? value)
       (= ledger-format-version (:format-version value))
       (vector? (:starts value))
       (<= (count (:starts value)) 10000)
       (every? #(and (integer? %) (not (neg? %))) (:starts value))))

(defn- assert-ledger-path!
  [^ProviderBudget budget]
  (let [candidate (fs/safe-child (:data-root budget)
                                 "kernel/provider-starts.edn")]
    (when-not (= (.normalize (.toAbsolutePath ^Path (:path budget)))
                 (.normalize (.toAbsolutePath ^Path candidate)))
      (throw (provider/error :provider/budget-state-invalid
                             "The provider capacity ledger path changed")))
    candidate))

(defn- read-ledger!
  [^Path path]
  (if-not (fs/exists? path)
    []
    (do
      (when (or (fs/symbolic-link? path)
                (not (Files/isRegularFile path no-links))
                (> (Files/size path) max-ledger-bytes))
        (throw (provider/error :provider/budget-state-invalid
                               "The provider capacity ledger is not a bounded regular file")))
      (let [value (try
                    (fs/read-edn path)
                    (catch Exception cause
                      (throw (provider/error
                              :provider/budget-state-invalid
                              "The provider capacity ledger could not be read"
                              {:cause-class (.getName (class cause))}))))]
        (when-not (valid-ledger? value)
          (throw (provider/error :provider/budget-state-invalid
                                 "The provider capacity ledger is invalid")))
        (:starts value)))))

(defn- persist!
  [^ProviderBudget budget starts]
  (let [path (assert-ledger-path! budget)]
    (when (fs/symbolic-link? path)
      (throw (provider/error :provider/budget-state-invalid
                             "The provider capacity ledger cannot be a symbolic link")))
    (fs/atomic-write-edn! path {:format-version ledger-format-version
                                :starts (vec starts)})))

(defn create-budget
  [{:keys [data-dir provider provider-calls-per-hour provider-window-seconds
           provider-budget-now-ms-fn]}]
  (let [data-root ^Path data-dir
        kernel-root (fs/safe-child data-root "kernel")
        _ (fs/ensure-dir! kernel-root)
        _ (fs/assert-no-symlinks! kernel-root)
        path (fs/safe-child data-root "kernel/provider-starts.edn")
        enabled? (= :codex provider)
        budget (->ProviderBudget enabled?
                                 (long (or provider-calls-per-hour 100))
                                 (* 1000 (long (or provider-window-seconds 3600)))
                                 data-root path (atom []) (ReentrantLock. true)
                                 (or provider-budget-now-ms-fn
                                     #(System/currentTimeMillis)))
        starts (if enabled? (read-ledger! path) [])]
    (reset! (:starts budget)
            (retained-starts starts (now-ms budget) (:window-ms budget)))
    budget))

(defn- with-budget-lock
  [^ProviderBudget budget thunk]
  (let [^ReentrantLock lock (:lock budget)]
    (.lock lock)
    (try
      (thunk)
      (finally
        (.unlock lock)))))

(defn- status-from
  [^ProviderBudget budget starts current-ms]
  (if-not (:enabled? budget)
    {:enabled? false
     :available? true
     :limit (:limit budget)
     :used 0
     :remaining (:limit budget)
     :retry-after-seconds 0}
    (let [used (count starts)
          available? (< used (:limit budget))
          wait-ms (when-not available?
                    (max 1 (- (+ (long (first starts)) (:window-ms budget))
                              current-ms)))]
      {:enabled? true
       :available? available?
       :limit (:limit budget)
       :used used
       :remaining (max 0 (- (:limit budget) used))
       :retry-after-seconds
       (if wait-ms
         (max 1 (long (Math/ceil (/ (double wait-ms) 1000.0))))
         0)})))

(defn status
  [^ProviderBudget budget]
  (with-budget-lock
    budget
    (fn []
      (let [current-ms (now-ms budget)
            previous @(:starts budget)
            starts (retained-starts previous current-ms (:window-ms budget))]
        (when (and (:enabled? budget) (not= previous starts))
          (persist! budget starts))
        (reset! (:starts budget) starts)
        (status-from budget starts current-ms)))))

(defn public-status
  [budget]
  (select-keys (status budget) [:available?]))

(defn inspect-status
  "Read the current on-disk ledger without pruning, persisting, or changing the
  process-local admission state. This is safe for the owner status command
  while the single application process is running."
  [^ProviderBudget budget]
  (if-not (:enabled? budget)
    (status-from budget [] (now-ms budget))
    (let [current-ms (now-ms budget)
          starts (retained-starts (read-ledger! (assert-ledger-path! budget))
                                  current-ms (:window-ms budget))]
      (status-from budget starts current-ms))))

(defn ensure-available!
  [budget]
  (let [{:keys [available? retry-after-seconds]} (status budget)]
    (when-not available?
      (throw (provider/error
              :provider/capacity-exhausted
              "The provider rolling capacity is exhausted"
              {:retry-after-seconds retry-after-seconds})))
    true))

(defn acquire!
  [^ProviderBudget budget]
  (if-not (:enabled? budget)
    {:counted? false :available? true}
    (with-budget-lock
      budget
      (fn []
        (let [current-ms (now-ms budget)
              previous @(:starts budget)
              starts (retained-starts previous current-ms
                                      (:window-ms budget))
              current-status (status-from budget starts current-ms)]
          (when-not (:available? current-status)
            (when (not= previous starts)
              (persist! budget starts))
            (reset! (:starts budget) starts)
            (throw (provider/error
                    :provider/capacity-exhausted
                    "The provider rolling capacity is exhausted"
                    {:retry-after-seconds (:retry-after-seconds current-status)})))
          (let [accepted (conj starts current-ms)]
            (persist! budget accepted)
            (reset! (:starts budget) accepted)
            (assoc (status-from budget accepted current-ms)
                   :counted? true)))))))

(defn reset-ledger!
  [^ProviderBudget budget]
  (with-budget-lock
    budget
    (fn []
      (persist! budget [])
      (reset! (:starts budget) [])
      (status-from budget [] (now-ms budget)))))
