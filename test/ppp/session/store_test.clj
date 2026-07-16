(ns ppp.session.store-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ppp.runtime.sqlite :as sqlite]
            [ppp.session.store :as store]
            [ppp.shared.protocol :as protocol]
            [ppp.util.fs :as fs])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.util UUID)))

(defn- test-context
  []
  (let [root (Files/createTempDirectory
              "ppp-store-test"
              (make-array FileAttribute 0))
        config {:data-dir root
                :source-file-limit 32
                :source-byte-limit (* 256 1024)
                :session-db-limit (* 25 1024 1024)
                :checkpoint-limit (* 256 1024 1024)
                :instance-limit (* 2 1024 1024 1024)}]
    {:root root
     :config config
     :store (store/create-store config)}))

(defn- exception-code
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo cause
      (:code (ex-data cause)))))

(defn- stage-database!
  [store session-id stage]
  (sqlite/stage-database!
   (store/current-db-path store session-id)
   (:database stage)
   (:assigned-migrations stage))
  stage)

(deftest new-session-is-a-complete-version-zero-product
  (let [{:keys [root store]} (test-context)]
    (try
      (let [session (store/create-session! store)
            session-id (:id session)
            manifest (store/current-manifest store session-id)
            source (store/current-source-map store session-id)
            checkpoints (store/list-checkpoints store session-id)]
        (is (uuid? session-id))
        (is (= 0 (:current-version session)))
        (is (nil? (:transcript-summary session)))
        (is (= 0 (:runtime-version manifest)))
        (is (protocol/valid-manifest? manifest))
        (is (= (set (keys store/initial-source))
               (set (keys source))
               (set (keys (:files manifest)))))
        (is (= [0] (mapv :runtime-version checkpoints)))
        (is (fs/regular-file? (store/current-db-path store session-id)))
        (is (= 0 (sqlite/runtime-version
                  (sqlite/datasource (store/current-db-path store session-id))))))
      (finally
        (fs/delete-tree! root)))))

(deftest current-source-must-match-its-manifest
  (let [{:keys [root store]} (test-context)]
    (try
      (let [session-id (:id (store/create-session! store))
            domain-path (fs/safe-child (store/current-source-root store session-id)
                                       "src/shared/runtime/domain.cljc")]
        (fs/atomic-write-string! domain-path "(ns runtime.domain)\n(def compromised? true)\n")
        (is (= :manifest/hash-mismatch
               (exception-code #(store/current-manifest store session-id)))))
      (finally
        (fs/delete-tree! root)))))

(deftest history-sequences-remain-unique-under-concurrency
  (let [{:keys [root store]} (test-context)]
    (try
      (let [session-id (:id (store/create-session! store))
            source-before (store/current-source-map store session-id)
            db-before (sqlite/logical-hash
                       (sqlite/datasource (store/current-db-path store session-id)))
            jobs (mapv (fn [index]
                         (future
                           (store/append-history!
                            store session-id
                            {:kind :reply
                             :prompt (str "Prompt " index)
                             :assistant (str "Reply " index)})))
                       (range 20))]
        (doseq [job jobs]
          (is (map? (deref job 5000 ::timeout))))
        (let [events (store/list-history store session-id)]
          (is (= (vec (range 1 21)) (mapv :event-sequence events)))
          (is (= 20 (count (set (map :event-id events))))))
        (is (= source-before (store/current-source-map store session-id)))
        (is (= db-before
               (sqlite/logical-hash
                (sqlite/datasource (store/current-db-path store session-id))))))
      (finally
        (fs/delete-tree! root)))))

(deftest failed-history-append-never-exposes-a-partial-event
  (let [{:keys [root store]} (test-context)]
    (try
      (let [session-id (:id (store/create-session! store))
            history-root (.resolve (store/session-root store session-id) "history")]
        (is (thrown? Exception
                     (store/append-history!
                      store session-id
                      {:kind :change
                       :assistant "This event must remain invisible."
                       :before {"../outside.clj" "invalid"}})))
        (is (empty? (store/list-history store session-id)))
        (is (empty? (filter #(str/starts-with? (str (.getFileName ^Path %))
                                               ".pending-")
                            (fs/list-tree history-root)))))
      (finally
        (fs/delete-tree! root)))))

(deftest staging-is-isolated-and-discardable
  (let [{:keys [root store]} (test-context)]
    (try
      (let [session-id (:id (store/create-session! store))
            current-before (store/current-source-map store session-id)
            transaction-id (random-uuid)
            stage (store/stage-change!
                   store session-id transaction-id
                   {:title "Add a canvas style"
                    :writes [{:path "styles/canvas.css"
                              :content ":host { background: white; }\n"}]
                    :deletes []
                    :migrations []})]
        (is (= 1 (get-in stage [:manifest :runtime-version])))
        (is (= ":host { background: white; }\n"
               (fs/read-text (fs/safe-child (:source stage) "styles/canvas.css"))))
        (is (= current-before (store/current-source-map store session-id)))
        (is (fs/exists? (:root stage)))
        (store/discard-stage! stage)
        (is (not (fs/exists? (:root stage)))))
      (finally
        (fs/delete-tree! root)))))

(deftest staged-source-and-database-materialize-and-roll-back-together
  (let [{:keys [root store]} (test-context)]
    (try
      (let [session-id (:id (store/create-session! store))
            source-before (store/current-source-map store session-id)
            database-before
            (sqlite/logical-hash
             (sqlite/datasource (store/current-db-path store session-id)))
            stage
            (let [stage (store/stage-change!
                         store session-id (random-uuid)
                         {:title "Add durable notes"
                          :writes [{:path "styles/notes.css"
                                    :content ".notes { color: black; }\n"}]
                          :deletes []
                          :migrations
                          [{:name "create-notes.sql"
                            :sql "CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL);\nINSERT INTO notes (body) VALUES ('kept together');"}]})]
              (stage-database! store session-id stage))]
        (is (= [{:file-name "000001-create-notes.sql"
                 :name "create-notes"
                 :sql "CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL);\nINSERT INTO notes (body) VALUES ('kept together');"}]
               (:assigned-migrations stage)))
        (is (= source-before (store/current-source-map store session-id)))
        (is (= database-before
               (sqlite/logical-hash
                (sqlite/datasource (store/current-db-path store session-id)))))
        (let [materialized (store/materialize-stage! store session-id stage)]
          (is (= 1 (:runtime-version (store/current-manifest store session-id))))
          (is (= [{:body "kept together"}]
                 (sqlite/query! (sqlite/datasource (store/current-db-path store session-id))
                                "SELECT body FROM notes" [])))
          (is (fs/regular-file? (:journal-file materialized)))
          (store/rollback-materialized! store session-id materialized)
          (is (= source-before (store/current-source-map store session-id)))
          (is (= database-before
                 (sqlite/logical-hash
                  (sqlite/datasource (store/current-db-path store session-id)))))
          (is (= 0 (:current-version (store/get-session store session-id))))
          (is (empty? (store/list-journals store session-id)))))
      (finally
        (fs/delete-tree! root)))))

(deftest finalized-materialization-removes-only-transaction-scaffolding
  (let [{:keys [root store]} (test-context)]
    (try
      (let [session-id (:id (store/create-session! store))
            stage
            (let [stage (store/stage-change!
                         store session-id (random-uuid)
                         {:title "Commit a style"
                          :writes [{:path "styles/committed.css"
                                    :content ".committed { display: block; }\n"}]
                          :deletes []
                          :migrations []})]
              (stage-database! store session-id stage))
            materialized (store/materialize-stage! store session-id stage)]
        (store/update-session! store session-id {:current-version 1})
        (store/create-checkpoint! store session-id {:title "Committed style"})
        (store/finalize-materialized! store session-id materialized)
        (is (= 1 (:current-version (store/get-session store session-id))))
        (is (contains? (store/current-source-map store session-id)
                       "styles/committed.css"))
        (is (= [0 1] (mapv :runtime-version
                           (store/list-checkpoints store session-id))))
        (is (empty? (store/list-journals store session-id)))
        (is (not (fs/exists? (:root stage)))))
      (finally
        (fs/delete-tree! root)))))

(defn- recovery-stage
  [store session-id label]
  (let [transaction-id (random-uuid)
        stage
        (store/stage-change!
         store session-id transaction-id
         {:title label
          :writes [{:path "styles/recovery.css"
                    :content (str "/* " label " */\n")}]
          :deletes []
          :migrations []})
        stage (stage-database! store session-id stage)
        target-version (get-in stage [:manifest :runtime-version])]
    (assoc stage :recovery
           {:event {:kind :change
                    :transaction-id transaction-id
                    :base-version (dec target-version)
                    :runtime-version target-version
                    :title label
                    :assistant "Recovered fixture"
                    :before (:before stage)
                    :after (:after stage)}
            :checkpoint {:title label :kind :change}
            :thread-action :reset})))

(deftest startup-journal-recovery-matrix-is-idempotent
  (let [{:keys [root store]} (test-context)]
    (try
      (testing "target/target finalizes missing durable evidence exactly once"
        (let [session-id (:id (store/create-session! store))
              materialized (store/materialize-stage!
                            store session-id
                            (recovery-stage store session-id "Target commit"))]
          (is (= 1 (:runtime-version (store/current-manifest store session-id))))
          (is (= [{:transaction-id (:transaction-id materialized)
                   :outcome :finalized
                   :runtime-version 1}]
                 (store/recover-session! store session-id)))
          (is (empty? (store/recover-session! store session-id)))
          (is (= 1 (:current-version (store/get-session store session-id))))
          (is (= [0 1] (mapv :runtime-version
                             (store/list-checkpoints store session-id))))
          (is (= 1 (count (store/list-history store session-id))))
          (is (empty? (store/list-journals store session-id)))))

      (testing "base/base removes an abandoned prepared transaction"
        (let [session-id (:id (store/create-session! store))
              materialized (store/materialize-stage!
                            store session-id
                            (recovery-stage store session-id "Abandoned commit"))]
          (fs/move-replacing! (:backup-current materialized)
                              (store/current-root store session-id))
          (is (= :abandoned (:outcome (first (store/recover-session! store session-id)))))
          (is (empty? (store/recover-session! store session-id)))
          (is (= 0 (:runtime-version (store/current-manifest store session-id))))
          (is (= [0] (mapv :runtime-version
                           (store/list-checkpoints store session-id))))
          (is (empty? (store/list-history store session-id)))))

      (testing "mixed source/database versions restore and verify the before backup"
        (let [session-id (:id (store/create-session! store))
              source-before (store/current-source-map store session-id)
              database-before
              (sqlite/logical-hash
               (sqlite/datasource (store/current-db-path store session-id)))]
          (store/materialize-stage! store session-id
                                    (recovery-stage store session-id "Mixed commit"))
          (sqlite/set-runtime-version!
           (sqlite/datasource (store/current-db-path store session-id)) 0)
          (is (= :rolled-back
                 (:outcome (first (store/recover-session! store session-id)))))
          (is (empty? (store/recover-session! store session-id)))
          (is (= source-before (store/current-source-map store session-id)))
          (is (= database-before
                 (sqlite/logical-hash
                  (sqlite/datasource (store/current-db-path store session-id)))))
          (is (= 0 (:current-version (store/get-session store session-id))))))
      (finally
        (fs/delete-tree! root)))))

(deftest checkpoint-corruption-never-mutates-current
  (let [{:keys [root store]} (test-context)]
    (try
      (doseq [[label corrupt!]
              [["source"
                (fn [checkpoint]
                  (fs/atomic-write-string!
                   (fs/safe-child (:source checkpoint)
                                  "src/shared/runtime/domain.cljc")
                   "(ns runtime.domain)\n(def corrupt? true)\n"))]
               ["manifest"
                (fn [checkpoint]
                  (fs/atomic-write-string! (.resolve (:root checkpoint) "manifest.edn")
                                           "{:not :a-manifest}\n"))]
               ["gzip"
                (fn [checkpoint]
                  (let [archive (:archive checkpoint)]
                    (fs/atomic-write-string! archive "not-gzip")
                    (fs/atomic-write-edn!
                     (.resolve (:root checkpoint) "checkpoint.edn")
                     (assoc (:metadata checkpoint)
                            :database-archive-hash (fs/sha256-file archive)))))]
               ["sqlite"
                (fn [checkpoint]
                  (let [invalid (.resolve (:root checkpoint) "invalid.sqlite")
                        archive (:archive checkpoint)]
                    (fs/atomic-write-string! invalid "not-a-sqlite-database")
                    (fs/gzip-file! invalid archive)
                    (Files/deleteIfExists invalid)
                    (fs/atomic-write-edn!
                     (.resolve (:root checkpoint) "checkpoint.edn")
                     (assoc (:metadata checkpoint)
                            :database-archive-hash (fs/sha256-file archive)))))]]]
        (testing label
          (let [session-id (:id (store/create-session! store))
                source-before (store/current-source-map store session-id)
                database-before
                (sqlite/logical-hash
                 (sqlite/datasource (store/current-db-path store session-id)))
                checkpoint (store/read-checkpoint store session-id 0)]
            (corrupt! checkpoint)
            (is (= :checkpoint/corrupt
                   (exception-code
                    #(store/stage-restore! store session-id (random-uuid) 0))))
            (is (= source-before (store/current-source-map store session-id)))
            (is (= database-before
                   (sqlite/logical-hash
                    (sqlite/datasource (store/current-db-path store session-id))))))))
      (finally
        (fs/delete-tree! root)))))

(deftest complete-file-write-manifest-property
  (let [{:keys [root store]} (test-context)]
    (try
      (let [session-id (:id (store/create-session! store))
            result
            (tc/quick-check
             100
             (prop/for-all [value gen/string-alphanumeric]
                           (let [content (str "/* " value " */\n")
                                 stage (store/stage-change!
                                        store session-id (random-uuid)
                                        {:title "Property-generated complete file"
                                         :writes [{:path "styles/property.css"
                                                   :content content}]
                                         :deletes []
                                         :migrations []})]
                             (try
                               (= (fs/sha256-string content)
                                  (get-in stage
                                          [:manifest :files "styles/property.css"]))
                               (finally
                                 (store/discard-stage! stage))))))]
        (is (:pass? result) (pr-str (dissoc result :result-data))))
      (finally
        (fs/delete-tree! root)))))

(deftest every-storage-quota-has-below-equal-and-above-boundaries
  (let [{:keys [root store]} (test-context)]
    (try
      (let [session-id (:id (store/create-session! store))
            source-root (store/current-source-root store session-id)
            status (store/quota-status store session-id)
            dimensions [[:source-files :source-file-limit]
                        [:source-bytes :source-byte-limit]
                        [:database-bytes :session-db-limit]
                        [:checkpoint-bytes :checkpoint-limit]
                        [:instance-bytes :instance-limit]]]
        (doseq [[usage-key limit-key] dimensions]
          (let [usage (get status usage-key)
                below-limit-store (assoc store :config
                                         (assoc (:config store) limit-key (inc usage)))
                equal-limit-store (assoc store :config
                                         (assoc (:config store) limit-key usage))
                above-limit-store (assoc store :config
                                         (assoc (:config store) limit-key (dec usage)))]
            (testing (str (name usage-key) " usage below its limit")
              (is (map? (store/assert-quota! below-limit-store session-id source-root))))
            (testing (str (name usage-key) " usage equal to its limit")
              (is (map? (store/assert-quota! equal-limit-store session-id source-root))))
            (testing (str (name usage-key) " usage above its limit")
              (is (= :storage/quota-exceeded
                     (exception-code
                      #(store/assert-quota! above-limit-store session-id source-root))))))))
      (finally
        (fs/delete-tree! root)))))

(deftest failed-stage-preserves-current-and-checkpoints
  (let [{:keys [root store]} (test-context)]
    (try
      (let [session-id (:id (store/create-session! store))
            source-before (store/current-source-map store session-id)
            source-size (fs/directory-size (store/current-source-root store session-id))
            quota-store (assoc store :config
                               (assoc (:config store)
                                      :source-byte-limit (+ source-size 5)))
            transaction-id (random-uuid)
            stage-root (.resolve (.resolve (store/session-root store session-id) ".staging")
                                 (str transaction-id))]
        (is (= :storage/quota-exceeded
               (exception-code
                #(store/stage-change!
                  quota-store session-id transaction-id
                  {:title "Exceed source quota"
                   :writes [{:path "styles/quota.css"
                             :content "1234567890abcdef"}]
                   :deletes []
                   :migrations []}))))
        (is (= source-before (store/current-source-map store session-id)))
        (is (not (fs/exists? stage-root)))
        (is (= [0] (mapv :runtime-version
                         (store/list-checkpoints store session-id)))))
      (finally
        (fs/delete-tree! root)))))

(deftest fixed-entrypoint-cannot-be-deleted
  (let [{:keys [root store]} (test-context)]
    (try
      (let [session-id (:id (store/create-session! store))
            transaction-id (random-uuid)
            stage-root (.resolve (.resolve (store/session-root store session-id) ".staging")
                                 (str transaction-id))]
        (is (= :source/missing-entrypoint
               (exception-code
                #(store/stage-change!
                  store session-id transaction-id
                  {:title "Remove the host entrypoint"
                   :writes []
                   :deletes ["src/client/runtime/client.cljs"]
                   :migrations []}))))
        (is (not (fs/exists? stage-root)))
        (is (contains? (store/current-source-map store session-id)
                       "src/client/runtime/client.cljs")))
      (finally
        (fs/delete-tree! root)))))

(deftest sessions-do-not-share-history-or-storage
  (let [{:keys [root store]} (test-context)]
    (try
      (let [session-a (:id (store/create-session! store {:title "A"}))
            session-b (:id (store/create-session! store {:title "B"}))]
        (store/append-history! store session-a {:kind :reply :assistant "Only A"})
        (is (not= (store/session-root store session-a)
                  (store/session-root store session-b)))
        (is (not= (store/current-db-path store session-a)
                  (store/current-db-path store session-b)))
        (is (= 1 (count (store/list-history store session-a))))
        (is (empty? (store/list-history store session-b)))
        (is (= (store/current-source-map store session-a)
               (store/current-source-map store session-b))))
      (finally
        (fs/delete-tree! root)))))

(deftest symbolic-links-are-rejected
  (let [{:keys [root store]} (test-context)]
    (try
      (let [session-id (:id (store/create-session! store))
            external (Files/createTempFile root "external" ".css"
                                           (make-array FileAttribute 0))
            link (fs/safe-child (store/current-source-root store session-id)
                                "styles/external.css")]
        (Files/createSymbolicLink link external (make-array FileAttribute 0))
        (is (= :storage/symlink
               (exception-code #(store/current-source-map store session-id)))))
      (finally
        (fs/delete-tree! root)))))

(deftest session-path-containment-property
  (let [{:keys [root store]} (test-context)]
    (try
      (testing "1,000 generated UUIDs stay directly below the session root"
        (let [uuid-gen (gen/fmap
                        (fn [values]
                          (UUID/nameUUIDFromBytes
                           (byte-array (map byte values))))
                        (gen/vector (gen/choose -128 127) 16))
              result (tc/quick-check
                      1000
                      (prop/for-all [session-id uuid-gen]
                                    (let [^Path path (store/session-root store session-id)]
                                      (and (= (:sessions-root store) (.getParent path))
                                           (.startsWith path (:sessions-root store)))))
                      :seed 90051)]
          (is (= 1000 (:num-tests result)))
          (is (:pass? result) (pr-str (dissoc result :result-data)))))
      (testing "arbitrary non-UUID identifiers never become filesystem paths"
        (let [result (tc/quick-check
                      1000
                      (prop/for-all [value gen/string-alphanumeric]
                                    (= :session/invalid-id
                                       (exception-code
                                        #(store/session-root store
                                                             (str "../" value)))))
                      :seed 90052)]
          (is (= 1000 (:num-tests result)))
          (is (:pass? result) (pr-str (dissoc result :result-data)))))
      (finally
        (fs/delete-tree! root)))))
