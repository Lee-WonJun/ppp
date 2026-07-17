(ns ppp.coordinator-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ppp.coordinator :as coordinator]
            [ppp.outbound.service :as outbound]
            [ppp.provider.core :as provider]
            [ppp.provider.budget :as provider-budget]
            [ppp.provider.fake :as fake]
            [ppp.provider.queue :as provider-queue]
            [ppp.runtime.auth :as auth]
            [ppp.runtime.resources :as resources]
            [ppp.runtime.server :as server]
            [ppp.runtime.sqlite :as sqlite]
            [ppp.session.store :as store]
            [ppp.shared.protocol :as protocol]
            [ppp.util.fs :as fs]
            [ppp.websocket :as websocket])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util Base64)))

(defn- test-context
  ([] (test-context {}))
  ([overrides]
   (let [root (Files/createTempDirectory "ppp-coordinator-test"
                                         (make-array FileAttribute 0))
         config (merge {:data-dir root
                        :prompt-limit 4000
                        :queue-capacity 8
                        :provider-timeout-ms 5000
                        :client-ack-timeout-ms 100
                        :require-client-ack? false
                        :source-file-limit 32
                        :source-byte-limit (* 256 1024)
                        :session-db-limit (* 25 1024 1024)
                        :checkpoint-limit (* 256 1024 1024)
                        :instance-limit (* 2 1024 1024 1024)}
                       (dissoc overrides :test/provider :test/provider-budget))
         session-store (store/create-store config)
         provider (or (:test/provider overrides) (fake/create-provider))
         queue (provider-queue/create-queue config)
         registry (server/create-registry)
         auth-service (auth/create-service
                       (merge {:cookie-secret
                               "coordinator-auth-test-secret-with-more-than-32-characters"
                               :cookie-secure? false
                               :product-auth-session-seconds 3600}
                              config)
                       {:hash-options {:memory-kib 7168
                                       :iterations 1
                                       :parallelism 1}
                        :allow-weak-test-parameters? true})
         outbound-service (outbound/create-service {})
         resource-service (resources/create-service config)
         sent (atom [])
         bundle-fn (atom nil)
         hub (websocket/create-hub
              (assoc config
                     :runtime-bundle-fn #(@bundle-fn %)
                     :send-fn (fn [channel frame]
                                (swap! sent conj
                                       [channel (websocket/decode-message frame)])
                                true)
                     :close-fn (constantly true)))
         coordinator (coordinator/create-coordinator
                      {:config config
                       :store session-store
                       :provider provider
                       :provider-budget (:test/provider-budget overrides)
                       :provider-queue queue
                       :registry registry
                       :product-auth auth-service
                       :resource-service resource-service
                       :outbound outbound-service
                       :hub hub})]
     (reset! bundle-fn #(coordinator/runtime-bundle coordinator %))
     {:root root
      :config config
      :store session-store
      :queue queue
      :registry registry
      :product-auth auth-service
      :resource-service resource-service
      :hub hub
      :sent sent
      :coordinator coordinator})))

(defn- close-context!
  [{:keys [root queue hub]}]
  (provider-queue/stop! queue)
  (websocket/stop! hub)
  (fs/delete-tree! root))

(defn- exception-code
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo cause
      (:code (ex-data cause)))))

(declare submit-and-await!)

(deftest provider-repair-attempts-consume-the-global-start-budget
  (let [clock (atom 100000)
        requests (atom [])
        repair-provider
        (reify provider/Provider
          (ready? [_] {:ready? true :provider :repair-fixture})
          (generate! [_ request]
            (let [attempt (count (swap! requests conj request))]
              (provider/generation
               (if (= 1 attempt)
                 (provider/change-result
                  "The first proposal is intentionally invalid."
                  "Invalid proposal"
                  [{:path "../../outside.clj" :content "(System/exit 0)"}]
                  [])
                 (provider/change-result
                  "The repaired theme is ready."
                  "Repaired theme"
                  [{:path "styles/runtime.css"
                    :content ":host { color: white; background: black; }\n"}]
                  []))
               "77777777-7777-4777-8777-777777777777"))))
        root (Files/createTempDirectory "ppp-coordinator-budget-test"
                                        (make-array FileAttribute 0))
        budget-config {:data-dir root
                       :provider :codex
                       :provider-calls-per-hour 1
                       :provider-window-seconds 60
                       :provider-budget-now-ms-fn #(deref clock)}
        provider-budget (provider-budget/create-budget budget-config)
        context (test-context (merge budget-config
                                     {:test/provider repair-provider
                                      :test/provider-budget provider-budget}))]
    (try
      (let [session-id (:id (coordinator/create-session! (:coordinator context)))
            source-before (store/current-source-map (:store context) session-id)
            database-before
            (sqlite/logical-hash
             (sqlite/datasource
              (store/current-db-path (:store context) session-id)))]
        (is (= :provider/capacity-exhausted
               (exception-code
                #(submit-and-await! context session-id (random-uuid) 0
                                    "Use a dark theme"))))
        (is (= 1 (count @requests)))
        (is (= 1 (:used (provider-budget/status provider-budget))))
        (is (= source-before (store/current-source-map (:store context) session-id)))
        (is (= 0 (:current-version (store/get-session (:store context) session-id))))
        (is (= :provider/capacity-exhausted
               (exception-code
                #(coordinator/submit-turn!
                  (:coordinator context) session-id
                  {:prompt "Try again"
                   :request-tab-id (random-uuid)
                   :base-version 0}))))
        (is (= 1 (count @requests)))
        (is (= {:ok true :message "The runtime is ready."}
               (:result
                (coordinator/invoke-action! (:coordinator context)
                                            session-id :ping {}))))
        (is (= database-before
               (sqlite/logical-hash
                (sqlite/datasource
                 (store/current-db-path (:store context) session-id)))))
        (let [restore
              (coordinator/submit-restore!
               (:coordinator context) session-id
               {:checkpoint-version 0
                :request-tab-id (random-uuid)
                :base-version 0})
              restored
              (coordinator/await-turn! (:coordinator context)
                                       (:request-id restore) 10000)]
          (is (= {:kind :restore
                  :runtime-version 1
                  :restored-from 0}
                 restored))
          (is (= [0 1]
                 (mapv :runtime-version
                       (store/list-checkpoints (:store context) session-id))))
          (is (= source-before
                 (store/current-source-map (:store context) session-id)))
          (is (= database-before
                 (sqlite/logical-hash
                  (sqlite/datasource
                   (store/current-db-path (:store context) session-id)))))
          (is (= 1 (:used (provider-budget/status provider-budget))))
          (is (= 1 (count @requests)))))
      (finally
        (close-context! context)
        (fs/delete-tree! root)))))

(defn- submit-and-await!
  [context session-id tab-id version prompt]
  (let [submission
        (coordinator/submit-turn!
         (:coordinator context) session-id
         {:prompt prompt
          :request-tab-id tab-id
          :base-version version})]
    (coordinator/await-turn! (:coordinator context) (:request-id submission) 10000)))

(deftest generated-product-accounts-survive-reload-and-restore-revokes-live-logins
  (let [context (test-context)]
    (try
      (let [session (coordinator/create-session! (:coordinator context))
            session-id (:id session)
            tab-id (random-uuid)
            account-change (submit-and-await! context session-id tab-id 0
                                              "로그인, 회원가입을 구현해줘")]
        (is (= {:kind :change :runtime-version 1} account-change))
        (let [created
              (coordinator/invoke-action!
               (:coordinator context) session-id :accounts/signup
               {:identifier "owner"
                :password "owner password"
                :display-name "Owner"})
              token (get-in created [:effects 0 :token])]
          (is (= "Owner" (get-in created [:result :profile :display_name])))
          (is (= true
                 (get-in (coordinator/invoke-action!
                          (:coordinator context) session-id :accounts/status {}
                          {:auth-token token})
                         [:result :signed-in?])))

          (let [visual-change (submit-and-await! context session-id tab-id 1
                                                 "다크테마로 바꿔줘")]
            (is (= {:kind :change :runtime-version 2} visual-change)))
          (is (some #(= 2 (:runtime-version %))
                    (store/list-checkpoints (:store context) session-id)))

          (let [submission
                (coordinator/submit-restore!
                 (:coordinator context) session-id
                 {:checkpoint-version 2
                  :request-tab-id tab-id
                  :base-version 2})
                restored (coordinator/await-turn! (:coordinator context)
                                                  (:request-id submission) 10000)]
            (is (= :restore (:kind restored))))

          (let [after-restore
                (coordinator/invoke-action!
                 (:coordinator context) session-id :accounts/status {}
                 {:auth-token token})]
            (is (false? (get-in after-restore [:result :signed-in?])))
            (is (= :clear (get-in after-restore [:effects 0 :op]))))
          (is (= ["owner"]
                 (mapv :identifier
                       (auth/identity-state
                        (sqlite/datasource
                         (store/current-db-path (:store context) session-id))))))
          (is (= "owner"
                 (get-in (coordinator/invoke-action!
                          (:coordinator context) session-id :accounts/login
                          {:identifier "owner" :password "owner password"})
                         [:result :user :identifier])))))
      (finally
        (close-context! context)))))

(deftest checkpoint-restore-rewinds-blobs-and-search-and-cancels-restored-work
  (let [context (test-context)]
    (try
      (let [session (coordinator/create-session! (:coordinator context))
            session-id (:id session)
            database (sqlite/datasource
                      (store/current-db-path (:store context) session-id))
            service (:resource-service context)
            encode #(.encodeToString
                     (Base64/getEncoder)
                     (.getBytes ^String % StandardCharsets/UTF_8))
            original-job
            (do
              (resources/blob-put! service database nil
                                   {:id "brief"
                                    :name "brief.txt"
                                    :content-type "text/plain"
                                    :content-base64 (encode "checkpoint value")})
              (resources/search-upsert! service database :briefs "brief"
                                        {:text "checkpoint searchable value"
                                         :metadata {:version :checkpoint}})
              (resources/schedule-job! service database :brief/index
                                       {:id "brief"}
                                       {:delay-ms 60000
                                        :idempotency-key "checkpoint"}))
            tab-id (random-uuid)
            changed (submit-and-await! context session-id tab-id 0
                                       "다크테마로 바꿔줘")]
        (is (= {:kind :change :runtime-version 1} changed))
        (is (some #(= 1 (:runtime-version %))
                  (store/list-checkpoints (:store context) session-id)))

        (let [live-database (sqlite/datasource
                             (store/current-db-path (:store context) session-id))
              later-job
              (do
                (resources/blob-put! service live-database nil
                                     {:id "brief"
                                      :name "brief.txt"
                                      :content-type "text/plain"
                                      :content-base64 (encode "later value")})
                (resources/search-upsert! service live-database :briefs "later"
                                          {:text "later only document"
                                           :metadata {:version :later}})
                (resources/schedule-job! service live-database :brief/index
                                         {:id "later"}
                                         {:delay-ms 60000
                                          :idempotency-key "later"}))
              submission
              (coordinator/submit-restore!
               (:coordinator context) session-id
               {:checkpoint-version 1
                :request-tab-id tab-id
                :base-version 1})
              restored (coordinator/await-turn! (:coordinator context)
                                                (:request-id submission) 10000)
              restored-database
              (sqlite/datasource (store/current-db-path (:store context) session-id))
              loaded (resources/blob-get service restored-database "brief")]
          (is (= :restore (:kind restored)))
          (is (= "checkpoint value"
                 (String. (.decode (Base64/getDecoder)
                                   ^String (:content-base64 loaded))
                          StandardCharsets/UTF_8)))
          (is (= ["brief"]
                 (mapv :id (resources/search-query service restored-database
                                                   :briefs "checkpoint" {}))))
          (is (empty? (resources/search-query service restored-database
                                              :briefs "later" {})))
          (is (= :cancelled
                 (:status (resources/job-status service restored-database
                                                (:id original-job)))))
          (is (= :job/not-found
                 (exception-code
                  #(resources/job-status service restored-database
                                         (:id later-job)))))))
      (finally
        (close-context! context)))))

(deftest rolled-back-resource-actions-neither-mutate-nor-publish
  (let [context (test-context)]
    (try
      (let [session-id (:id (coordinator/create-session! (:coordinator context)))
            database-path (store/current-db-path (:store context) session-id)
            source-map
            (assoc store/initial-source
                   "src/server/runtime/server.clj"
                   (str
                    "(ns runtime.server (:require [runtime.api :as api]))\n"
                    "(defn fail! [_]\n"
                    "  (api/blob-put! {:id \"rolled-back\" :name \"rolled-back.txt\" :content-type \"text/plain\" :content-base64 \"bm8=\"})\n"
                    "  (api/publish! :resource/should-not-escape {:id \"rolled-back\"})\n"
                    "  (throw (ex-info \"reject transaction\" {:code :resource/rejected})))\n"
                    "(api/register-action! :resource/fail fail!)\n"))
            runtime (server/stage! {:source-map source-map
                                    :session-id session-id
                                    :database-path database-path
                                    :resource-service (:resource-service context)
                                    :version 0
                                    :run-tests? false})
            channel :rollback-channel
            tab-id (random-uuid)
            request-id (random-uuid)
            before (sqlite/logical-hash (sqlite/datasource database-path))]
        (server/activate! (:registry context) session-id runtime)
        (websocket/open! (:hub context) channel)
        (websocket/receive!
         (:hub context) channel
         (websocket/encode-message
          (protocol/envelope {:session-id session-id
                              :request-id request-id
                              :runtime-version 0
                              :type :session/subscribe
                              :payload {:tab-id tab-id :current-version 0}})))
        (reset! (:sent context) [])
        (is (= :resource/rejected
               (exception-code
                #(coordinator/invoke-action! (:coordinator context) session-id
                                             :resource/fail {}))))
        (is (= before (sqlite/logical-hash (sqlite/datasource database-path))))
        (is (empty? (resources/blob-list (:resource-service context)
                                         (sqlite/datasource database-path))))
        (is (empty? @(:sent context))))
      (finally
        (close-context! context)))))

(deftest failure-messages-explain-the-stage-without-exposing-internals
  (let [message-for (ns-resolve 'ppp.coordinator 'user-safe-failure)
        render-message (message-for :runtime/client-render-failed)
        source-message (message-for :source/validation-failed)]
    (is (re-find #"could not be drawn in this browser preview" render-message))
    (is (re-find #"does not allow" source-message))
    (is (not= render-message source-message))
    (is (not (re-find #"SCI|REPL|WebSocket|source/validation" render-message)))
    (is (not (re-find #"SCI|REPL|WebSocket|source/validation" source-message)))))

(deftest generated-domain-tests-run-only-for-relevant-runtime-changes
  (is (= :client-only
         (coordinator/change-impact
          {:writes [{:path "styles/runtime.css"}]})))
  (is (= :client-only
         (coordinator/change-impact
          {:writes [{:path "src/client/runtime/client.cljs"}]})))
  (is (= :client-only
         (coordinator/change-impact
          {:writes [{:path "src/client/runtime/client.cljs"}
                    {:path "styles/game.css"}]
           :deletes []
           :migrations []})))
  (doseq [change [{:writes [{:path "src/server/runtime/server.clj"}]}
                  {:writes [{:path "src/shared/runtime/domain.cljc"}]}
                  {:writes [{:path "test/runtime/domain_test.cljc"}]}
                  {:deletes ["test/runtime/domain_test.cljc"]}
                  {:migrations [{:name "domain-change" :sql "CREATE TABLE items (id INTEGER);"}]}]]
    (is (= :server-data (coordinator/change-impact change)) (pr-str change))))

(deftest client-only-change-reuses-server-runtime-and-preserves-logical-data
  (let [context (test-context)]
    (try
      (let [session-id (:id (coordinator/create-session! (:coordinator context)))
            tab-id (random-uuid)
            source-before (store/current-source-map (:store context) session-id)
            database-before
            (sqlite/logical-hash
             (sqlite/datasource (store/current-db-path (:store context) session-id)))
            runtime-before (server/runtime-for (:registry context) session-id)]
        (is (= {:kind :change :runtime-version 1}
               (submit-and-await! context session-id tab-id 0
                                  "Use a dark theme")))
        (let [source-after (store/current-source-map (:store context) session-id)
              runtime-after (server/runtime-for (:registry context) session-id)
              event (first (store/list-history (:store context) session-id))
              validation-path
              (first (filter #(= "validation.edn" (str (.getFileName %)))
                             (fs/list-tree
                              (.resolve (store/session-root (:store context) session-id)
                                        "history"))))
              validation (fs/read-edn validation-path)
              server-paths ["src/server/runtime/server.clj"
                            "src/shared/runtime/domain.cljc"
                            "test/runtime/domain_test.cljc"]]
          (is (= (select-keys source-before server-paths)
                 (select-keys source-after server-paths)))
          (is (= database-before
                 (sqlite/logical-hash
                  (sqlite/datasource
                   (store/current-db-path (:store context) session-id)))))
          (is (identical? (:context runtime-before) (:context runtime-after)))
          (is (= 1 (:version runtime-after)))
          (is (= :client-only (:runtime-impact event)))
          (is (= 1 (:generation-attempts event)))
          (is (= {:source :passed
                  :impact :client-only
                  :server :not-applicable
                  :client :passed
                  :domain-tests :not-applicable
                  :sql :not-applicable}
                 validation))
          (is (= {:ok true :message "The runtime is ready."}
                 (:result (coordinator/invoke-action! (:coordinator context)
                                                      session-id :ping {}))))))
      (finally
        (close-context! context)))))

(deftest reply-and-clarify-create-history-without-runtime-versions
  (let [context (test-context)]
    (try
      (let [session (coordinator/create-session! (:coordinator context))
            session-id (:id session)
            tab-id (random-uuid)]
        (is (= {:kind :reply :runtime-version 0}
               (submit-and-await! context session-id tab-id 0 "hello")))
        (is (= {:kind :clarify :runtime-version 0}
               (submit-and-await! context session-id tab-id 0
                                  "Make something useful")))
        (is (= 0 (:current-version (store/get-session (:store context) session-id))))
        (is (= [:reply :clarify]
               (mapv :kind (store/list-history (:store context) session-id))))
        (is (= [0] (mapv :runtime-version
                         (store/list-checkpoints (:store context) session-id)))))
      (finally
        (close-context! context)))))

(deftest change-commits-source-database-history-checkpoint-and-server-registry
  (let [context (test-context)]
    (try
      (let [session-id (:id (coordinator/create-session! (:coordinator context)))
            tab-id (random-uuid)]
        (is (= {:kind :change :runtime-version 1}
               (submit-and-await! context session-id tab-id 0
                                  "Make the sidebar a floating panel")))
        (is (= 1 (:current-version (store/get-session (:store context) session-id))))
        (is (= 1 (:runtime-version (store/current-manifest (:store context)
                                                           session-id))))
        (is (= 1 (:version (server/runtime-for (:registry context) session-id))))
        (is (= [0 1] (mapv :runtime-version
                           (store/list-checkpoints (:store context) session-id))))
        (is (= [:change] (mapv :kind (store/list-history (:store context)
                                                         session-id))))
        (is (= {:ok true :message "The runtime is ready."}
               (:result (coordinator/invoke-action! (:coordinator context)
                                                    session-id :ping {})))))
      (finally
        (close-context! context)))))

(deftest invalid-generated-change-is-repaired-once-before-the-user-sees-a-failure
  (let [requests (atom [])
        thread-id "77777777-7777-4777-8777-777777777777"
        repair-provider
        (reify provider/Provider
          (ready? [_] {:ready? true :provider :repair-fixture})
          (generate! [_ request]
            (let [attempt (count (swap! requests conj request))]
              (provider/generation
               (if (= 1 attempt)
                 (provider/change-result
                  "First proposal"
                  "Invalid proposal"
                  [{:path "../../outside.clj" :content "(System/exit 0)"}]
                  [])
                 (provider/change-result
                  "The repaired theme is running."
                  "Repair the generated theme"
                  [{:path "styles/runtime.css"
                    :content ":host { color: #eee; background: #111; }\n"}]
                  []))
               thread-id))))
        context (test-context {:test/provider repair-provider})]
    (try
      (let [session-id (:id (coordinator/create-session! (:coordinator context)))
            result (submit-and-await! context session-id (random-uuid) 0
                                      "Use a dark theme")]
        (is (= {:kind :change :runtime-version 1} result))
        (is (= 2 (count @requests)))
        (is (= :source/validation-failed
               (get-in @requests [1 :repair-feedback :code])))
        (is (= 1 (get-in @requests [1 :repair-feedback :attempt])))
        (is (= thread-id (:thread-id (second @requests))))
        (let [event (first (store/list-history (:store context) session-id))]
          (is (= thread-id (:provider-thread-id event)))
          (is (= 2 (:generation-attempts event)))
          (is (= :client-only (:runtime-impact event))))
        (is (= [:change]
               (mapv :kind (store/list-history (:store context) session-id))))
        (is (= 1 (:runtime-version (store/current-manifest (:store context)
                                                           session-id)))))
      (finally
        (close-context! context)))))

(deftest exhausted-repairable-change-keeps-thread-for-the-next-explicit-repair
  (let [requests (atom [])
        thread-id "99999999-9999-4999-8999-999999999999"
        repair-provider
        (reify provider/Provider
          (ready? [_] {:ready? true :provider :repair-fixture})
          (generate! [_ request]
            (let [attempt (count (swap! requests conj request))]
              (provider/generation
               (if (<= attempt 2)
                 (provider/change-result
                  "This proposal is still invalid."
                  "Invalid proposal"
                  [{:path "../../outside.clj" :content "(System/exit 0)"}]
                  [])
                 (provider/change-result
                  "The corrected theme is running."
                  "Corrected theme"
                  [{:path "styles/runtime.css"
                    :content ":host { color: #eee; background: #111; }\n"}]
                  []))
               thread-id))))
        context (test-context {:test/provider repair-provider
                               :change-generation-attempts 2})]
    (try
      (let [session-id (:id (coordinator/create-session! (:coordinator context)))
            tab-id (random-uuid)]
        (is (= :source/validation-failed
               (exception-code
                #(submit-and-await! context session-id tab-id 0
                                    "Use a dark theme"))))
        (is (= 2 (count @requests)))
        (is (= thread-id
               (:codex-thread-id (store/get-session (:store context)
                                                    session-id))))
        (is (= thread-id
               (:provider-thread-id
                (first (store/list-history (:store context) session-id)))))

        (is (= {:kind :change :runtime-version 1}
               (submit-and-await! context session-id tab-id 0
                                  "Fix the rejected theme")))
        (is (= thread-id (:thread-id (nth @requests 2))))
        (is (= [:rejected :change]
               (mapv :kind (store/list-history (:store context) session-id))))
        (is (= thread-id
               (:codex-thread-id (store/get-session (:store context)
                                                    session-id)))))
      (finally
        (close-context! context)))))

(deftest failed-generated-business-test-is-fed-back-and-repaired-before-commit
  (let [requests (atom [])
        thread-id "88888888-8888-4888-8888-888888888888"
        repair-provider
        (reify provider/Provider
          (ready? [_] {:ready? true :provider :domain-repair-fixture})
          (generate! [_ request]
            (let [attempt (count (swap! requests conj request))]
              (provider/generation
               (if (= 1 attempt)
                 (provider/change-result
                  "First business-rule proposal"
                  "Invalid business rule"
                  [{:path "test/runtime/domain_test.cljc"
                    :content
                    "(ns runtime.domain-test (:require [clojure.test :refer [deftest is]]))\n(deftest promised-rule (is (= 0 1)))\n"}]
                  [])
                 (provider/change-result
                  "The corrected business rule passed validation."
                  "Repair the business rule"
                  [{:path "test/runtime/domain_test.cljc"
                    :content (get store/initial-source
                                  "test/runtime/domain_test.cljc")}
                   {:path "styles/runtime.css"
                    :content ":host { color: #eee; background: #111; }\n"}]
                  []))
               thread-id))))
        context (test-context {:test/provider repair-provider})]
    (try
      (let [session-id (:id (coordinator/create-session! (:coordinator context)))
            result (submit-and-await! context session-id (random-uuid) 0
                                      "Change a business rule")]
        (is (= {:kind :change :runtime-version 1} result))
        (is (= 2 (count @requests)))
        (is (= :runtime/generated-tests-failed
               (get-in @requests [1 :repair-feedback :code])))
        (is (= [{:test "runtime.domain-test/promised-rule"
                 :assertions 1
                 :fail 1
                 :error 0}]
               (get-in @requests [1 :repair-feedback :failed-tests])))
        (is (= [:change]
               (mapv :kind (store/list-history (:store context) session-id))))
        (let [history-root (.resolve (store/session-root (:store context) session-id)
                                     "history")
              validation-path
              (first (filter #(= "validation.edn" (str (.getFileName %)))
                             (fs/list-tree history-root)))]
          (is (= :passed
                 (:domain-tests (fs/read-edn validation-path))))))
      (finally
        (close-context! context)))))

(deftest gallery-migration-and-actions-are-live-after-the-same-commit
  (let [context (test-context)]
    (try
      (let [session-id (:id (coordinator/create-session! (:coordinator context)))
            tab-id (random-uuid)]
        (submit-and-await! context session-id tab-id 0
                           "Create a gallery with voting and a leaderboard")
        (let [result (:result (coordinator/invoke-action!
                               (:coordinator context) session-id :projects/list {}))]
          (is (= 6 (count (:projects result))))
          (is (every? zero? (map :score (:projects result))))
          (is (= {:public 1 :judge 1} (:weights result))))
        (is (= #{"projects" "votes"}
               (set (sqlite/user-table-names
                     (sqlite/datasource (store/current-db-path (:store context)
                                                               session-id)))))))
      (finally
        (close-context! context)))))

(deftest rejected-source-and-missing-browser-ack-preserve-the-active-product
  (testing "source policy rejection"
    (let [context (test-context)]
      (try
        (let [session-id (:id (coordinator/create-session! (:coordinator context)))
              tab-id (random-uuid)
              source-before (store/current-source-map (:store context) session-id)
              database-before
              (sqlite/logical-hash
               (sqlite/datasource (store/current-db-path (:store context) session-id)))]
          (is (= :source/validation-failed
                 (exception-code
                  #(submit-and-await! context session-id tab-id 0
                                      "[[fake:invalid-source]]"))))
          (is (= source-before (store/current-source-map (:store context) session-id)))
          (is (= database-before
                 (sqlite/logical-hash
                  (sqlite/datasource (store/current-db-path (:store context)
                                                            session-id)))))
          (is (= 0 (:current-version (store/get-session (:store context) session-id))))
          (is (= [0] (mapv :runtime-version
                           (store/list-checkpoints (:store context) session-id))))
          (is (= [:rejected]
                 (mapv :kind (store/list-history (:store context) session-id))))
          (let [history-root (.resolve (store/session-root (:store context) session-id)
                                       "history")
                changes-path (first (filter #(= "changes.edn"
                                                (str (.getFileName %)))
                                            (fs/list-tree history-root)))
                validation-path (first (filter #(= "validation.edn"
                                                   (str (.getFileName %)))
                                               (fs/list-tree history-root)))]
            (is (= ["../../outside.clj"]
                   (mapv :path (:writes (fs/read-edn changes-path)))))
            (is (= {:status :rejected
                    :error-code :source/validation-failed}
                   (fs/read-edn validation-path)))))
        (finally
          (close-context! context)))))
  (testing "request tab is not connected"
    (let [context (test-context {:require-client-ack? true})]
      (try
        (let [session-id (:id (coordinator/create-session! (:coordinator context)))
              tab-id (random-uuid)
              source-before (store/current-source-map (:store context) session-id)]
          (is (= :runtime/requester-not-connected
                 (exception-code
                  #(submit-and-await! context session-id tab-id 0
                                      "Make the sidebar float"))))
          (is (= source-before (store/current-source-map (:store context) session-id)))
          (is (= 0 (:current-version (store/get-session (:store context) session-id)))))
        (finally
          (close-context! context))))))

(deftest stale-turn-is-rejected-before-provider-or-storage-work
  (let [context (test-context)]
    (try
      (let [session-id (:id (coordinator/create-session! (:coordinator context)))]
        (is (= :runtime/stale-browser-version
               (exception-code
                #(coordinator/submit-turn!
                  (:coordinator context) session-id
                  {:prompt "Make the sidebar float"
                   :request-tab-id (random-uuid)
                   :base-version 9}))))
        (is (empty? (store/list-history (:store context) session-id))))
      (finally
        (close-context! context)))))

(deftest client-diagnostics-are-normalized-and-transient-provider-evidence
  (let [requests (atom [])
        recording-provider
        (reify provider/Provider
          (ready? [_] {:ready? true :provider :diagnostic-fixture})
          (generate! [_ request]
            (swap! requests conj request)
            (provider/generation
             {:kind :reply
              :assistant-message "I found the failed signup path."
              :clarification-question nil
              :restore-version nil
              :change nil}
             "88888888-8888-4888-8888-888888888888")))
        context (test-context {:test/provider recording-provider})]
    (try
      (let [session-id (:id (coordinator/create-session! (:coordinator context)))
            tab-id (random-uuid)
            sentinel "CLIENT_DIAGNOSTIC_SENTINEL"
            submission
            (coordinator/submit-turn!
             (:coordinator context) session-id
             {:prompt "Why did signup fail?"
              :request-tab-id tab-id
              :base-version 0
              :client-diagnostics
              [{:kind "action"
                :actionId "auth/register"
                :code "auth/identifier-invalid"
                :status 400
                :message sentinel}]})
            result (coordinator/await-turn! (:coordinator context)
                                            (:request-id submission) 10000)
            request (first @requests)]
        (is (= :reply (:kind result)))
        (is (= [{:kind :action
                 :action-id "auth/register"
                 :code "auth/identifier-invalid"
                 :status 400
                 :message sentinel}]
               (:client-diagnostics request)))
        (is (not (str/includes? (pr-str (:transcript-summary request)) sentinel)))
        (is (not (str/includes? (pr-str (store/list-history (:store context)
                                                            session-id))
                                sentinel)))
        (is (= :turn/client-diagnostics-invalid
               (exception-code
                #(coordinator/submit-turn!
                  (:coordinator context) session-id
                  {:prompt "Try malformed evidence"
                   :request-tab-id tab-id
                   :base-version 0
                   :client-diagnostics
                   (vec (repeat (inc protocol/max-client-diagnostics)
                                {:kind :runtime :message "failure"}))}))))
        (is (= :turn/client-diagnostics-invalid
               (exception-code
                #(coordinator/submit-turn!
                  (:coordinator context) session-id
                  {:prompt "Try scalar evidence"
                   :request-tab-id tab-id
                   :base-version 0
                   :client-diagnostics false}))))
        (is (= 1 (count @requests))))
      (finally
        (close-context! context)))))

(deftest successful-change-versions-are-monotonic-and-unique
  (let [context (test-context)]
    (try
      (let [session-id (:id (coordinator/create-session! (:coordinator context)))
            tab-id (random-uuid)
            prompts ["Make the sidebar float"
                     "Create a gallery with voting and a leaderboard"
                     "Make judge=3 points and show the top 3 podium"]]
        (doseq [[base-version prompt] (map-indexed vector prompts)]
          (is (= (inc base-version)
                 (:runtime-version
                  (submit-and-await! context session-id tab-id base-version prompt)))))
        (is (= [1 2 3]
               (mapv :runtime-version
                     (store/list-history (:store context) session-id))))
        (is (= [0 1 2 3]
               (mapv :runtime-version
                     (store/list-checkpoints (:store context) session-id)))))
      (finally
        (close-context! context)))))

(deftest restore-rewinds-source-and-data-while-preserving-future-checkpoints
  (let [context (test-context)]
    (try
      (let [session-id (:id (coordinator/create-session! (:coordinator context)))
            tab-id (random-uuid)]
        (submit-and-await! context session-id tab-id 0 "Make the sidebar float")
        (submit-and-await! context session-id tab-id 1
                           "Create a gallery with voting and a leaderboard")
        (let [source-v2 (store/current-source-map (:store context) session-id)
              database-v2
              (sqlite/logical-hash
               (sqlite/datasource (store/current-db-path (:store context) session-id)))]
          (coordinator/invoke-action! (:coordinator context) session-id
                                      :votes/create
                                      {:project-id 1 :voter-type "judge"})
          (submit-and-await! context session-id tab-id 2
                             "Make judge=3 points and show the top 3 podium")
          (let [source-v3 (store/current-source-map (:store context) session-id)
                database-v3
                (sqlite/logical-hash
                 (sqlite/datasource (store/current-db-path (:store context) session-id)))]
            (is (= {:kind :restore :runtime-version 4 :restored-from 2}
                   (submit-and-await! context session-id tab-id 3
                                      "restore checkpoint 2")))
            (is (= source-v2 (store/current-source-map (:store context) session-id)))
            (is (= database-v2
                   (sqlite/logical-hash
                    (sqlite/datasource (store/current-db-path (:store context)
                                                              session-id)))))
            (is (nil? (:codex-thread-id (store/get-session (:store context)
                                                           session-id))))
            (is (= [0 1 2 3 4]
                   (mapv :runtime-version
                         (store/list-checkpoints (:store context) session-id))))

            (is (= {:kind :restore :runtime-version 5 :restored-from 3}
                   (submit-and-await! context session-id tab-id 4
                                      "restore checkpoint 3")))
            (is (= source-v3 (store/current-source-map (:store context) session-id)))
            (is (= database-v3
                   (sqlite/logical-hash
                    (sqlite/datasource (store/current-db-path (:store context)
                                                              session-id)))))
            (is (= [0 1 2 3 4 5]
                   (mapv :runtime-version
                         (store/list-checkpoints (:store context) session-id))))
            (is (= [:change :change :change :restore :restore]
                   (mapv :kind (store/list-history (:store context) session-id)))))))
      (finally
        (close-context! context)))))

(deftest corrupt-checkpoint-rejection-preserves-active-runtime
  (let [context (test-context)]
    (try
      (let [session-id (:id (coordinator/create-session! (:coordinator context)))
            tab-id (random-uuid)
            source-before (store/current-source-map (:store context) session-id)
            database-before
            (sqlite/logical-hash
             (sqlite/datasource (store/current-db-path (:store context) session-id)))]
        (fs/atomic-write-string!
         (.resolve (store/checkpoint-root (:store context) session-id 0)
                   "app.sqlite.gz")
         "not a gzip archive")
        (is (= :checkpoint/corrupt
               (exception-code
                #(submit-and-await! context session-id tab-id 0
                                    "restore checkpoint 0"))))
        (is (= source-before (store/current-source-map (:store context) session-id)))
        (is (= database-before
               (sqlite/logical-hash
                (sqlite/datasource (store/current-db-path (:store context)
                                                          session-id)))))
        (is (= 0 (:current-version (store/get-session (:store context) session-id)))))
      (finally
        (close-context! context)))))

(deftest initialization-recovers-journal-before-reactivating-runtime
  (let [context (test-context)]
    (try
      (let [session-id (:id (coordinator/create-session! (:coordinator context)))
            transaction-id (random-uuid)
            stage
            (store/stage-change!
             (:store context) session-id transaction-id
             {:title "Recovered on startup"
              :writes [{:path "styles/recovered.css"
                        :content ".recovered { display: block; }\n"}]
              :deletes []
              :migrations []})]
        (sqlite/stage-database! (store/current-db-path (:store context) session-id)
                                (:database stage) [])
        (store/materialize-stage!
         (:store context) session-id
         (assoc stage :recovery
                {:event {:kind :change
                         :transaction-id transaction-id
                         :base-version 0
                         :runtime-version 1
                         :title "Recovered on startup"
                         :assistant "Recovered fixture"}
                 :checkpoint {:title "Recovered on startup" :kind :change}
                 :thread-action :reset}))
        (is (= 0 (:version (server/runtime-for (:registry context) session-id))))
        (coordinator/initialize! (:coordinator context))
        (is (= 1 (:version (server/runtime-for (:registry context) session-id))))
        (is (= 1 (:current-version (store/get-session (:store context) session-id))))
        (is (empty? (store/list-journals (:store context) session-id)))
        (is (= [0 1] (mapv :runtime-version
                           (store/list-checkpoints (:store context) session-id)))))
      (finally
        (close-context! context)))))
