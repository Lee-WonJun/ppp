(ns ppp.eval.demo-story
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [ppp.runtime.sqlite :as sqlite]
            [ppp.session.store :as store]
            [ppp.util.fs :as fs])
  (:import (java.nio.file Path Paths)
           (java.time Instant)))

(def scenario-order
  ["DEMO-01" "DEMO-02" "DEMO-03"
   "DEMO-04" "DEMO-05" "DEMO-06"])

(def expected-impact
  {"DEMO-01" :client-only
   "DEMO-02" :server-data
   "DEMO-03" :client-only
   "DEMO-04" :server-data
   "DEMO-05" :client-only
   "DEMO-06" :client-only})

(defn- history-root
  [^Path data-root session-id]
  (-> data-root
      (.resolve "workspaces/local/sessions")
      (.resolve (str (store/parse-session-id session-id)))
      (.resolve "history")))

(defn history-events
  [^Path data-root session-id]
  (let [root (history-root data-root session-id)]
    (if-not (fs/exists? root)
      []
      (->> (fs/list-tree root)
           (filter #(= "event.edn" (str (.getFileName ^Path %))))
           (mapv
            (fn [^Path event-path]
              (let [directory (.getParent event-path)
                    validation-path (.resolve directory "validation.edn")
                    changes-path (.resolve directory "changes.edn")]
                (cond-> (fs/read-edn event-path)
                  (fs/regular-file? validation-path)
                  (assoc :validation (fs/read-edn validation-path))

                  (fs/regular-file? changes-path)
                  (assoc :changes (fs/read-edn changes-path))))))
           (sort-by :event-sequence)
           vec))))

(defn- surface-for-path
  [path]
  (cond
    (str/starts-with? path "src/client/") :client
    (str/starts-with? path "styles/") :style
    (str/starts-with? path "src/server/") :server
    (str/starts-with? path "src/shared/") :shared
    (str/starts-with? path "test/") :test
    :else :other))

(defn change-surfaces
  [{:keys [writes deletes migrations]}]
  (let [paths (concat (map :path writes) deletes)
        counts (frequencies (map surface-for-path paths))]
    {:client (get counts :client 0)
     :style (get counts :style 0)
     :server (get counts :server 0)
     :shared (get counts :shared 0)
     :test (get counts :test 0)
     :other (get counts :other 0)
     :migrations (count migrations)}))

(defn- add-surfaces
  [left right]
  (merge-with + left right))

(defn- client-only-surfaces?
  [{:keys [server shared test other migrations]}]
  (zero? (+ server shared test other migrations)))

(defn- initial-surfaces-valid?
  [impact {:keys [server test other] :as surfaces}]
  (case impact
    :client-only (client-only-surfaces? surfaces)
    :server-data (and (pos? server) (pos? test) (zero? other))
    false))

(defn- repair-surfaces-valid?
  [impact {:keys [server other] :as surfaces}]
  (case impact
    :client-only (client-only-surfaces? surfaces)
    :server-data (and (pos? server) (zero? other))
    false))

(defn- stage-valid?
  [event]
  (let [impact (:runtime-impact event)
        validation (:validation event)]
    (and (= :passed (:source validation))
         (= :passed (:client validation))
         (= impact (:impact validation))
         (case impact
           :client-only (and (= :not-applicable (:server validation))
                             (= :not-applicable (:domain-tests validation)))
           :server-data (and (= :passed (:server validation))
                             (= :passed (:domain-tests validation)))
           false))))

(defn- migration-valid?
  [scenario surfaces]
  (if (contains? #{"DEMO-02" "DEMO-04"} scenario)
    (pos? (:migrations surfaces))
    (zero? (:migrations surfaces))))

(defn evaluate-record
  [observation events]
  (let [scenario (:scenario observation)
        expected (get expected-impact scenario)
        events (vec events)
        event-surfaces (mapv #(change-surfaces (:changes %)) events)
        surfaces (reduce add-surfaces
                         {:client 0 :style 0 :server 0 :shared 0
                          :test 0 :other 0 :migrations 0}
                         event-surfaces)
        attempts (mapv :generation-attempts events)
        expected-versions (vec (range (inc (:before-version observation))
                                      (inc (:after-version observation))))
        event-versions (mapv :runtime-version events)
        gates
        {:kind (and (seq events) (every? #(= :change (:kind %)) events))
         :version (and (= expected-versions event-versions)
                       (= (count events)
                          (- (:after-version observation)
                             (:before-version observation))))
         :browser (and (true? (:browser-outcome observation))
                       (true? (:client-stage-valid observation)))
         :impact (and (seq events)
                      (= expected (:runtime-impact (first events)))
                      (every? #(= (:runtime-impact %)
                                  (get-in % [:validation :impact]))
                              events))
         :surfaces (and (seq events)
                        (initial-surfaces-valid?
                         (:runtime-impact (first events))
                         (first event-surfaces))
                        (every? true?
                                (map repair-surfaces-valid?
                                     (map :runtime-impact (rest events))
                                     (rest event-surfaces))))
         :stage (and (seq events) (every? stage-valid? events))
         :migration-policy (migration-valid? scenario surfaces)
         :attempts (and (seq attempts)
                        (every? #(and (integer? %) (<= 1 % 3)) attempts))}
        passed? (every? true? (vals gates))]
    {:scenario scenario
     :runtime-before (:before-version observation)
     :runtime-after (:after-version observation)
     :duration-ms (:duration-ms observation)
     :runtime-impact expected
     :generation-attempts (reduce + 0 attempts)
     :repair-event-count (max 0 (dec (count events)))
     :changed-surface-counts surfaces
     :outcomes (:outcomes observation)
     :gates gates
     :passed? passed?}))

(defn- database-summary
  [^Path data-root session-id]
  (let [database (-> data-root
                     (.resolve "workspaces/local/sessions")
                     (.resolve (str (store/parse-session-id session-id)))
                     (.resolve "current/app.sqlite"))]
    (if-not (fs/regular-file? database)
      {:user-table-count 0 :logical-hash nil}
      (let [datasource (sqlite/datasource database)]
        {:user-table-count (count (sqlite/user-table-names datasource))
         :logical-hash (sqlite/logical-hash datasource)}))))

(defn build-report
  [{:keys [model observations events database]}]
  (let [observation-records (:records observations)
        runtime-events (->> events
                            (filter #(and (= :change (:kind %))
                                          (integer? (:runtime-version %))))
                            (sort-by :runtime-version)
                            vec)
        event-groups
        (mapv (fn [{:keys [before-version after-version]}]
                (->> runtime-events
                     (filter #(< before-version (:runtime-version %)
                                 (inc after-version)))
                     vec))
              observation-records)
        records (mapv evaluate-record observation-records event-groups)
        selected-events (vec (mapcat identity event-groups))
        missing-records (max 0 (- (count scenario-order) (count records)))
        failed-records (count (remove :passed? records))
        thread-ids (mapv :provider-thread-id selected-events)
        ordered? (= scenario-order (mapv :scenario records))
        event-coverage? (= (mapv :runtime-version runtime-events)
                           (mapv :runtime-version selected-events))
        thread-continuity? (and (= (count scenario-order) (count records))
                                (every? seq event-groups)
                                (every? string? thread-ids)
                                (= 1 (count (distinct thread-ids))))
        passed? (and ordered?
                     event-coverage?
                     thread-continuity?
                     (= (count scenario-order) (count records))
                     (every? :passed? records)
                     (pos? (:user-table-count database)))]
    {:format-version 1
     :created-at (Instant/now)
     :provider :codex
     :model model
     :session-count 1
     :scenario-count (count scenario-order)
     :record-count (count records)
     :passed (count (filter :passed? records))
     :failed (+ failed-records missing-records)
     :thread-continuity (if thread-continuity? :passed :failed)
     :event-coverage (if event-coverage? :passed :failed)
     :scenario-order (if ordered? :passed :failed)
     :database database
     :records records
     :passed? passed?}))

(defn report-passes?
  [report]
  (true? (:passed? report)))

(defn generate-report!
  [^Path data-root ^Path observations-path ^Path output-root model]
  (let [observations (json/read-str (fs/read-text observations-path)
                                    :key-fn keyword)
        session-id (:session-id observations)
        events (history-events data-root session-id)
        database (database-summary data-root session-id)
        report (build-report {:model model
                              :observations observations
                              :events events
                              :database database})
        report-path (.resolve output-root "report.edn")]
    (fs/ensure-dir! output-root)
    (fs/atomic-write-edn! report-path report)
    {:path report-path :report report}))

(defn -main
  [& [data-root observations-path output-root model]]
  (when-not (every? #(and (string? %) (not (str/blank? %)))
                    [data-root observations-path output-root model])
    (throw (ex-info
            "Usage: ppp.eval.demo-story DATA_ROOT OBSERVATIONS OUTPUT_ROOT MODEL"
            {:code :demo-story/arguments-invalid})))
  (let [{:keys [path report]}
        (generate-report!
         (Paths/get data-root (make-array String 0))
         (Paths/get observations-path (make-array String 0))
         (Paths/get output-root (make-array String 0))
         model)]
    (println (pr-str {:report (str path)
                      :passed (:passed report)
                      :failed (:failed report)
                      :thread-continuity (:thread-continuity report)
                      :passed? (:passed? report)}))
    (when-not (report-passes? report)
      (System/exit 1))))
