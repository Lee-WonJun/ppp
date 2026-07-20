(ns ppp.runtime.server-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ppp.runtime.auth :as auth]
            [ppp.runtime.policy :as policy]
            [ppp.runtime.resources :as resources]
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

(def auth-profile-migration
  {:file-name "000001-create-product-profiles.sql"
   :name "create-product-profiles"
   :sql "CREATE TABLE product_profiles (user_id TEXT PRIMARY KEY, display_name TEXT NOT NULL);"})

(defn- product-auth-service
  []
  (auth/create-service
   {:cookie-secret "server-runtime-auth-test-secret-with-more-than-32-characters"
    :cookie-secure? false
    :product-auth-session-seconds 3600}
   {:hash-options {:memory-kib 7168 :iterations 1 :parallelism 1}
    :allow-weak-test-parameters? true}))

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

(deftest live-repl-redefines-the-running-action-and-retains-state
  (let [root (test-root)]
    (try
      (let [runtime (server/stage! {:source-map store/initial-source
                                    :database-path (.resolve root "repl.sqlite")
                                    :version 0
                                    :timeout-ms 500})
            registry (server/create-registry)
            session-id (random-uuid)]
        (server/activate! registry session-id runtime)
        (is (= {:runtime-version 0
                :result {:ok true :message "The runtime is ready."}}
               (server/invoke! registry session-id :ping {})))

        (is (= "1"
               (:value
                (server/eval-live!
                 registry session-id
                 (str "(ns runtime.server (:require [runtime.api :as api]))\n"
                      "(defonce repl-count (atom 0))\n"
                      "(swap! repl-count inc)\n"
                      "(api/register-action! :ping"
                      " (fn [_] {:ok true :count @repl-count}))\n"
                      "@repl-count")))))
        (is (= {:runtime-version 0 :result {:ok true :count 1}}
               (server/invoke! registry session-id :ping {})))

        (is (= "2"
               (:value (server/eval-live! registry session-id
                                          "(swap! runtime.server/repl-count inc)"))))
        (is (= {:runtime-version 0 :result {:ok true :count 2}}
               (server/invoke! registry session-id :ping {})))

        (is (= {:file-name "000001-counter.sql"
                :name "counter"
                :status :applied}
               (server/migrate-live!
                registry session-id "counter"
                (str "CREATE TABLE counter (id INTEGER PRIMARY KEY, value INTEGER NOT NULL);"
                     "INSERT INTO counter (id, value) VALUES (1, 0);"))))
        (server/eval-live!
         registry session-id
         (str "(ns runtime.server (:require [runtime.api :as api]))\n"
              "(api/register-action! :counter/add "
              "(fn [_] (api/execute! \"UPDATE counter SET value = value + 1 WHERE id = 1\" []) "
              "{:total (:value (first (api/query! \"SELECT value FROM counter WHERE id = 1\" []))) }))"))
        (is (= {:runtime-version 0 :result {:total 1}}
               (server/invoke! registry session-id :counter/add {})))

        (is (= :repl/eval-failed
               (:code (ex-data
                       (exception #(server/eval-live! registry session-id
                                                      "(defn broken ["))))))
        (is (= {:runtime-version 0 :result {:ok true :count 2}}
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

(deftest resource-plane-runs-through-actions-jobs-ingress-and-post-commit-effects
  (let [root (test-root)]
    (try
      (let [session-id (random-uuid)
            runtime
            (server/stage!
             {:source-map
              (assoc
               (source-map
                (str
                 "(ns runtime.server (:require [runtime.api :as api]))\n"
                 "(defn seed! [_]\n"
                 "  (api/blob-put! {:id \"hello\" :name \"hello.txt\" :content-type \"text/plain\" :content-base64 \"aGVsbG8=\"})\n"
                 "  (api/search-upsert! :notes \"hello\" {:text \"안녕 live product\" :metadata {:kind :demo} :vector [1.0 0.0]})\n"
                 "  (let [job (api/schedule-job! :notes/enrich {:id \"hello\"} {:idempotency-key \"hello\"})]\n"
                 "    (api/publish! :notes/changed {:id \"hello\"})\n"
                 "    {:job job :objects (api/blob-list) :matches (api/search-query :notes \"안녕\")}))\n"
                 "(defn enrich! [{:keys [id]}]\n"
                 "  (api/search-upsert! :notes id {:text \"안녕 enriched\" :metadata {:kind :job}})\n"
                 "  (api/publish! :notes/enriched {:id id})\n"
                 "  {:enriched id})\n"
                 "(defn hook! [request]\n"
                 "  (api/publish! :hooks/received {:method (:method request)})\n"
                 "  {:status 202 :body {:accepted true :request-body (:body request)}})\n"
                 "(api/register-action! :resources/seed seed!)\n"
                 "(api/register-job! :notes/enrich enrich!)\n"
                 "(api/register-ingress! :hook {} hook!)\n"))
               "test/runtime/domain_test.cljc"
               (str
                "(ns runtime.domain-test (:require [clojure.test :refer [deftest is]] [runtime.test :as runtime-test]))\n"
                "(deftest complete-resource-contract\n"
                "  (let [seeded (runtime-test/invoke! :resources/seed {})\n"
                "        job-result (runtime-test/invoke-job! :notes/enrich {:id \"hello\"})\n"
                "        ingress-result (runtime-test/invoke-ingress! :hook {:method :post :body {:value 1}})]\n"
                "    (is (= \"hello\" (get-in seeded [:objects 0 :id])))\n"
                "    (is (= {:enriched \"hello\"} job-result))\n"
                "    (is (= 202 (:status ingress-result)))))\n"))
              :database-path (.resolve root "resources.sqlite")
              :session-id session-id
              :version 0
              :timeout-ms 1000
              :response-limit (* 7 1024 1024)})
            registry (server/create-registry)]
        (is (= [:notes/enrich] (server/job-ids runtime)))
        (is (= [:hook] (server/ingress-routes runtime)))
        (server/activate! registry session-id runtime)
        (let [result (server/invoke! registry session-id :resources/seed {})]
          (is (= "hello" (get-in result [:result :objects 0 :id])))
          (is (= "hello" (get-in result [:result :matches 0 :id])))
          (is (= [{:op :product-event
                   :topic :notes/changed
                   :payload {:id "hello"}}]
                 (:effects result))))
        (let [claimed (server/claim-job! registry session-id)
              result (server/run-job! registry session-id claimed)]
          (is (= :notes/enrich (:handler claimed)))
          (is (= {:enriched "hello"} (:result result)))
          (is (= :notes/enriched (get-in result [:effects 0 :topic]))))
        (let [result (server/invoke-ingress! registry session-id :hook
                                             {:method :post :body {:value 1}})]
          (is (= 202 (get-in result [:result :status])))
          (is (= {:value 1} (get-in result [:result :body :request-body])))
          (is (= :hooks/received (get-in result [:effects 0 :topic])))))
      (finally
        (fs/delete-tree! root)))))

(deftest database-quota-rolls-back-a-resource-replacement
  (let [root (test-root)]
    (try
      (let [database-path (.resolve root "resource-quota.sqlite")
            runtime
            (server/stage!
             {:source-map
              (source-map
               (str
                "(ns runtime.server (:require [runtime.api :as api]))\n"
                "(defn store! [{:keys [content]}] (api/blob-put! {:id \"kept\" :name \"kept.bin\" :content-type \"application/octet-stream\" :content-base64 content}))\n"
                "(defn objects [_] {:objects (api/blob-list)})\n"
                "(api/register-action! :objects/store store!)\n"
                "(api/register-action! :objects/list objects)\n"))
              :database-path database-path
              :version 1})
            registry (server/create-registry)
            session-id (random-uuid)]
        (server/activate! registry session-id runtime)
        (server/invoke! registry session-id :objects/store {:content "AQ=="})
        (let [before (sqlite/logical-hash (:database runtime))
              page-count (:page_count
                          (sqlite/execute-one! (:database runtime)
                                               ["PRAGMA page_count"]))
              page-size (:page_size
                         (sqlite/execute-one! (:database runtime)
                                              ["PRAGMA page_size"]))
              bounded (assoc runtime :database-size-limit
                             (+ (* page-count page-size) page-size))]
          (server/activate! registry session-id bounded)
          (let [cause (exception
                       #(server/invoke! registry session-id :objects/store
                                        {:content (apply str (repeat 150000 "A"))}))]
            (is (= :storage/quota-exceeded (:code (ex-data cause)))))
          (is (= before (sqlite/logical-hash (:database bounded))))
          (is (= 1 (get-in (server/invoke! registry session-id :objects/list {})
                           [:result :objects 0 :size])))))
      (finally
        (fs/delete-tree! root)))))

(deftest actions-cannot-schedule-an-unregistered-background-handler
  (let [root (test-root)]
    (try
      (let [runtime
            (server/stage!
             {:source-map
              (source-map
               (str
                "(ns runtime.server (:require [runtime.api :as api]))\n"
                "(api/register-action! :jobs/invalid (fn [_] (api/schedule-job! :jobs/missing {})))\n"))
              :database-path (.resolve root "unregistered-job.sqlite")
              :version 1})
            registry (server/create-registry)
            session-id (random-uuid)]
        (server/activate! registry session-id runtime)
        (is (= :job/handler-not-found
               (:code (ex-data
                       (exception #(server/invoke! registry session-id
                                                   :jobs/invalid {}))))))
        (is (nil? (server/claim-job! registry session-id))))
      (finally
        (fs/delete-tree! root)))))

(deftest a-durable-job-whose-handler-was-removed-becomes-terminal
  (let [root (test-root)]
    (try
      (let [database-path (.resolve root "removed-job-handler.sqlite")
            runtime-one
            (server/stage!
             {:source-map
              (source-map
               (str
                "(ns runtime.server (:require [runtime.api :as api]))\n"
                "(api/register-job! :jobs/old (fn [_] {:ok true}))\n"
                "(api/register-action! :jobs/schedule (fn [_] (api/schedule-job! :jobs/old {} {:max-attempts 1})))\n"))
              :database-path database-path
              :version 1})
            registry (server/create-registry)
            session-id (random-uuid)]
        (server/activate! registry session-id runtime-one)
        (let [job-id (get-in (server/invoke! registry session-id :jobs/schedule {})
                             [:result :id])
              runtime-two (server/stage! {:source-map store/initial-source
                                          :database-path database-path
                                          :version 2})]
          (server/activate! registry session-id runtime-two)
          (let [claimed (server/claim-job! registry session-id)
                cause (exception #(server/run-job! registry session-id claimed))]
            (is (= job-id (:id claimed)))
            (is (= :job/handler-not-found (:code (ex-data cause))))
            (is (= :failed
                   (:status (resources/job-status (:resource-service runtime-two)
                                                  (:database runtime-two)
                                                  job-id)))))))
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

(deftest generated-product-identity-is-atomic-private-and-request-scoped
  (let [root (test-root)
        session-id (random-uuid)
        auth-service (product-auth-service)
        server-source
        (str
         "(ns runtime.server (:require [runtime.api :as api]))\n"
         "(defn signup [{:keys [identifier password display-name]}] (let [user (api/auth-register! {:identifier identifier :password password})] (api/execute! \"INSERT INTO product_profiles (user_id, display_name) VALUES (?, ?)\" [(:id user) display-name]) {:user user :profile {:display-name display-name}}))\n"
         "(defn login [{:keys [identifier password]}] {:user (api/auth-login! {:identifier identifier :password password})})\n"
         "(defn logout [_] (api/auth-logout!) {:signed-out true})\n"
         "(defn me [_] (let [user (api/auth-require-user!) profile (first (api/query! \"SELECT display_name FROM product_profiles WHERE user_id = ?\" [(:id user)]))] {:user user :profile profile}))\n"
         "(api/register-action! :accounts/signup signup)\n"
         "(api/register-action! :accounts/login login)\n"
         "(api/register-action! :accounts/logout logout)\n"
         "(api/register-action! :accounts/me me)\n")
        test-source
        (str
         "(ns runtime.domain-test (:require [clojure.test :refer [deftest is]] [runtime.test :as runtime-test]))\n"
         "(deftest account-contract (let [created (runtime-test/invoke! :accounts/signup {:identifier \"stage-owner\" :password \"stage password\" :display-name \"Stage Owner\"}) user (:user created)] (is (string? (:id user))) (is (= \"Stage Owner\" (get-in (runtime-test/invoke-as! (:id user) :accounts/me {}) [:profile :display_name])))))\n")]
    (try
      (let [runtime
            (server/stage!
             {:source-map (assoc (source-map server-source)
                                 "test/runtime/domain_test.cljc" test-source)
              :database-path (.resolve root "product-auth.sqlite")
              :migrations [auth-profile-migration]
              :version 1
              :session-id session-id
              :auth-service auth-service})
            registry (server/create-registry)]
        (server/activate! registry session-id runtime)
        (let [created (server/invoke! registry session-id :accounts/signup
                                      {:identifier "Owner"
                                       :password "owner password"
                                       :display-name "Product Owner"})
              token (get-in created [:effects 0 :token])
              user (get-in created [:result :user])]
          (is (string? token))
          (is (= "Owner" (:identifier user)))
          (is (nil? (:password user)))
          (is (nil? (:token (:result created))))
          (is (= "Product Owner"
                 (get-in (server/invoke! registry session-id :accounts/me {}
                                         {:auth-token token})
                         [:result :profile :display_name])))

          (testing "a failed product write rolls back the account and its cookie effect"
            (is (some? (exception
                        #(server/invoke! registry session-id :accounts/signup
                                         {:identifier "rolled-back"
                                          :password "rolled back password"
                                          :display-name nil}))))
            (is (= ["Owner"]
                   (mapv :identifier (auth/identity-state (:database runtime))))))

          (testing "logout revokes the token and protected actions remain protected"
            (is (= :clear
                   (get-in (server/invoke! registry session-id :accounts/logout {}
                                           {:auth-token token})
                           [:effects 0 :op])))
            (is (= :auth/required
                   (:code
                    (ex-data
                     (exception
                      #(server/invoke! registry session-id :accounts/me {}
                                       {:auth-token token}))))))))

        (testing "login throttling survives the rejected action transaction"
          (dotimes [_ 5]
            (is (= :auth/invalid-credentials
                   (:code
                    (ex-data
                     (exception
                      #(server/invoke! registry session-id :accounts/login
                                       {:identifier "owner"
                                        :password "wrong password"})))))))
          (is (= :auth/temporarily-locked
                 (:code
                  (ex-data
                   (exception
                    #(server/invoke! registry session-id :accounts/login
                                     {:identifier "owner"
                                      :password "owner password"}))))))))
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
