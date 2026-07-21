(ns ppp.repl.service-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ppp.repl.bridge :as bridge]
            [ppp.repl.service :as repl]
            [ppp.runtime.server :as server]
            [ppp.runtime.sqlite :as sqlite]
            [ppp.session.store :as store]
            [ppp.util.fs :as fs])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(deftest workspace-nrepl-evaluates-against-the-running-jvm
  (let [service (repl/start!)
        workspace-a (random-uuid)
        workspace-b (random-uuid)]
    (try
      (testing "successive evaluations retain Vars and mutable state"
        (is (= ["#'ppp.workspace.session_"
                "{:count 1}"]
               (let [defined (repl/eval! service workspace-a
                                         "(defonce state (atom {:count 0}))")
                     changed (repl/eval! service workspace-a
                                         "(swap! state update :count inc)")]
                 [(subs (first (:values defined)) 0 24)
                  (first (:values changed))])))
        (is (= ["1"] (:values (repl/eval! service workspace-a "(:count @state)")))))

      (testing "another workspace receives another namespace and session"
        (is (= ["nil"] (:values (repl/eval! service workspace-b
                                            "(resolve 'state)"))))
        (is (not= (:namespace (repl/workspace-session service workspace-a))
                  (:namespace (repl/workspace-session service workspace-b)))))

      (testing "redefinition changes the next invocation without restart"
        (repl/eval! service workspace-a "(defn score [n] n)")
        (is (= ["3"] (:values (repl/eval! service workspace-a "(score 3)"))))
        (repl/eval! service workspace-a "(defn score [n] (* 3 n))")
        (is (= ["9"] (:values (repl/eval! service workspace-a "(score 3)")))))
      (finally
        (repl/stop! service)))))

(deftest workspace-nrepl-denies-public-bind-and-bounds-code
  (is (= :repl/public-bind-denied
         (:code (ex-data (try
                           (repl/start! {:bind "0.0.0.0"})
                           (catch Exception cause cause))))))
  (let [service (repl/start!)]
    (try
      (is (= :repl/code-invalid
             (:code (ex-data (try
                               (repl/eval! service (random-uuid) "")
                               (catch Exception cause cause))))))
      (is (= :repl/workspace-id-invalid
             (:code (ex-data (try
                               (repl/eval! service "../another-project" "1")
                               (catch Exception cause cause))))))
      (is (= :repl/result-too-large
             (:code (ex-data (try
                               (repl/eval! service (random-uuid)
                                           "(apply str (repeat 300000 \"x\"))")
                               (catch Exception cause cause))))))
      (finally
        (repl/stop! service)))))

(deftest workspace-nrepl-retains-state-across-one-thousand-generated-sequences
  (let [service (repl/start!)
        workspace-id (random-uuid)]
    (try
      (repl/eval! service workspace-id "(defonce generated-state (atom 0))")
      (let [result
            (tc/quick-check
             1000
             (prop/for-all
              [operations (gen/vector (gen/choose -20 20) 0 16)]
              (let [response
                    (repl/eval!
                     service workspace-id
                     (str "(let [before @generated-state"
                          "      after (swap! generated-state + "
                          (reduce + 0 operations) ")]"
                          "  [before after])"))
                    [before after] (edn/read-string (first (:values response)))]
                (= after (+ before (reduce + 0 operations))))))]
        (is (:pass? result)
            (pr-str (select-keys result [:num-tests :seed :fail])))
        (is (= 1000 (:num-tests result))))
      (finally
        (repl/stop! service)))))

(deftest standard-nrepl-session-redefines-the-active-product-server
  (let [root (Files/createTempDirectory "ppp-nrepl-bridge-test"
                                        (make-array FileAttribute 0))
        service (repl/start!)
        registry (server/create-registry)
        workspace-id (random-uuid)
        request-id (random-uuid)]
    (try
      (server/activate!
       registry workspace-id
       (server/stage! {:source-map store/initial-source
                       :database-path (.resolve root "app.sqlite")
                       :version 0
                       :timeout-ms 500}))
      (bridge/register-project! workspace-id registry)
      (bridge/begin-turn! workspace-id request-id)
      (repl/open-workspace! service workspace-id)

      (is (= ["{:runtime-version 0, :actions [:ping], :jobs [], :ingresses []}"]
             (:values
              (repl/eval!
               service workspace-id
               (str "(do (require '[ppp.repl.bridge :as bridge])"
                    " (bridge/describe-server \"" workspace-id "\"))")))))

      (let [response
            (repl/eval!
             service workspace-id
             (str "(ppp.repl.bridge/eval-server! \"" workspace-id "\" "
                  "\"(ns runtime.server (:require [runtime.api :as api]))"
                  " (api/register-action! :ping (fn [_] {:from :nrepl}))\")"))]
        (is (some #(str/includes? % ":value \":ping\"") (:values response))))
      (is (= {:runtime-version 0 :result {:from :nrepl}}
             (server/invoke! registry workspace-id :ping {})))

      (testing "a directly evaluated JVM Var drives the next HTTP action"
        (let [form
              (read-string
               (str "(do"
                    " (defn live-ping [_] {:from :jvm-var :revision 1})"
                    " (ppp.repl.bridge/call-runtime! \"" workspace-id
                    "\" 'register-action! :ping #'live-ping))"))]
          (bridge/eval-server! workspace-id form)
          (is (= {:runtime-version 0
                  :result {:from :jvm-var :revision 1}}
                 (server/invoke! registry workspace-id :ping {})))
          (repl/eval! service workspace-id
                      "(defn live-ping [_] {:from :jvm-var :revision 2})")
          (is (= {:runtime-version 0
                  :result {:from :jvm-var :revision 2}}
                 (server/invoke! registry workspace-id :ping {})))))
      (is (some #(str/includes? % ":from :jvm-var")
                (:values
                 (repl/eval!
                  service workspace-id
                  (str "(ppp.repl.bridge/invoke-server! \"" workspace-id
                       "\" :ping {})")))))
      (let [events (bridge/finish-turn! workspace-id request-id)]
        (is (= [:server/inspect :server/eval :server/eval :server/invoke]
               (mapv :operation events)))
        (is (= [:sci-guest :jvm-var]
               (->> events
                    (filter #(= :server/eval (:operation %)))
                    (mapv :evaluation-realm)))))
      (finally
        (bridge/unregister-project! workspace-id)
        (repl/stop! service)
        (fs/delete-tree! root)))))

(deftest workspace-nrepl-can-inspect-and-repair-its-product-auth-database
  (let [root (Files/createTempDirectory "ppp-nrepl-database-test"
                                        (make-array FileAttribute 0))
        registry (server/create-registry)
        workspace-id (random-uuid)
        request-id (random-uuid)
        database-path (.resolve root "app.sqlite")]
    (try
      (server/activate!
       registry workspace-id
       (server/stage! {:source-map store/initial-source
                       :database-path database-path
                       :version 0
                       :timeout-ms 500}))
      (let [database (sqlite/datasource database-path)]
        (sqlite/execute!
         database
         ["CREATE TABLE _ppp_auth_users
             (id TEXT PRIMARY KEY, identifier_display TEXT, password_hash TEXT)"])
        (sqlite/execute!
         database
         ["INSERT INTO _ppp_auth_users
             (id, identifier_display, password_hash) VALUES (?, ?, ?)"
          "user-1" "Player One" "fake-argon-hash"]))
      (bridge/register-project! workspace-id registry)
      (bridge/begin-turn! workspace-id request-id)

      (let [table-names
            (set (map :name
                      (:tables
                       (bridge/inspect-workspace-database workspace-id))))]
        (is (contains? table-names "_ppp_auth_users"))
        (is (not (contains? table-names "_ppp_runtime_meta")))
        (is (not (contains? table-names "_ppp_migrations"))))
      (is (= [{:id "user-1"
               :identifier_display "Player One"
               :password_hash "fake-argon-hash"}]
             (bridge/query-workspace-database!
              workspace-id
              "SELECT id, identifier_display, password_hash FROM _ppp_auth_users"
              [])))
      (bridge/mutate-workspace-database!
       workspace-id
       "UPDATE _ppp_auth_users SET identifier_display = ? WHERE id = ?"
       ["Player Prime" "user-1"])
      (is (= [{:identifier_display "Player Prime"}]
             (bridge/query-workspace-database!
              workspace-id
              "SELECT identifier_display FROM _ppp_auth_users WHERE id = ?"
              ["user-1"])))
      (is (= :repl/database-control-plane-denied
             (:code
              (ex-data
               (try
                 (bridge/query-workspace-database!
                  workspace-id
                  "SELECT runtime_version FROM _ppp_runtime_meta"
                  [])
                 (catch Exception cause cause))))))
      (is (= [:database/inspect :database/query :database/mutate
              :database/query :database/query]
             (mapv :operation (bridge/finish-turn! workspace-id request-id))))
      (finally
        (bridge/unregister-project! workspace-id)
        (fs/delete-tree! root)))))

(deftest rejected-repl-branch-never-mutates-the-public-active-runtime
  (let [root (Files/createTempDirectory "ppp-nrepl-branch-test"
                                        (make-array FileAttribute 0))
        active-registry (server/create-registry)
        branch-registry (server/create-registry)
        workspace-id (random-uuid)
        request-id (random-uuid)
        active-db (.resolve root "active.sqlite")
        branch-db (.resolve root "branch.sqlite")]
    (try
      (server/activate!
       active-registry workspace-id
       (server/stage! {:source-map store/initial-source
                       :database-path active-db
                       :version 0
                       :timeout-ms 500}))
      (server/activate!
       branch-registry workspace-id
       (server/stage! {:source-map store/initial-source
                       :source-database-path active-db
                       :database-path branch-db
                       :version 0
                       :timeout-ms 500}))
      (bridge/register-project! workspace-id branch-registry)
      (bridge/begin-turn! workspace-id request-id)

      (is (= ":ping"
             (:value
              (bridge/eval-server!
               workspace-id
               (str "(ns runtime.server (:require [runtime.api :as api]))\n"
                    "(api/register-action! :ping (fn [_] {:branch true}))")))))
      (is (= {:runtime-version 0 :result {:branch true}}
             (server/invoke! branch-registry workspace-id :ping {})))
      (is (= {:runtime-version 0
              :result {:ok true :message "The runtime is ready."}}
             (server/invoke! active-registry workspace-id :ping {})))
      (is (= [:server/eval]
             (mapv :operation
                   (bridge/finish-turn! workspace-id request-id))))
      (finally
        (bridge/unregister-project! workspace-id)
        (fs/delete-tree! root)))))
