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
   "EVOLVE-04" "EVOLVE-05" "EVOLVE-06"
   "EVOLVE-07" "EVOLVE-08"])

(def expected-impact
  {"EVOLVE-01" :client-only
   "EVOLVE-02" :client-only
   "EVOLVE-03" :client-only
   "EVOLVE-04" :server-data
   "EVOLVE-05" :server-data
   "EVOLVE-06" :client-only
   "EVOLVE-07" :server-data
   "EVOLVE-08" :server-data})

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

(defn- valid-surfaces-for-impact?
  [impact surfaces]
  (case impact
    :client-only (client-only-surfaces? surfaces)
    :server-data (server-data-surfaces? surfaces)
    false))

(defn- valid-repair-surfaces-for-impact?
  [impact {:keys [server other] :as surfaces}]
  (case impact
    :client-only (client-only-surfaces? surfaces)
    ;; The scenario's first server-data event must introduce or update its
    ;; rollback-only domain tests. A later semantic repair may rely on those
    ;; same tests, but it must still be an actual server change and may never
    ;; escape into an unclassified surface.
    :server-data (and (pos? server) (zero? other))
    false))

(defn- valid-stage-for-impact?
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

(defn- add-surfaces
  [left right]
  (merge-with + left right))

(defn- valid-thread-lineage?
  "A successful evolution normally stays on one provider thread. A new thread
  is valid only after append-only history records that the previous generated
  branch was terminally detached. This proves continuity without treating a
  deliberately discarded failed branch as reusable context."
  [events]
  (loop [[event & more] events
         active-thread nil
         reset-authorized? false]
    (if-not event
      true
      (case (:kind event)
        :rejected
        (recur more active-thread
               (or reset-authorized?
                   (and (true? (:provider-thread-reset? event))
                        (string? active-thread)
                        (= active-thread (:provider-thread-id event)))))

        :change
        (let [thread-id (:provider-thread-id event)]
          (cond
            (not (string? thread-id)) false
            (nil? active-thread) (recur more thread-id false)
            (= active-thread thread-id) (recur more active-thread false)
            reset-authorized? (recur more thread-id false)
            :else false))

        (recur more active-thread reset-authorized?)))))

(defn evaluate-record
  [observation events]
  (let [scenario (:scenario observation)
        expected (get expected-impact scenario)
        events (vec events)
        initial-event (first events)
        event-surfaces (mapv #(change-surfaces (:changes %)) events)
        surfaces (reduce add-surfaces
                         {:client 0 :style 0 :server 0 :shared 0
                          :test 0 :other 0 :migrations 0}
                         event-surfaces)
        impact-valid? (and (= expected (:runtime-impact initial-event))
                           (every? #(= (:runtime-impact %)
                                       (get-in % [:validation :impact]))
                                   events))
        surface-valid?
        (and (seq events)
             (valid-surfaces-for-impact? (:runtime-impact initial-event)
                                         (first event-surfaces))
             (every? true?
                     (map valid-repair-surfaces-for-impact?
                          (map :runtime-impact (rest events))
                          (rest event-surfaces))))
        server-stage (and (seq events)
                          (every? valid-stage-for-impact? events))
        migration-valid?
        (case scenario
          "EVOLVE-04" (pos? (:migrations surfaces))
          "EVOLVE-05" (zero? (:migrations surfaces))
          "EVOLVE-07" (pos? (:migrations surfaces))
          "EVOLVE-08" (zero? (:migrations surfaces))
          true)
        attempts (mapv :generation-attempts events)
        expected-versions (vec (range (inc (:before-version observation))
                                      (inc (:after-version observation))))
        event-versions (mapv :runtime-version events)
        gates {:kind (and (seq events) (every? #(= :change (:kind %)) events))
               :version (and (= expected-versions event-versions)
                             (= (count events)
                                (- (:after-version observation)
                                   (:before-version observation))))
               :browser (and (true? (:browser-outcome observation))
                             (true? (:client-stage-valid observation)))
               :source (and (seq events)
                            (every? #(= :passed (get-in % [:validation :source]))
                                    events))
               :client-stage (and (seq events)
                                  (every? #(= :passed (get-in % [:validation :client]))
                                          events))
               :impact impact-valid?
               :surfaces surface-valid?
               :server-stage server-stage
               :migration-policy migration-valid?
               ;; The coordinator contract allows one proposal plus five
               ;; same-turn validation repairs. Semantic browser repairs are
               ;; separate committed turns and are counted by version span.
               :attempts (and (seq attempts)
                              (every? #(and (integer? %) (<= 1 % 6)) attempts))}
        passed? (every? true? (vals gates))]
    {:scenario scenario
     :runtime-before (:before-version observation)
     :runtime-after (:after-version observation)
     :duration-ms (:duration-ms observation)
     :runtime-impact expected
     :generation-attempts (reduce + 0 attempts)
     :repair-event-count (max 0 (dec (count events)))
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
  (let [observations-records (:records observations)
        runtime-events (->> events
                            (filter #(and (= :change (:kind %))
                                          (integer? (:runtime-version %))))
                            (sort-by :runtime-version)
                            vec)
        event-groups
        (mapv (fn [{:keys [before-version after-version]}]
                (->> runtime-events
                     (filter #(< before-version (:runtime-version %) (inc after-version)))
                     vec))
              observations-records)
        records (mapv evaluate-record observations-records event-groups)
        selected-events (vec (mapcat identity event-groups))
        missing-records (max 0 (- (count scenario-order) (count records)))
        failed-records (count (remove :passed? records))
        event-coverage? (= (mapv :runtime-version runtime-events)
                           (mapv :runtime-version selected-events))
        thread-continuity? (and (= (count scenario-order) (count records))
                                (every? seq event-groups)
                                (valid-thread-lineage? events))
        ordered? (= scenario-order (mapv :scenario records))
        passed? (and ordered?
                     thread-continuity?
                     event-coverage?
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
