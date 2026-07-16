(ns ppp.runtime.sqlite-test
  (:require [clojure.test :refer [deftest is testing]]
            [ppp.runtime.sqlite :as sqlite]
            [ppp.util.fs :as fs])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def create-notes
  {:file-name "000001-create-notes.sql"
   :name "create-notes"
   :sql "CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL);"})

(defn- test-root
  []
  (Files/createTempDirectory "ppp-sqlite-test"
                             (make-array FileAttribute 0)))

(defn- exception-code
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo cause
      (:code (ex-data cause)))
    (catch Exception _
      :unclassified)))

(deftest sqlite-kernel-configuration-and-online-backup
  (let [root (test-root)]
    (try
      (let [live (.resolve root "live.sqlite")
            backup (.resolve root "backup.sqlite")
            live-ds (sqlite/init! live)]
        (sqlite/apply-migrations! live-ds [create-notes])
        (sqlite/mutate! live-ds "INSERT INTO notes (body) VALUES (?)" ["durable"])
        (is (= 1 (:foreign_keys
                  (sqlite/execute-one! live-ds ["PRAGMA foreign_keys"]))))
        (is (= 5000 (:timeout
                     (sqlite/execute-one! live-ds ["PRAGMA busy_timeout"]))))
        (sqlite/clone-database! live backup)
        (is (= (sqlite/logical-hash live-ds)
               (sqlite/logical-hash (sqlite/datasource backup))))
        (is (= ["notes"] (sqlite/user-table-names live-ds)))
        (is (not (contains? (sqlite/logical-content live-ds) "_ppp_runtime_meta"))))
      (finally
        (fs/delete-tree! root)))))

(deftest online-backups-remain-consistent-during-concurrent-writes
  (let [root (test-root)]
    (try
      (let [live (.resolve root "live.sqlite")
            live-ds (sqlite/init! live)]
        (sqlite/apply-migrations! live-ds [create-notes])
        (let [writer
              (future
                (dotimes [index 200]
                  (sqlite/mutate! live-ds "INSERT INTO notes (body) VALUES (?)"
                                  [(str "note-" index)])))]
          (dotimes [snapshot-index 20]
            (let [snapshot (.resolve root (str "snapshot-" snapshot-index ".sqlite"))
                  snapshot-ds (sqlite/datasource
                               (sqlite/clone-database! live snapshot))
                  count (:count (sqlite/execute-one! snapshot-ds
                                                     ["SELECT COUNT(*) AS count FROM notes"]))]
              (is (sqlite/assert-integrity! snapshot-ds))
              (is (<= 0 count 200))))
          (is (not= ::timeout (deref writer 10000 ::timeout)))
          (is (= 200 (:count (sqlite/execute-one! live-ds
                                                  ["SELECT COUNT(*) AS count FROM notes"]))))))
      (finally
        (fs/delete-tree! root)))))

(deftest migrations-are-transactional-idempotent-and-immutable
  (let [root (test-root)]
    (try
      (let [database (.resolve root "app.sqlite")
            ds (sqlite/init! database)]
        (sqlite/apply-migrations! ds [create-notes])
        (sqlite/apply-migrations! ds [create-notes])
        (is (= 1 (:count (sqlite/execute-one! ds ["SELECT COUNT(*) AS count
                                                     FROM _ppp_migrations"]))))
        (is (= :sql/committed-migration-modified
               (exception-code
                #(sqlite/apply-migrations!
                  ds [(assoc create-notes
                             :sql "CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT, extra TEXT);")]))))
        (is (= :sql/migration-not-allowed
               (exception-code
                #(sqlite/apply-migrations!
                  ds [{:file-name "000002-escape.sql"
                       :name "escape"
                       :sql "ATTACH DATABASE '/tmp/private' AS private"}]))))
        (is (= ["notes"] (sqlite/user-table-names ds))))
      (finally
        (fs/delete-tree! root)))))

(deftest failed-stage-database-never-mutates-live-content
  (let [root (test-root)]
    (try
      (let [live (.resolve root "live.sqlite")
            stage (.resolve root "stage.sqlite")
            live-ds (sqlite/init! live)
            before (sqlite/logical-hash live-ds)
            failing {:file-name "000001-bad-seed.sql"
                     :name "bad-seed"
                     :sql "INSERT INTO table_that_does_not_exist (value) VALUES ('x');"}]
        (is (= :sql/migration-failed
               (exception-code #(sqlite/stage-database! live stage [failing]))))
        (is (= before (sqlite/logical-hash live-ds)))
        (is (not (fs/exists? stage))))
      (finally
        (fs/delete-tree! root)))))

(deftest action-sql-is-single-family-and-parameter-bound
  (let [root (test-root)]
    (try
      (let [database (.resolve root "actions.sqlite")
            ds (sqlite/init! database)]
        (sqlite/apply-migrations! ds [create-notes])
        (testing "comments and forbidden words inside string data are not code"
          (is (= "-- harmless\nSELECT ? AS value, 'PRAGMA; ATTACH' AS note"
                 (sqlite/validate-action-sql!
                  "-- harmless\nSELECT ? AS value, 'PRAGMA; ATTACH' AS note"
                  [1] false))))
        (let [hostile "x'); DELETE FROM notes; --"]
          (sqlite/mutate! ds "INSERT INTO notes (body) VALUES (?)" [hostile])
          (is (= [{:body hostile}]
                 (sqlite/query! ds "SELECT body FROM notes" []))))
        (doseq [[sql params write?]
                [["SELECT 1; DELETE FROM notes" [] false]
                 ["SELECT * FROM _ppp_runtime_meta" [] false]
                 ["SELECT * FROM sqlite_schema" [] false]
                 ["SELECT ?" [] false]
                 ["DROP TABLE notes" [] true]
                 ["INSERT INTO notes (body) VALUES ('literal')" ["unused"] true]]]
          (is (= :action/sql-not-allowed
                 (exception-code #(sqlite/validate-action-sql! sql params write?)))
              sql)))
      (finally
        (fs/delete-tree! root)))))
