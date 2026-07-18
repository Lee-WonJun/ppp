(ns ppp.coordinator
  (:require [clojure.string :as str]
            [ppp.provider.core :as provider]
            [ppp.provider.budget :as provider-budget]
            [ppp.provider.queue :as provider-queue]
            [ppp.outbound.service :as outbound]
            [ppp.runtime.server :as server]
            [ppp.session.store :as store]
            [ppp.shared.protocol :as protocol]
            [ppp.util.fs :as fs]
            [ppp.websocket :as websocket])
  (:import (java.nio.charset StandardCharsets)
           (java.util UUID)))

;; queued -> generating -> validating -> staging server/database/browser
;;        -> materialize+journal -> history+checkpoint+registry -> activate
;; Every edge before activation either leaves current untouched or restores the
;; before-current backup. The request tab's exact ACK is the only browser vote.

(defrecord Coordinator [config store provider provider-budget provider-queue registry product-auth
                        resource-service outbound hub jobs])

(defn- parse-protocol-uuid
  [value code]
  (try
    (cond
      (uuid? value) value
      (string? value) (UUID/fromString value)
      :else (throw (IllegalArgumentException. "Not a UUID")))
    (catch IllegalArgumentException cause
      (throw (ex-info "A protocol identifier is invalid"
                      {:code code :value (str value)}
                      cause)))))

(defn- exception-code
  [cause]
  (loop [current cause
         depth 0]
    (when (and current (< depth 24))
      (or (:code (ex-data current))
          (recur (.getCause ^Throwable current) (inc depth))))))

(defn- user-safe-failure
  ([code]
   (user-safe-failure code nil))
  ([code details]
   (let [detail (last (filter string? details))
         base
         (cond
           (contains? #{:provider/timeout :provider/wait-timeout} code)
           "The product assistant took too long. Your current product is unchanged."

           (= :provider/queue-full code)
           "The product assistant is busy. Try again after the queued change finishes."

           (= :provider/capacity-exhausted code)
           "New product changes are temporarily unavailable. Your current product and saved work remain available."

           (contains? #{:runtime/requester-not-connected
                        :runtime/requester-disconnected
                        :runtime/client-timeout} code)
           "The browser could not verify the new version. Reconnect and try again; your current product is unchanged."

           (= :runtime/stale-browser-version code)
           "This tab was behind the saved product. It is being refreshed to the current version."

           (= :runtime/client-render-failed code)
           "The generated interface passed source checks, but it could not be drawn in this browser preview. Your previous product is still running."

           (= :runtime/client-frame-load-timeout code)
           "The browser did not finish loading the product preview within 30 seconds. Your previous product is still running."

           (= :runtime/client-frame-render-timeout code)
           "The product preview loaded, but it did not finish drawing within 10 seconds. Your previous product is still running."

           (contains? #{:runtime/client-stage-failed
                        :runtime/client-registration-contract
                        :runtime/client-rejected
                        :runtime/source-read-failed} code)
           "The generated interface did not pass the browser runtime check. Your previous product is still running."

           (= :source/nonreactive-client-state code)
           "The generated interface kept changing data outside the page's reactive state, so the screen would not redraw after interactions. I tried corrected versions, but none passed validation; your previous product is still running."

           (= :runtime/server-stage-failed code)
           "The generated server behavior did not start safely. Your previous product is still running."

           (contains? #{:runtime/generated-tests-missing
                        :runtime/generated-tests-failed} code)
           "The generated product did not pass its domain and business-rule checks. I tried corrected versions, but none passed, so your previous product is still running."

           (contains? #{"source" "sql"} (namespace code))
           "The generated change requested code or data operations this workspace does not allow. I tried one corrected version, but it still failed validation, so your product is unchanged."

           (contains? #{:provider/invalid-result
                        :provider/repair-kind-invalid} code)
           "The assistant did not return a complete, valid change. Your current product is unchanged."

           (= :storage/quota-exceeded code)
           "This workspace has reached its storage limit. Existing products and checkpoints are still available."

           (= :checkpoint/not-found code)
           "That checkpoint is no longer available. Your current product is unchanged."

           (or (= :checkpoint/corrupt code)
               (and (keyword? code) (= "checkpoint" (namespace code))))
           "That checkpoint did not pass recovery validation. Your current product is unchanged."

           :else
           "The requested change could not be safely applied. Your current product is unchanged.")]
     (if (and detail
              (contains? #{:runtime/client-stage-failed
                           :runtime/client-render-failed
                           :runtime/client-registration-contract
                           :runtime/client-rejected
                           :runtime/source-read-failed}
                         code))
       (str base " Reason: " detail ".")
       base))))

(defn runtime-bundle
  [^Coordinator coordinator session-id]
  (let [session-id (store/parse-session-id session-id)
        manifest (store/current-manifest (:store coordinator) session-id)
        source (store/current-source-map (:store coordinator) session-id)]
    {:runtime-version (:runtime-version manifest)
     :capability-version (:capability-version manifest)
     :manifest manifest
     :files (protocol/client-runtime-files source)}))

(defn- runtime-capabilities
  [^Coordinator coordinator]
  {:auth-service (:product-auth coordinator)
   :resource-service (:resource-service coordinator)
   :public-http-fn #(outbound/public-request! (:outbound coordinator) %)
   :connector-http-fn #(outbound/connector-request! (:outbound coordinator) %1 %2)})

(defn- stage-active-runtime!
  [^Coordinator coordinator session-id]
  (let [session-id (store/parse-session-id session-id)
        manifest (store/current-manifest (:store coordinator) session-id)
        runtime
        (server/stage!
         (merge
          (runtime-capabilities coordinator)
          {:source-map (store/current-source-map (:store coordinator) session-id)
           :session-id session-id
           :database-path (store/current-db-path (:store coordinator) session-id)
           :version (:runtime-version manifest)
           :run-tests? false
           :timeout-ms 2000
           :database-size-limit (get (:config coordinator) :session-db-limit
                                     (* 25 1024 1024))
           :response-limit (get (:config coordinator)
                                :runtime-response-limit (* 7 1024 1024))}))]
    (server/activate! (:registry coordinator) session-id runtime)))

(defn initialize!
  [^Coordinator coordinator]
  (store/recover-all! (:store coordinator))
  (doseq [session (store/list-sessions (:store coordinator))]
    (stage-active-runtime! coordinator (:id session)))
  coordinator)

(defn create-coordinator
  [{:keys [config store provider provider-budget provider-queue registry product-auth
           resource-service outbound hub]}]
  (let [provider-budget (or provider-budget
                            (provider-budget/create-budget config))]
    (->Coordinator config store provider provider-budget provider-queue registry product-auth
                   resource-service outbound hub (atom {}))))

(defn create-session!
  ([coordinator]
   (create-session! coordinator {}))
  ([^Coordinator coordinator options]
   (let [session (store/create-session! (:store coordinator) options)]
     (stage-active-runtime! coordinator (:id session))
     session)))

(defn- send-turn!
  [^Coordinator coordinator session-id tab-id request-id version type payload]
  (websocket/send-turn-event! (:hub coordinator) session-id tab-id request-id
                              version type payload))

(defn- progress!
  ([coordinator request phase]
   (send-turn! coordinator
               (:session-id request)
               (:tab-id request)
               (:request-id request)
               (:base-version request)
               :turn/progress
               {:phase phase}))
  ([coordinator request phase detail]
   (when-let [detail (protocol/normalize-progress-detail phase detail)]
     (send-turn! coordinator
                 (:session-id request)
                 (:tab-id request)
                 (:request-id request)
                 (:base-version request)
                 :turn/progress
                 {:phase phase :detail detail}))))

(defn- append-conversation-event!
  [^Coordinator coordinator request result]
  (store/append-history!
   (:store coordinator) (:session-id request)
   (cond-> {:kind (:kind result)
            :request-id (:request-id request)
            :runtime-version (:base-version request)
            :prompt (:prompt request)
            :assistant (:assistant-message result)}
     (= :clarify (:kind result))
     (assoc :clarification-question (:clarification-question result)))))

(defn- complete-conversation!
  [^Coordinator coordinator request generation]
  (let [{:keys [result thread-id]} generation]
    (append-conversation-event! coordinator request result)
    (store/set-codex-thread! (:store coordinator) (:session-id request) thread-id)
    (send-turn! coordinator
                (:session-id request) (:tab-id request) (:request-id request)
                (:base-version request) :turn/completed
                {:kind (:kind result)
                 :assistant-message (:assistant-message result)
                 :clarification-question (:clarification-question result)})
    {:kind (:kind result)
     :runtime-version (:base-version request)}))

(defn- browser-stage!
  [^Coordinator coordinator request transaction-id stage]
  (let [target-version (get-in stage [:manifest :runtime-version])
        submission
        (websocket/request-stage!
         (:hub coordinator)
         {:session-id (:session-id request)
          :request-id (:request-id request)
          :tab-id (:tab-id request)
          :transaction-id transaction-id
          :base-version (:base-version request)
          :target-version target-version
          :capability-version (get-in stage [:manifest :capability-version])
          :files (protocol/client-runtime-files (store/stage-source-map stage))})
        outcome (websocket/await-stage! (:hub coordinator) submission)]
    (when-not (= :staged (:status outcome))
      (throw (ex-info "Browser rejected the staged runtime"
                      {:code (:code outcome)
                       :details (:details outcome)})))
    true))

(defn change-impact
  "Derive runtime impact from the host-validated changed paths. This value is
  never accepted from the provider."
  [{:keys [writes deletes migrations]}]
  (if (or (seq migrations)
          (some (fn [path]
                  (or (str/starts-with? path "src/server/")
                      (str/starts-with? path "src/shared/")
                      (str/starts-with? path "test/")))
                (concat (map :path writes) deletes)))
    :server-data
    :client-only))

(defn- stage-change!
  [^Coordinator coordinator request change transaction-id]
  (let [stage (store/stage-change! (:store coordinator)
                                   (:session-id request)
                                   transaction-id change)
        impact (change-impact change)
        domain-tests? (= :server-data impact)]
    (try
      (let [target-version (get-in stage [:manifest :runtime-version])
            staged-runtime
            (if (= :client-only impact)
              (server/reuse-for-client-stage!
               (server/runtime-for (:registry coordinator) (:session-id request))
               (store/current-db-path (:store coordinator) (:session-id request))
               (:database stage)
               target-version)
              (server/stage!
               (merge
                (runtime-capabilities coordinator)
                {:source-map (store/stage-source-map stage)
                 :session-id (:session-id request)
                 :source-database-path
                 (store/current-db-path (:store coordinator) (:session-id request))
                 :database-path (:database stage)
                 :migrations (:assigned-migrations stage)
                 :version target-version
                 :run-tests? true
                 :timeout-ms 2000
                 :database-size-limit (get (:config coordinator) :session-db-limit
                                           (* 25 1024 1024))
                 :response-limit (get (:config coordinator)
                                      :runtime-response-limit
                                      (* 7 1024 1024))})))]
        {:stage stage
         :server-runtime staged-runtime
         :impact impact
         :domain-tests? domain-tests?})
      (catch Exception cause
        (store/discard-stage! stage)
        (throw cause)))))

(defn- commit-change!
  [^Coordinator coordinator request result thread-id transaction-id
   {:keys [stage server-runtime impact domain-tests?]}]
  (let [session-id (:session-id request)
        old-runtime (server/runtime-for (:registry coordinator) session-id)
        old-session (store/get-session (:store coordinator) session-id)
        materialized (atom nil)
        target-version (get-in stage [:manifest :runtime-version])]
    (store/with-session-lock
      (:store coordinator) session-id
      (fn []
        (let [active-version (:runtime-version
                              (store/current-manifest (:store coordinator) session-id))]
          (when-not (= active-version (:base-version request))
            (throw (ex-info "The active runtime changed while this turn was staged"
                            {:code :runtime/base-version-conflict
                             :expected (:base-version request)
                             :actual active-version}))))
        (try
          (let [checkpoint-title (get-in result [:change :title])
                event {:kind :change
                       :request-id (:request-id request)
                       :transaction-id transaction-id
                       :base-version (:base-version request)
                       :runtime-version target-version
                       :title checkpoint-title
                       :prompt (:prompt request)
                       :assistant (:assistant-message result)
                       :changes (:change result)
                       :before (:before stage)
                       :after (:after stage)
                       :provider-thread-id thread-id
                       :generation-attempts (or (:generation-attempts result) 1)
                       :runtime-impact impact
                       :validation {:source :passed
                                    :impact impact
                                    :server (if (= :server-data impact)
                                              :passed
                                              :not-applicable)
                                    :client :passed
                                    :domain-tests (if domain-tests?
                                                    :passed
                                                    :not-applicable)
                                    :sql (if (seq (get-in result [:change :migrations]))
                                           :passed
                                           :not-applicable)}}
                recoverable-stage
                (assoc stage :recovery
                       {:event event
                        :checkpoint {:title checkpoint-title :kind :change}
                        :thread-action :set
                        :codex-thread-id thread-id})
                committed-stage (store/materialize-stage!
                                 (:store coordinator) session-id recoverable-stage)
                _ (reset! materialized committed-stage)
                active-runtime (server/rebind-database
                                server-runtime
                                (store/current-db-path (:store coordinator) session-id))]
            (store/update-session! (:store coordinator) session-id
                                   {:current-version target-version
                                    :codex-thread-id thread-id})
            (let [checkpoint
                  (store/create-checkpoint! (:store coordinator) session-id
                                            {:title checkpoint-title :kind :change})]
              (store/append-history!
               (:store coordinator) session-id event)
              (server/activate! (:registry coordinator) session-id active-runtime)
             ;; The journal may safely remain after the durable commit; startup
             ;; recovery recognizes target/target and only finalizes cleanup.
              (try
                (store/finalize-materialized! (:store coordinator) session-id
                                              committed-stage)
                (catch Exception _cleanup-failure nil))
              {:checkpoint checkpoint
               :manifest (store/current-manifest (:store coordinator) session-id)}))
          (catch Exception cause
            (when @materialized
              (store/rollback-materialized! (:store coordinator) session-id @materialized)
              (store/update-session! (:store coordinator) session-id
                                     {:codex-thread-id (:codex-thread-id old-session)}))
            (when old-runtime
              (server/activate! (:registry coordinator) session-id old-runtime))
            (throw cause)))))
    (let [bundle (runtime-bundle coordinator session-id)]
      (websocket/publish-activation!
       (:hub coordinator)
       {:session-id session-id
        :request-id (:request-id request)
        :tab-id (:tab-id request)
        :target-version target-version
        :manifest (:manifest bundle)
        :bundle bundle})
      bundle)))

(defn- apply-change!
  [^Coordinator coordinator request {:keys [result thread-id]}]
  (let [transaction-id (random-uuid)
        staged (atom nil)]
    (try
      (progress! coordinator request :validating)
      (reset! staged (stage-change! coordinator request (:change result) transaction-id))
      (browser-stage! coordinator request transaction-id (:stage @staged))
      (progress! coordinator request :applying)
      (let [result (assoc result :generation-attempts
                          (or (:generation-attempts result) 1))
            bundle (commit-change! coordinator request result thread-id
                                   transaction-id @staged)
            target-version (:runtime-version bundle)]
        (send-turn! coordinator
                    (:session-id request) (:tab-id request) (:request-id request)
                    target-version :turn/progress {:phase :applied})
        (send-turn! coordinator
                    (:session-id request) (:tab-id request) (:request-id request)
                    target-version :turn/completed
                    {:kind :change
                     :assistant-message (:assistant-message result)
                     :runtime-version target-version
                     :checkpoint-title (get-in result [:change :title])})
        {:kind :change :runtime-version target-version})
      (catch Exception cause
        (when-let [stage (:stage @staged)]
          (when (fs/exists? (:root stage))
            (store/discard-stage! stage)))
        (throw cause)))))

(defn- stage-restore-runtime!
  [^Coordinator coordinator request checkpoint-version transaction-id]
  (let [stage (store/stage-restore! (:store coordinator)
                                    (:session-id request)
                                    transaction-id
                                    checkpoint-version)]
    (try
      (let [target-version (get-in stage [:manifest :runtime-version])
            staged-runtime
            (server/stage!
             (merge
              (runtime-capabilities coordinator)
              {:source-map (store/stage-source-map stage)
               :session-id (:session-id request)
               :database-path (:database stage)
               :clear-auth-sessions? true
               :clear-operational-jobs? true
               :version target-version
               :timeout-ms 2000
               :database-size-limit (get (:config coordinator) :session-db-limit
                                         (* 25 1024 1024))
               :response-limit (get (:config coordinator)
                                    :runtime-response-limit
                                    (* 7 1024 1024))}))]
        {:stage stage :server-runtime staged-runtime})
      (catch Exception cause
        (store/discard-stage! stage)
        (throw cause)))))

(defn- commit-restore!
  [^Coordinator coordinator request result transaction-id
   {:keys [stage server-runtime]}]
  (let [session-id (:session-id request)
        old-runtime (server/runtime-for (:registry coordinator) session-id)
        old-session (store/get-session (:store coordinator) session-id)
        materialized (atom nil)
        target-version (get-in stage [:manifest :runtime-version])
        restored-from (:restored-from stage)
        source-title (get-in stage [:checkpoint :metadata :title])
        checkpoint-title (str "Restored " source-title)
        event {:kind :restore
               :request-id (:request-id request)
               :transaction-id transaction-id
               :base-version (:base-version request)
               :runtime-version target-version
               :restored-from restored-from
               :title checkpoint-title
               :prompt (:prompt request)
               :assistant (:assistant-message result)
               :before (:before stage)
               :after (:after stage)
               :validation {:checkpoint :passed
                            :source :passed
                            :server :passed
                            :client :passed
                            :sqlite :passed}}
        recoverable-stage
        (assoc stage :recovery
               {:event event
                :checkpoint {:title checkpoint-title :kind :restore}
                :thread-action :reset})]
    (store/with-session-lock
      (:store coordinator) session-id
      (fn []
        (let [active-version (:runtime-version
                              (store/current-manifest (:store coordinator) session-id))]
          (when-not (= active-version (:base-version request))
            (throw (ex-info "The active runtime changed while restore was staged"
                            {:code :runtime/base-version-conflict
                             :expected (:base-version request)
                             :actual active-version}))))
        (try
          (let [committed-stage
                (store/materialize-stage! (:store coordinator) session-id
                                          recoverable-stage)
                _ (reset! materialized committed-stage)
                active-runtime
                (server/rebind-database
                 server-runtime
                 (store/current-db-path (:store coordinator) session-id))]
            (store/update-session! (:store coordinator) session-id
                                   {:current-version target-version
                                    :codex-thread-id nil})
            (let [checkpoint
                  (store/create-checkpoint! (:store coordinator) session-id
                                            {:title checkpoint-title :kind :restore})]
              (store/append-history! (:store coordinator) session-id event)
              (server/activate! (:registry coordinator) session-id active-runtime)
              (try
                (store/finalize-materialized! (:store coordinator) session-id
                                              committed-stage)
                (catch Exception _cleanup-failure nil))
              {:checkpoint checkpoint
               :manifest (store/current-manifest (:store coordinator) session-id)}))
          (catch Exception cause
            (when @materialized
              (store/rollback-materialized! (:store coordinator) session-id @materialized)
              (store/update-session! (:store coordinator) session-id
                                     {:codex-thread-id (:codex-thread-id old-session)}))
            (when old-runtime
              (server/activate! (:registry coordinator) session-id old-runtime))
            (throw cause)))))
    (let [bundle (runtime-bundle coordinator session-id)]
      (websocket/publish-activation!
       (:hub coordinator)
       {:session-id session-id
        :request-id (:request-id request)
        :tab-id (:tab-id request)
        :target-version target-version
        :manifest (:manifest bundle)
        :bundle bundle})
      bundle)))

(defn- apply-restore!
  [^Coordinator coordinator request result]
  (let [transaction-id (random-uuid)
        staged (atom nil)
        checkpoint-version (:restore-version result)]
    (try
      (progress! coordinator request :validating)
      (reset! staged
              (stage-restore-runtime! coordinator request checkpoint-version
                                      transaction-id))
      (browser-stage! coordinator request transaction-id (:stage @staged))
      (progress! coordinator request :applying)
      (let [bundle (commit-restore! coordinator request result transaction-id @staged)
            target-version (:runtime-version bundle)]
        (send-turn! coordinator
                    (:session-id request) (:tab-id request) (:request-id request)
                    target-version :turn/progress {:phase :applied})
        (send-turn! coordinator
                    (:session-id request) (:tab-id request) (:request-id request)
                    target-version :turn/completed
                    {:kind :restore
                     :assistant-message (:assistant-message result)
                     :runtime-version target-version
                     :restored-from checkpoint-version})
        {:kind :restore
         :runtime-version target-version
         :restored-from checkpoint-version})
      (catch Exception cause
        (when-let [stage (:stage @staged)]
          (when (fs/exists? (:root stage))
            (store/discard-stage! stage)))
        (throw cause)))))

(def ^:private repairable-change-codes
  #{:runtime/server-stage-failed
    :runtime/missing-entrypoint
    :runtime/generated-test-namespace
    :runtime/generated-tests-missing
    :runtime/generated-tests-failed
    :runtime/client-stage-failed
    :runtime/client-render-failed
    :runtime/client-registration-contract
    :runtime/client-rejected
    :runtime/source-read-failed})

(defn- repairable-change-error?
  [code]
  (or (contains? repairable-change-codes code)
      (contains? #{"source" "sql"} (namespace code))))

(defn- repair-feedback
  [attempt cause]
  (let [data (ex-data cause)
        code (or (exception-code cause) :turn/failed)]
    (cond-> {:attempt attempt
             :code code}
      (:cause-code data) (assoc :cause-code (:cause-code data))
      (:path data) (assoc :path (:path data))
      (seq (:paths data)) (assoc :paths (vec (take 8 (:paths data))))
      (seq (:failed-tests data))
      (assoc :failed-tests (vec (take 8 (:failed-tests data))))
      (seq (:errors data)) (assoc :errors (vec (take 8 (:errors data))))
      (seq (:details data)) (assoc :details (vec (take 4 (:details data)))))))

(defn- generation-request
  [coordinator request session thread-id feedback]
  (cond-> {:session-id (:session-id request)
           :prompt (:prompt request)
           :runtime-version (:base-version request)
           :thread-id thread-id
           :transcript-summary (:transcript-summary session)
           :connector-catalog (outbound/catalog (:outbound coordinator))
           :ingress-verifier-catalog
           (outbound/ingress-catalog (:outbound coordinator))
           :on-progress #(progress! coordinator request :generating %)
           :source (store/current-source-map (:store coordinator)
                                             (:session-id request))}
    (seq (:client-diagnostics request))
    (assoc :client-diagnostics (:client-diagnostics request))
    feedback (assoc :repair-feedback feedback)))

(defn process-turn!
  [^Coordinator coordinator request]
  (let [generated? (atom false)
        generated-result (atom nil)
        generated-thread-id (atom nil)]
    (try
      (let [session (store/get-session (:store coordinator) (:session-id request))
            max-attempts (max 1 (long (get (:config coordinator)
                                           :change-generation-attempts 3)))]
        (loop [attempt 1
               thread-id (:codex-thread-id session)
               feedback nil]
          (progress! coordinator request :generating)
          (let [generation
                (do
                  (provider-budget/acquire! (:provider-budget coordinator))
                  (provider/generate!
                   (:provider coordinator)
                   (generation-request coordinator request session thread-id feedback)))
                result (:result generation)]
            (reset! generated? true)
            (reset! generated-result result)
            (reset! generated-thread-id (:thread-id generation))
            (when (and (> attempt 1) (not= :change (:kind result)))
              (throw (ex-info "A repair attempt did not return a change"
                              {:code :provider/repair-kind-invalid
                               :kind (:kind result)})))
            (case (:kind result)
              :reply (complete-conversation! coordinator request generation)
              :clarify (complete-conversation! coordinator request generation)
              :restore (apply-restore! coordinator request result)
              :change
              (let [outcome (try
                              {:value (apply-change! coordinator request
                                                     (update generation :result assoc
                                                             :generation-attempts attempt))}
                              (catch Exception cause {:cause cause}))]
                (if-let [cause (:cause outcome)]
                  (let [code (or (exception-code cause) :turn/failed)]
                    (if (and (< attempt max-attempts)
                             (repairable-change-error? code))
                      (recur (inc attempt)
                             (:thread-id generation)
                             (repair-feedback attempt cause))
                      (throw cause)))
                  (:value outcome)))))))
      (catch Exception cause
        (let [code (or (exception-code cause) :turn/failed)
              message (user-safe-failure code (:details (ex-data cause)))]
          ;; A fully exhausted but repairable change still contains the most
          ;; useful context for the user's next explicit correction. Preserve
          ;; that thread while keeping the rejected source out of current.
          ;; Non-repairable failures continue to reset potentially poisoned or
          ;; unusable provider context. Successful restore resets separately.
          (when @generated?
            (if (and (= :change (:kind @generated-result))
                     (string? @generated-thread-id)
                     (repairable-change-error? code))
              (store/set-codex-thread! (:store coordinator)
                                       (:session-id request)
                                       @generated-thread-id)
              (store/reset-codex-thread! (:store coordinator)
                                         (:session-id request))))
          (try
            (store/append-history!
             (:store coordinator) (:session-id request)
             (cond-> {:kind :rejected
                      :request-id (:request-id request)
                      :runtime-version (:base-version request)
                      :error-code code
                      :prompt (:prompt request)
                      :assistant message
                      :validation {:status :rejected
                                   :error-code code}}
               (= :change (:kind @generated-result))
               (assoc :changes (:change @generated-result)
                      :provider-thread-id @generated-thread-id)))
            (catch Exception _history-failure nil))
          (send-turn! coordinator
                      (:session-id request) (:tab-id request) (:request-id request)
                      (:base-version request) :turn/failed
                      {:code code :message message})
          (throw (ex-info message {:code code} cause)))))))

(defn submit-turn!
  [^Coordinator coordinator session-id
   {:keys [prompt request-tab-id base-version request-id client-diagnostics]}]
  (let [session-id (store/parse-session-id session-id)
        tab-id (parse-protocol-uuid request-tab-id :protocol/tab-id-invalid)
        request-id (parse-protocol-uuid (or request-id (random-uuid))
                                        :protocol/request-id-invalid)
        prompt (when (string? prompt) (str/trim prompt))
        prompt-bytes (when prompt
                       (alength (.getBytes ^String prompt StandardCharsets/UTF_8)))
        client-diagnostics
        (if (nil? client-diagnostics)
          []
          (protocol/normalize-client-diagnostics client-diagnostics))
        active-version (:runtime-version
                        (store/current-manifest (:store coordinator) session-id))]
    (when (or (str/blank? prompt)
              (> (or prompt-bytes 0) (long (get (:config coordinator)
                                                :prompt-limit 4000))))
      (throw (ex-info "A turn prompt is required and must fit the configured limit"
                      {:code :turn/prompt-invalid})))
    (when (nil? client-diagnostics)
      (throw (ex-info "Client diagnostics did not match the bounded contract"
                      {:code :turn/client-diagnostics-invalid})))
    (when-not (and (nat-int? base-version) (= base-version active-version))
      (throw (ex-info "This browser tab is not on the active runtime version"
                      {:code :runtime/stale-browser-version
                       :active-version active-version
                       :base-version base-version})))
    (provider-budget/ensure-available! (:provider-budget coordinator))
    ;; Reject before a provider job is accepted when the request tab has
    ;; already moved to another session. Otherwise the job could safely fail
    ;; staging but its failure event would have no subscribed tab to receive it.
    (when (and (get (:config coordinator) :require-client-ack? true)
               (not (websocket/subscribed? (:hub coordinator)
                                           session-id tab-id base-version)))
      (throw (ex-info "The requesting browser tab is not connected to this runtime"
                      {:code :runtime/requester-not-connected})))
    (let [request {:session-id session-id
                   :tab-id tab-id
                   :request-id request-id
                   :base-version base-version
                   :prompt prompt
                   :client-diagnostics client-diagnostics}
          submission
          (provider-queue/submit! (:provider-queue coordinator) session-id
                                  #(process-turn! coordinator request))]
      (swap! (:jobs coordinator) assoc request-id submission)
      (send-turn! coordinator session-id tab-id request-id base-version
                  :turn/queued
                  {:job-id (:job-id submission)
                   :position (:position submission)})
      {:job-id (:job-id submission)
       :request-id request-id})))

(defn- process-explicit-restore!
  [^Coordinator coordinator request checkpoint-version]
  (try
    (apply-restore!
     coordinator request
     {:kind :restore
      :assistant-message (str "Checkpoint " checkpoint-version
                              " is restored as a new version.")
      :restore-version checkpoint-version})
    (catch Exception cause
      (let [code (or (exception-code cause) :restore/failed)
            message (user-safe-failure code (:details (ex-data cause)))]
        (try
          (store/append-history!
           (:store coordinator) (:session-id request)
           {:kind :rejected
            :request-id (:request-id request)
            :runtime-version (:base-version request)
            :error-code code
            :prompt (:prompt request)
            :assistant message})
          (catch Exception _history-failure nil))
        (send-turn! coordinator
                    (:session-id request) (:tab-id request) (:request-id request)
                    (:base-version request) :turn/failed
                    {:code code :message message})
        (throw (ex-info message {:code code} cause))))))

(defn submit-restore!
  [^Coordinator coordinator session-id
   {:keys [checkpoint-version request-tab-id base-version request-id]}]
  (let [session-id (store/parse-session-id session-id)
        tab-id (parse-protocol-uuid request-tab-id :protocol/tab-id-invalid)
        request-id (parse-protocol-uuid (or request-id (random-uuid))
                                        :protocol/request-id-invalid)
        active-version (:runtime-version
                        (store/current-manifest (:store coordinator) session-id))]
    (when-not (nat-int? checkpoint-version)
      (throw (ex-info "A checkpoint version is required"
                      {:code :checkpoint/version-invalid})))
    (when-not (and (nat-int? base-version) (= base-version active-version))
      (throw (ex-info "This browser tab is not on the active runtime version"
                      {:code :runtime/stale-browser-version
                       :active-version active-version
                       :base-version base-version})))
    (when (and (get (:config coordinator) :require-client-ack? true)
               (not (websocket/subscribed? (:hub coordinator)
                                           session-id tab-id base-version)))
      (throw (ex-info "The requesting browser tab is not connected to this runtime"
                      {:code :runtime/requester-not-connected})))
    (let [request {:session-id session-id
                   :tab-id tab-id
                   :request-id request-id
                   :base-version base-version
                   :prompt (str "Restore checkpoint " checkpoint-version)}
          submission
          (provider-queue/submit!
           (:provider-queue coordinator) session-id
           #(process-explicit-restore! coordinator request checkpoint-version))]
      (swap! (:jobs coordinator) assoc request-id submission)
      (send-turn! coordinator session-id tab-id request-id base-version
                  :turn/queued
                  {:job-id (:job-id submission)
                   :position (:position submission)})
      {:job-id (:job-id submission)
       :request-id request-id})))

(defn await-turn!
  ([coordinator request-id]
   (await-turn! coordinator request-id 125000))
  ([^Coordinator coordinator request-id timeout-ms]
   (let [request-id (parse-protocol-uuid request-id :protocol/request-id-invalid)
         submission (get @(:jobs coordinator) request-id)]
     (when-not submission
       (throw (ex-info "Turn job was not found" {:code :turn/job-not-found})))
     (provider-queue/await! submission timeout-ms))))

(defn invoke-action!
  ([coordinator session-id action-id payload]
   (invoke-action! coordinator session-id action-id payload {}))
  ([^Coordinator coordinator session-id action-id payload request-context]
   (let [session-id (store/parse-session-id session-id)
         action-id (if (keyword? action-id) action-id (keyword (str action-id)))
         result
         (store/with-session-lock
           (:store coordinator) session-id
           #(server/invoke! (:registry coordinator) session-id action-id payload
                            request-context))]
     (doseq [{:keys [op topic payload]} (:effects result)
             :when (= :product-event op)]
       (websocket/publish-product-event! (:hub coordinator) session-id
                                         (:runtime-version result) topic payload))
     result)))

(defn invoke-ingress!
  [^Coordinator coordinator session-id route request
   {:keys [headers raw-body]}]
  (let [session-id (store/parse-session-id session-id)
        route (if (keyword? route) route (keyword (str route)))
        result
        (store/with-session-lock
          (:store coordinator) session-id
          #(let [options (server/ingress-options (:registry coordinator)
                                                 session-id route)]
             (outbound/verify-ingress! (:outbound coordinator)
                                       (:verification options) headers raw-body)
             (server/invoke-ingress! (:registry coordinator) session-id route
                                     request)))]
    (doseq [{:keys [op topic payload]} (:effects result)
            :when (= :product-event op)]
      (websocket/publish-product-event! (:hub coordinator) session-id
                                        (:runtime-version result) topic payload))
    result))

(defn run-one-due-job!
  "Run at most one due generated job. The caller may poll this operation; no
  generated source receives a thread, executor, or process handle."
  [^Coordinator coordinator]
  (some
   (fn [{:keys [id]}]
     (store/with-session-lock
       (:store coordinator) id
       (fn []
         (when-let [claimed (server/claim-job! (:registry coordinator) id)]
           (let [result (server/run-job! (:registry coordinator) id claimed)]
             (doseq [{:keys [op topic payload]} (:effects result)
                     :when (= :product-event op)]
               (websocket/publish-product-event! (:hub coordinator) id
                                                 (:runtime-version result)
                                                 topic payload))
             (assoc result :session-id id))))))
   (store/list-sessions (:store coordinator))))

(defn readiness
  [^Coordinator coordinator]
  (let [provider-state (provider/ready? (:provider coordinator))
        capacity-state (provider-budget/public-status (:provider-budget coordinator))
        outbound-state (outbound/readiness (:outbound coordinator))]
    {:ready? (boolean (and (:ready? provider-state)
                           (:ready? outbound-state)))
     :storage true
     :provider provider-state
     :change-capacity capacity-state
     :outbound outbound-state
     :sessions (count (store/list-sessions (:store coordinator)))
     :runtime-registry (count @(:active (:registry coordinator)))
     :websocket (websocket/stats (:hub coordinator))}))
