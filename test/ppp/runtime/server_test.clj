(ns ppp.runtime.server-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ppp.runtime.policy :as policy]
            [ppp.runtime.server :as server]
            [ppp.runtime.sqlite :as sqlite]
            [ppp.session.store :as store]
            [ppp.util.fs :as fs])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def domain-source "(ns runtime.domain)\n")

(def domain-test-source
  "(ns runtime.domain-test (:require [clojure.test :refer [deftest is]]))\n(deftest runtime-smoke (is true))\n")

(def notes-migration
  {:file-name "000001-create-notes.sql"
   :name "create-notes"
   :sql "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT NOT NULL);"})

(defn- test-root
  []
  (Files/createTempDirectory "ppp-server-test"
                             (make-array FileAttribute 0)))

(defn- source-map
  [server-source]
  {"src/shared/runtime/domain.cljc" domain-source
   "src/server/runtime/server.clj" server-source
   "test/runtime/domain_test.cljc" domain-test-source})

(defn- exception
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo cause
      cause)))

(deftest initial-runtime-stages-registers-and-invokes
  (let [root (test-root)]
    (try
      (let [runtime (server/stage! {:source-map store/initial-source
                                    :database-path (.resolve root "initial.sqlite")
                                    :version 0
                                    :timeout-ms 500})
            registry (server/create-registry)
            session-id (random-uuid)]
        (is (= [:ping] (server/action-ids runtime)))
        (server/activate! registry session-id runtime)
        (is (= {:runtime-version 0
                :result {:ok true :message "The runtime is ready."}}
               (server/invoke! registry session-id :ping {}))))
      (finally
        (fs/delete-tree! root)))))

(deftest client-only-stage-reuses-actions-and-context-against-an-atomic-database-copy
  (let [root (test-root)]
    (try
      (let [live-database (.resolve root "live.sqlite")
            staged-database (.resolve root "client-stage.sqlite")
            runtime (server/stage! {:source-map store/initial-source
                                    :database-path live-database
                                    :version 0
                                    :timeout-ms 500})
            logical-before (sqlite/logical-hash (:database runtime))
            reused (server/reuse-for-client-stage! runtime live-database
                                                   staged-database 1)
            registry (server/create-registry)
            session-id (random-uuid)]
        (is (= 1 (:version reused)))
        (is (identical? (:context runtime) (:context reused)))
        (is (identical? (:actions runtime) (:actions reused)))
        (is (= [:ping] (server/action-ids reused)))
        (is (= logical-before (sqlite/logical-hash (:database reused))))
        (server/activate! registry session-id reused)
        (is (= {:runtime-version 1
                :result {:ok true :message "The runtime is ready."}}
               (server/invoke! registry session-id :ping {}))))
      (finally
        (fs/delete-tree! root)))))

(deftest generated-domain-tests-run-against-staged-actions-and-always-roll-back
  (let [root (test-root)
        server-source
        "(ns runtime.server (:require [runtime.api :as api]))\n(defn add [{:keys [body]}] (api/execute! \"INSERT INTO notes (body) VALUES (?)\" [body]) {:notes (api/query! \"SELECT body FROM notes ORDER BY id\" [])})\n(defn list-notes [_] {:notes (api/query! \"SELECT body FROM notes ORDER BY id\" [])})\n(api/register-action! :notes/add add)\n(api/register-action! :notes/list list-notes)\n"
        test-source
        "(ns runtime.domain-test (:require [clojure.test :refer [deftest is testing]] [runtime.test :as runtime-test]))\n(deftest note-contract\n  (testing \"mutation changes the read model\"\n    (is (= [] (:notes (runtime-test/invoke! :notes/list {}))))\n    (is (= [{:body \"kept only inside the test\"}]\n           (:notes (runtime-test/invoke! :notes/add {:body \"kept only inside the test\"}))))\n    (is (= [{:body \"kept only inside the test\"}]\n           (:notes (runtime-test/invoke! :notes/list {}))))))\n"]
    (try
      (let [runtime
            (server/stage!
             {:source-map (assoc (source-map server-source)
                                 "test/runtime/domain_test.cljc" test-source)
              :database-path (.resolve root "tested.sqlite")
              :migrations [notes-migration]
              :version 1})
            registry (server/create-registry)
            session-id (random-uuid)]
        (server/activate! registry session-id runtime)
        (is (= [] (get-in (server/invoke! registry session-id :notes/list {})
                          [:result :notes]))))
      (finally
        (fs/delete-tree! root)))))

(deftest failed-generated-domain-test-rejects-staging-without-source-details
  (let [root (test-root)]
    (try
      (let [cause
            (exception
             #(server/stage!
               {:source-map
                (assoc (source-map "(ns runtime.server)\n")
                       "test/runtime/domain_test.cljc"
                       "(ns runtime.domain-test (:require [clojure.test :refer [deftest is]]))\n(deftest business-rule (is (= 0 1)))\n")
                :database-path (.resolve root "failed-domain-test.sqlite")
                :version 1}))]
        (is (= :runtime/generated-tests-failed (:code (ex-data cause))))
        (is (= [{:test "runtime.domain-test/business-rule"
                 :assertions 1
                 :fail 1
                 :error 0}]
               (:failed-tests (ex-data cause))))
        (is (not (contains? (ex-data cause) :source))))
      (finally
        (fs/delete-tree! root)))))

(deftest committed-runtime-load-can-skip-replaying-tests-against-live-data
  (let [root (test-root)]
    (try
      (let [runtime
            (server/stage!
             {:source-map
              (assoc (source-map "(ns runtime.server)\n")
                     "test/runtime/domain_test.cljc"
                     "(ns runtime.domain-test (:require [clojure.test :refer [deftest is]]))\n(deftest stale-live-data-assumption (is (= 0 1)))\n")
              :database-path (.resolve root "committed.sqlite")
              :version 4
              :run-tests? false})]
        (is (= 4 (:version runtime))))
      (finally
        (fs/delete-tree! root)))))

(deftest staging-never-replaces-the-active-runtime
  (let [root (test-root)]
    (try
      (let [registry (server/create-registry)
            session-id (random-uuid)
            active (server/stage! {:source-map store/initial-source
                                   :database-path (.resolve root "active.sqlite")
                                   :version 0
                                   :timeout-ms 500})]
        (server/activate! registry session-id active)
        (let [started (System/nanoTime)
              cause (exception
                     #(server/stage!
                       {:source-map
                        (source-map "(ns runtime.server)\n(loop [] (recur))\n")
                        :database-path (.resolve root "failed.sqlite")
                        :version 1
                        :timeout-ms 25}))
              elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)]
          (is (= :runtime/server-stage-failed (:code (ex-data cause))))
          (is (= :runtime/timeout (:cause-code (ex-data cause))))
          (is (< elapsed-ms 2000.0))
          (is (identical? active (server/runtime-for registry session-id)))
          (is (= 0 (:runtime-version
                    (server/invoke! registry session-id :ping {}))))))
      (finally
        (fs/delete-tree! root)))))

(deftest server-sci-capability-escape-property
  (let [root (test-root)]
    (try
      (let [forms ["(System/getenv \"HOME\")"
                   "(java.io.File. \"/tmp/private\")"
                   "(slurp \"/etc/passwd\")"
                   "(load-string \"(+ 1 2)\")"
                   "(eval '(+ 1 2))"
                   "(future 1)"
                   "(.getClass \"value\")"
                   "(resolve 'runtime.api/query!)"
                   "(alter-var-root #'map identity)"]
            sequence (atom 0)
            result
            (tc/quick-check
             100
             (prop/for-all [form (gen/elements forms)]
                           (let [index (swap! sequence inc)
                                 cause
                                 (exception
                                  #(server/stage!
                                    {:source-map
                                     (source-map
                                      (str "(ns runtime.server)\n(def escape " form ")\n"))
                                     :database-path (.resolve root (str "escape-" index ".sqlite"))
                                     :version 1
                                     :timeout-ms 250}))]
                             (= :runtime/server-stage-failed
                                (:code (ex-data cause))))))]
        (is (:pass? result) (pr-str (dissoc result :result-data))))
      (finally
        (fs/delete-tree! root)))))

(deftest staging-phase-cannot-read-or-write-database
  (let [root (test-root)]
    (try
      (let [cause
            (exception
             #(server/stage!
               {:source-map
                (source-map
                 "(ns runtime.server (:require [runtime.api :as api]))\n(api/query! \"SELECT 1\" [])\n")
                :database-path (.resolve root "phase.sqlite")
                :version 1}))]
        (is (= :runtime/server-stage-failed (:code (ex-data cause))))
        (is (= :runtime/capability-phase (:cause-code (ex-data cause)))))
      (finally
        (fs/delete-tree! root)))))

(deftest required-entrypoints-and-duplicate-actions-fail-closed
  (let [root (test-root)]
    (try
      (is (= :runtime/missing-entrypoint
             (:code
              (ex-data
               (exception
                #(server/stage! {:source-map {"src/server/runtime/server.clj"
                                              "(ns runtime.server)"}
                                 :database-path (.resolve root "missing.sqlite")
                                 :version 1}))))))
      (let [cause
            (exception
             #(server/stage!
               {:source-map
                (source-map
                 "(ns runtime.server (:require [runtime.api :as api]))\n(api/register-action! :same (fn [_] 1))\n(api/register-action! :same (fn [_] 2))\n")
                :database-path (.resolve root "duplicate.sqlite")
                :version 1}))]
        (is (= :runtime/duplicate-action (:cause-code (ex-data cause)))))
      (finally
        (fs/delete-tree! root)))))

(deftest action-transaction-rolls-back-and-success-persists
  (let [root (test-root)]
    (try
      (let [runtime
            (server/stage!
             {:source-map
              (source-map
               "(ns runtime.server (:require [runtime.api :as api]))\n(defn add [{:keys [body]}] (api/execute! \"INSERT INTO notes (body) VALUES (?)\" [body]) {:ok true})\n(defn add-then-fail [{:keys [body]}] (api/execute! \"INSERT INTO notes (body) VALUES (?)\" [body]) (throw (ex-info \"rollback\" {:code :notes/rejected})))\n(defn list-notes [_] {:notes (api/query! \"SELECT body FROM notes ORDER BY id\" [])})\n(api/register-action! :notes/add add)\n(api/register-action! :notes/fail add-then-fail)\n(api/register-action! :notes/list list-notes)\n")
              :database-path (.resolve root "actions.sqlite")
              :migrations [notes-migration]
              :version 1})
            registry (server/create-registry)
            session-id (random-uuid)]
        (server/activate! registry session-id runtime)
        (is (= :notes/rejected
               (:code (ex-data
                       (exception #(server/invoke! registry session-id
                                                   :notes/fail {:body "no"}))))))
        (is (= [] (get-in (server/invoke! registry session-id :notes/list {})
                          [:result :notes])))
        (is (= {:ok true}
               (:result (server/invoke! registry session-id
                                        :notes/add {:body "yes"}))))
        (is (= [{:body "yes"}]
               (get-in (server/invoke! registry session-id :notes/list {})
                       [:result :notes]))))
      (finally
        (fs/delete-tree! root)))))

(deftest action-sql-must-be-a-static-source-template
  (let [root (test-root)]
    (try
      (let [runtime
            (server/stage!
             {:source-map
              (source-map
               "(ns runtime.server (:require [runtime.api :as api]))\n(def safe-query \"SELECT body FROM notes WHERE body = ?\")\n(defn safe [{:keys [body]}] (api/query! safe-query [body]))\n(defn unsafe [{:keys [predicate]}] (api/query! (str \"SELECT body FROM notes WHERE \" predicate) []))\n(api/register-action! :notes/safe safe)\n(api/register-action! :notes/unsafe unsafe)\n")
              :database-path (.resolve root "static-sql.sqlite")
              :migrations [notes-migration]
              :version 1})
            registry (server/create-registry)
            session-id (random-uuid)]
        (server/activate! registry session-id runtime)
        (is (= [] (:result (server/invoke! registry session-id
                                           :notes/safe {:body "missing"}))))
        (let [cause (exception #(server/invoke! registry session-id
                                                :notes/unsafe
                                                {:predicate "1 = 1"}))]
          (is (= :action/sql-not-declared (:code (ex-data cause))))))
      (finally
        (fs/delete-tree! root)))))

(deftest action-time-and-response-contracts-are-bounded
  (let [root (test-root)]
    (try
      (let [runtime
            (server/stage!
             {:source-map
              (source-map
               "(ns runtime.server (:require [runtime.api :as api]))\n(api/register-action! :loop (fn [_] (loop [] (recur))))\n(api/register-action! :large (fn [_] (apply str (repeat 500 \"x\"))))\n(api/register-action! :function (fn [_] (fn [] :not-data)))\n")
              :database-path (.resolve root "bounded.sqlite")
              :version 1
              :timeout-ms 25
              :response-limit 128})
            registry (server/create-registry)
            session-id (random-uuid)]
        (server/activate! registry session-id runtime)
        (is (= :runtime/timeout
               (:code (ex-data
                       (exception #(server/invoke! registry session-id :loop {}))))))
        (is (= :runtime/response-too-large
               (:code (ex-data
                       (exception #(server/invoke! registry session-id :large {}))))))
        (is (= :runtime/response-invalid
               (:code (ex-data
                       (exception #(server/invoke! registry session-id :function {})))))))
      (finally
        (fs/delete-tree! root)))))

(deftest capability-inventory-is-derived-from-the-catalog
  (let [inventory (server/capability-inventory)]
    (is (= (set (map name (get-in policy/capability-catalog
                                  [:server :namespaces 'runtime.api])))
           (set (:runtime.api inventory))))
    (is (= (set (map name (get-in policy/capability-catalog
                                  [:server :namespaces 'clojure.string])))
           (set (:clojure.string inventory))))
    (is (= (set (map str policy/server-denied-symbols))
           (set (:denied inventory))))))

(deftest outbound-capabilities-are-host-functions-limited-to-action-scope
  (let [root (test-root)]
    (try
      (let [calls (atom [])
            runtime
            (server/stage!
             {:source-map
              (source-map
               "(ns runtime.server (:require [runtime.api :as api]))\n(defn network [_]\n  {:public (api/public-http! {:method :get :url \"https://example.com/public\"})\n   :connector (api/connector-http! :issues {:method :get :path \"/v1/issues/1\"})})\n(api/register-action! :network network)\n")
              :database-path (.resolve root "network.sqlite")
              :version 1
              :public-http-fn
              (fn [request]
                (swap! calls conj [:public request])
                {:status 200 :body "public"})
              :connector-http-fn
              (fn [alias request]
                (swap! calls conj [:connector alias request])
                {:status 200 :body "connector"})})
            registry (server/create-registry)
            session-id (random-uuid)]
        (server/activate! registry session-id runtime)
        (is (= {:public {:status 200 :body "public"}
                :connector {:status 200 :body "connector"}}
               (:result (server/invoke! registry session-id :network {}))))
        (is (= [[:public {:method :get
                          :url "https://example.com/public"}]
                [:connector :issues
                 {:method :get :path "/v1/issues/1"}]]
               @calls)))
      (finally
        (fs/delete-tree! root)))))

(deftest outbound-capability-cannot-run-during-staging
  (let [root (test-root)]
    (try
      (let [cause
            (exception
             #(server/stage!
               {:source-map
                (source-map
                 "(ns runtime.server (:require [runtime.api :as api]))\n(api/public-http! {:method :get :url \"https://example.com\"})\n")
                :database-path (.resolve root "network-stage.sqlite")
                :version 1
                :public-http-fn (constantly {:status 200})}))]
        (is (= :runtime/server-stage-failed (:code (ex-data cause))))
        (is (= :runtime/capability-phase (:cause-code (ex-data cause)))))
      (finally
        (fs/delete-tree! root)))))

(deftest generated-tests-cannot-use-outbound-capabilities
  (let [root (test-root)
        calls (atom [])]
    (try
      (let [cause
            (exception
             #(server/stage!
               {:source-map
                (assoc
                 (source-map
                  "(ns runtime.server (:require [runtime.api :as api]))\n(api/register-action! :network (fn [_] (api/public-http! {:method :get :url \"https://example.com\"})))\n")
                 "test/runtime/domain_test.cljc"
                 "(ns runtime.domain-test (:require [clojure.test :refer [deftest is]] [runtime.test :as runtime-test]))\n(deftest no-validation-network (is (= 200 (:status (runtime-test/invoke! :network {})))))\n")
                :database-path (.resolve root "test-network.sqlite")
                :version 1
                :public-http-fn (fn [request]
                                  (swap! calls conj request)
                                  {:status 200})}))]
        (is (= :runtime/generated-tests-failed (:code (ex-data cause))))
        (is (empty? @calls)))
      (finally
        (fs/delete-tree! root)))))
