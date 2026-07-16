(ns ppp.eval.evolution
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [ppp.runtime.sqlite :as sqlite]
            [ppp.session.store :as store]
            [ppp.util.fs :as fs])
  (:import (java.nio.file Path Paths)
           (java.time Instant)))

(def scenario-order
  ["EVOLVE-01" "EVOLVE-02" "EVOLVE-03"
   "EVOLVE-04" "EVOLVE-05" "EVOLVE-06"])

(def expected-impact
  {"EVOLVE-01" :client-only
   "EVOLVE-02" :client-only
   "EVOLVE-03" :client-only
   "EVOLVE-04" :server-data
   "EVOLVE-05" :server-data
   "EVOLVE-06" :client-only})

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

(defn- client-only-surfaces?
  [{:keys [server shared test other migrations]}]
  (zero? (+ server shared test other migrations)))

(defn- server-data-surfaces?
  [{:keys [server test other]}]
  (and (pos? server) (pos? test) (zero? other)))

(defn evaluate-record
  [observation event]
  (let [scenario (:scenario observation)
        expected (get expected-impact scenario)
        surfaces (change-surfaces (:changes event))
        validation (:validation event)
        impact-valid? (= expected (:runtime-impact event)
                         (:impact validation))
        surface-valid?
        (case expected
          :client-only (client-only-surfaces? surfaces)
          :server-data (server-data-surfaces? surfaces)
          false)
        server-stage
        (case expected
          :client-only (and (= :not-applicable (:server validation))
                            (= :not-applicable (:domain-tests validation)))
          :server-data (and (= :passed (:server validation))
                            (= :passed (:domain-tests validation)))
          false)
        migration-valid?
        (case scenario
          "EVOLVE-04" (pos? (:migrations surfaces))
          "EVOLVE-05" (zero? (:migrations surfaces))
          true)
        attempts (:generation-attempts event)
        gates {:kind (= :change (:kind event))
               :version (and (= (inc (:before-version observation))
                                (:after-version observation))
                             (= (:after-version observation)
                                (:runtime-version event)))
               :browser (and (true? (:browser-outcome observation))
                             (true? (:client-stage-valid observation)))
               :source (= :passed (:source validation))
               :client-stage (= :passed (:client validation))
               :impact impact-valid?
               :surfaces surface-valid?
               :server-stage server-stage
               :migration-policy migration-valid?
               :attempts (and (integer? attempts) (<= 1 attempts 3))}
        passed? (every? true? (vals gates))]
    {:scenario scenario
     :runtime-before (:before-version observation)
     :runtime-after (:after-version observation)
     :duration-ms (:duration-ms observation)
     :runtime-impact (:runtime-impact event)
     :generation-attempts attempts
     :changed-surface-counts surfaces
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
  (let [records (mapv evaluate-record (:records observations) events)
        missing-records (max 0 (- (count scenario-order) (count records)))
        failed-records (count (remove :passed? records))
        thread-ids (mapv :provider-thread-id events)
        thread-continuity? (and (= (count scenario-order) (count thread-ids))
                                (every? string? thread-ids)
                                (= 1 (count (distinct thread-ids))))
        ordered? (= scenario-order (mapv :scenario records))
        passed? (and ordered?
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
     :scenario-order (if ordered? :passed :failed)
     :database database
     :records records
     :passed? passed?}))

(defn report-passes?
  [report]
  (true? (:passed? report)))

(defn generate-report!
  [^Path data-root ^Path observations-path ^Path output-root model]
  (let [observations (json/read-str (fs/read-text observations-path) :key-fn keyword)
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
            "Usage: ppp.eval.evolution DATA_ROOT OBSERVATIONS OUTPUT_ROOT MODEL"
            {:code :evolution-eval/arguments-invalid})))
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
                      :database-tables (get-in report [:database :user-table-count])}))
    (when-not (report-passes? report)
      (System/exit 1))))
