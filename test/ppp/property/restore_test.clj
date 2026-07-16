(ns ppp.property.restore-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ppp.runtime.sqlite :as sqlite]
            [ppp.session.store :as store]
            [ppp.util.fs :as fs])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- context
  []
  (let [root (Files/createTempDirectory "ppp-restore-property"
                                        (make-array FileAttribute 0))
        config {:data-dir root
                :source-file-limit 32
                :source-byte-limit (* 256 1024)
                :session-db-limit (* 25 1024 1024)
                :checkpoint-limit (* 256 1024 1024)
                :instance-limit (* 2 1024 1024 1024)}]
    {:root root :store (store/create-store config)}))

(defn- create-checkpoint-a!
  [session-store session-id]
  (let [stage
        (store/stage-change!
         session-store session-id (random-uuid)
         {:title "Checkpoint A"
          :writes [{:path "styles/checkpoint-a.css"
                    :content ".checkpoint-a { display: block; }\n"}]
          :deletes []
          :migrations
          [{:name "create-items"
            :sql (str "CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT NOT NULL);\n"
                      "INSERT INTO items (body) VALUES ('checkpoint-a');")}]})]
    (sqlite/stage-database! (store/current-db-path session-store session-id)
                            (:database stage)
                            (:assigned-migrations stage))
    (let [materialized (store/materialize-stage! session-store session-id stage)]
      (store/update-session! session-store session-id {:current-version 1})
      (store/create-checkpoint! session-store session-id
                                {:title "Checkpoint A" :kind :change})
      (store/finalize-materialized! session-store session-id materialized))))

(def operation-gen
  (gen/vector
   (gen/tuple (gen/elements [:insert :update :delete])
              gen/string-alphanumeric
              (gen/choose 1 32))
   0 3))

(defn- apply-operations!
  [database operations]
  (doseq [[operation value row-id] operations]
    (case operation
      :insert (sqlite/mutate! database "INSERT INTO items (body) VALUES (?)"
                              [(str "changed-" value)])
      :update (sqlite/mutate! database "UPDATE items SET body = ? WHERE id = ?"
                              [(str "updated-" value) row-id])
      :delete (sqlite/mutate! database "DELETE FROM items WHERE id = ?" [row-id])))
  true)

(deftest restore-a-after-generated-valid-data-changes-restages-a
  (let [{:keys [root store]} (context)]
    (try
      (let [session-id (:id (store/create-session! store))]
        (create-checkpoint-a! store session-id)
        (let [expected-source (store/current-source-map store session-id)
              expected-database-hash
              (get-in (store/read-checkpoint store session-id 1)
                      [:metadata :database-content-hash])
              live-database (sqlite/datasource (store/current-db-path store session-id))
              result
              (tc/quick-check
               1000
               (prop/for-all
                [operations operation-gen]
                (do
                  (apply-operations! live-database operations)
                  (let [stage (store/stage-restore! store session-id (random-uuid) 1)]
                    (try
                      (and (= 2 (get-in stage [:manifest :runtime-version]))
                           (= expected-source (store/stage-source-map stage))
                           (= expected-database-hash
                              (sqlite/logical-hash
                               (sqlite/datasource (:database stage)))))
                      (finally
                        (store/discard-stage! stage))))))
               :seed 8003)]
          (is (= 1000 (:num-tests result)))
          (is (:pass? result) (pr-str (dissoc result :result-data)))))
      (finally
        (fs/delete-tree! root)))))
