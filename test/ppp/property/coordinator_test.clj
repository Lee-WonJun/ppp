(ns ppp.property.coordinator-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ppp.coordinator :as coordinator]
            [ppp.runtime.sqlite :as sqlite]
            [ppp.session.store :as store]
            [ppp.shared.protocol :as protocol]
            [ppp.util.fs :as fs]
            [ppp.websocket :as websocket])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- exception-code
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo cause
      (:code (ex-data cause)))))

(def operation-gen
  (gen/elements [:reply :clarify :accepted-change :rejected-change
                 :accepted-restore]))

(def impact-path-gen
  (gen/elements ["src/client/runtime/client.cljs"
                 "src/client/runtime/sidebar.cljs"
                 "styles/runtime.css"
                 "src/server/runtime/server.clj"
                 "src/shared/runtime/domain.cljc"
                 "test/runtime/domain_test.cljc"]))

(deftest runtime-impact-is-derived-deterministically-from-affected-paths
  (let [result
        (tc/quick-check
         1000
         (prop/for-all [paths (gen/vector impact-path-gen 0 20)
                        migration? gen/boolean]
                       (let [change {:writes (mapv (fn [path]
                                                     {:path path :content "fixture"})
                                                   paths)
                                     :deletes []
                                     :migrations (if migration?
                                                   [{:name "fixture"
                                                     :sql "CREATE TABLE fixture (id INTEGER);"}]
                                                   [])}
                             server-data?
                             (or migration?
                                 (some #(or (str/starts-with? % "src/server/")
                                            (str/starts-with? % "src/shared/")
                                            (str/starts-with? % "test/"))
                                       paths))
                             expected (if server-data? :server-data :client-only)]
                         (and (= expected (coordinator/change-impact change))
                              (= (coordinator/change-impact change)
                                 (coordinator/change-impact change)))))
         :seed 14001)]
    (is (:pass? result) (pr-str (dissoc result :result-data)))))

(deftest committed-versions-are-unique-and-strictly-increasing
  (let [result
        (tc/quick-check
         1000
         (prop/for-all [operations (gen/vector operation-gen 0 40)]
                       (let [{:keys [active committed]}
                             (reduce
                              (fn [{:keys [active] :as state} operation]
                                (if (contains? #{:accepted-change :accepted-restore}
                                               operation)
                                  (let [target (inc active)]
                                    (store/assert-next-runtime-version!
                                     active target)
                                    (-> state
                                        (assoc :active target)
                                        (update :committed conj target)))
                                  state))
                              {:active 0 :committed []}
                              operations)
                             expected (count
                                       (filter
                                        #{:accepted-change :accepted-restore}
                                        operations))]
                         (and (= expected active)
                              (= (vec (range 1 (inc active))) committed)
                              (= (count committed) (count (distinct committed)))
                              (every? true?
                                      (map < committed (rest committed))))))
         :seed 7001)]
    (is (:pass? result) (pr-str (dissoc result :result-data)))))

(def invalid-change-gen
  (gen/one-of
   [(gen/fmap
     (fn [suffix]
       {:title "Traversal"
        :writes [{:path (str "../" suffix ".clj") :content "(ns escaped)"}]
        :deletes []
        :migrations []})
     (gen/not-empty gen/string-alphanumeric))
    (gen/return
     {:title "Delete kernel entry"
      :writes []
      :deletes ["src/server/runtime/server.clj"]
      :migrations []})
    (gen/return
     {:title "Forbidden SQL"
      :writes []
      :deletes []
      :migrations [{:name "escape"
                    :sql "ATTACH DATABASE '/tmp/escape' AS escaped;"}]})
    (gen/return
     {:title "Forbidden runtime symbol"
      :writes [{:path "src/server/runtime/server.clj"
                :content "(ns runtime.server)\n(System/exit 0)\n"}]
      :deletes []
      :migrations []})]))

(deftest generated-rejections-preserve-source-and-logical-database-hashes
  (let [root (Files/createTempDirectory "ppp-rejection-property"
                                        (make-array FileAttribute 0))
        config {:data-dir root
                :source-file-limit 32
                :source-byte-limit (* 256 1024)
                :session-db-limit (* 25 1024 1024)
                :checkpoint-limit (* 256 1024 1024)
                :instance-limit (* 2 1024 1024 1024)}]
    (try
      (let [session-store (store/create-store config)
            session-id (:id (store/create-session! session-store))
            source-before (store/current-source-map session-store session-id)
            manifest-before (store/current-manifest session-store session-id)
            database-before
            (sqlite/logical-hash
             (sqlite/datasource (store/current-db-path session-store session-id)))
            result
            (tc/quick-check
             1000
             (prop/for-all [change invalid-change-gen]
                           (let [code
                                 (exception-code
                                  #(store/stage-change!
                                    session-store session-id (random-uuid) change))]
                             (and code
                                  (= manifest-before
                                     (store/current-manifest session-store
                                                             session-id))
                                  (= source-before
                                     (store/current-source-map session-store
                                                               session-id))
                                  (= database-before
                                     (sqlite/logical-hash
                                      (sqlite/datasource
                                       (store/current-db-path session-store
                                                              session-id)))))))
             :seed 7002)]
        (is (:pass? result) (pr-str (dissoc result :result-data))))
      (finally
        (fs/delete-tree! root)))))

(defn- envelope
  [session-id request-id runtime-version type payload]
  (protocol/envelope {:session-id session-id
                      :request-id request-id
                      :runtime-version runtime-version
                      :type type
                      :payload payload}))

(deftest stale-browser-ack-sequences-never-authorize-a-stage
  (let [session-id (random-uuid)
        tab-id (random-uuid)
        channel :requester
        hub
        (websocket/create-hub
         {:client-ack-timeout-ms 10000
          :require-client-ack? true
          :runtime-bundle-fn
          (constantly {:runtime-version 0
                       :capability-version 1
                       :files []})
          :send-fn (constantly true)
          :close-fn (constantly true)})]
    (try
      (websocket/open! hub channel)
      (websocket/receive!
       hub channel
       (websocket/encode-message
        (envelope session-id (random-uuid) 0 :session/subscribe
                  {:tab-id tab-id :current-version 0})))
      (let [result
            (tc/quick-check
             1000
             (prop/for-all [mismatch (gen/elements
                                      [:request-id :session-id
                                       :version-pair :tab-id])]
                           (let [request-id (random-uuid)
                                 transaction-id (random-uuid)
                                 submission
                                 (websocket/request-stage!
                                  hub
                                  {:session-id session-id
                                   :request-id request-id
                                   :tab-id tab-id
                                   :transaction-id transaction-id
                                   :base-version 0
                                   :target-version 1
                                   :capability-version 1
                                   :files []})
                                 response
                                 (cond-> (envelope
                                          session-id request-id 1 :runtime/staged
                                          {:tab-id tab-id
                                           :transaction-id transaction-id
                                           :base-version 0
                                           :target-version 1})
                                   (= mismatch :request-id)
                                   (assoc :request-id (random-uuid))

                                   (= mismatch :session-id)
                                   (assoc :session-id (random-uuid))

                                   (= mismatch :tab-id)
                                   (assoc-in [:payload :tab-id] (random-uuid))

                                   (= mismatch :version-pair)
                                   (assoc :runtime-version 2
                                          :payload
                                          {:tab-id tab-id
                                           :transaction-id transaction-id
                                           :base-version 1
                                           :target-version 2}))]
                             (websocket/receive!
                              hub channel (websocket/encode-message response))
                             (and (= {:status :rejected
                                      :code :runtime/stale-browser-version}
                                     (websocket/await-stage! hub submission))
                                  (zero? (:pending-stages
                                          (websocket/stats hub))))))
             :seed 7008)]
        (is (:pass? result) (pr-str (dissoc result :result-data))))
      (finally
        (websocket/stop! hub)))))
