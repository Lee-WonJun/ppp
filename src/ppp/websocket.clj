(ns ppp.websocket
  (:require [cognitect.transit :as transit]
            [org.httpkit.server :as http-kit]
            [ppp.shared.protocol :as protocol])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent ScheduledThreadPoolExecutor ThreadFactory TimeUnit)
           (java.util.concurrent.atomic AtomicLong)))

(defn- daemon-thread-factory
  []
  (let [sequence (AtomicLong.)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable (str "ppp-websocket-timeout-"
                                     (.incrementAndGet sequence)))
          (.setDaemon true))))))

(defrecord Hub [config send-fn close-fn runtime-bundle-fn connections subscriptions
                pending ^ScheduledThreadPoolExecutor scheduler closed?])

(defn encode-message
  [message]
  (let [output (ByteArrayOutputStream.)
        writer (transit/writer output :json)]
    (transit/write writer message)
    (.toString output StandardCharsets/UTF_8)))

(defn decode-message
  [message]
  (when-not (string? message)
    (throw (ex-info "WebSocket messages must use Transit JSON text frames"
                    {:code :protocol/frame-invalid})))
  (let [input (ByteArrayInputStream. (.getBytes ^String message
                                                StandardCharsets/UTF_8))
        reader (transit/reader input :json)]
    (try
      (transit/read reader)
      (catch Exception cause
        (throw (ex-info "WebSocket message is not valid Transit JSON"
                        {:code :protocol/transit-invalid}
                        cause))))))

(defn create-hub
  [{:keys [send-fn close-fn runtime-bundle-fn] :as config}]
  (let [scheduler (ScheduledThreadPoolExecutor. 1 (daemon-thread-factory))]
    (.setRemoveOnCancelPolicy scheduler true)
    (->Hub config
           (or send-fn http-kit/send!)
           (or close-fn http-kit/close)
           runtime-bundle-fn
           (atom {})
           (atom {})
           (atom {})
           scheduler
           (atom false))))

(defn- send-raw!
  [^Hub hub channel message]
  (and (not @(:closed? hub))
       (boolean ((:send-fn hub) channel (encode-message message)))))

(defn send-envelope!
  [hub channel envelope]
  (when-not (contains? protocol/server-message-types (:type envelope))
    (throw (ex-info "Kernel attempted to send an unknown WebSocket message"
                    {:code :protocol/server-message-invalid
                     :type (:type envelope)})))
  (send-raw! hub channel envelope))

(defn- take-pending!
  [^Hub hub transaction-id]
  (loop []
    (let [snapshot @(:pending hub)
          value (get snapshot transaction-id)]
      (cond
        (nil? value) nil
        (compare-and-set! (:pending hub) snapshot
                          (dissoc snapshot transaction-id)) value
        :else (recur)))))

(defn- settle-stage!
  [hub transaction-id outcome]
  (when-let [{:keys [completion timeout]} (take-pending! hub transaction-id)]
    (when timeout
      (.cancel ^java.util.concurrent.Future timeout false))
    (deliver completion outcome)
    true))

(defn open!
  [^Hub hub channel]
  (when @(:closed? hub)
    (throw (ex-info "WebSocket hub is closed" {:code :websocket/closed})))
  (swap! (:connections hub) assoc channel {:connection-id (random-uuid)})
  channel)

(defn- remove-subscription
  [subscriptions session-id tab-id channel]
  (let [remaining (if (= channel (get-in subscriptions [session-id tab-id]))
                    (update subscriptions session-id dissoc tab-id)
                    subscriptions)]
    (if (empty? (get remaining session-id))
      (dissoc remaining session-id)
      remaining)))

(defn close!
  [^Hub hub channel _status]
  (when-let [{:keys [session-id tab-id]} (get @(:connections hub) channel)]
    (swap! (:connections hub) dissoc channel)
    (when (and session-id tab-id)
      (swap! (:subscriptions hub) remove-subscription session-id tab-id channel))
    (doseq [[transaction-id pending] @(:pending hub)
            :when (= channel (:channel pending))]
      (settle-stage! hub transaction-id
                     {:status :rejected
                      :code :runtime/requester-disconnected})))
  nil)

(defn- connection-subscription
  [hub channel]
  (get @(:connections hub) channel))

(defn subscribed?
  "True only when the exact browser tab is subscribed to this session/version."
  [^Hub hub session-id tab-id runtime-version]
  (when-let [channel (get-in @(:subscriptions hub) [session-id tab-id])]
    (let [connection (connection-subscription hub channel)]
      (and (= session-id (:session-id connection))
           (= tab-id (:tab-id connection))
           (= runtime-version (:runtime-version connection))))))

(defn- send-resync!
  [^Hub hub channel session-id request-id]
  (when-let [bundle-fn (:runtime-bundle-fn hub)]
    (let [{:keys [runtime-version] :as bundle} (bundle-fn session-id)]
      (send-envelope!
       hub channel
       (protocol/envelope
        {:session-id session-id
         :request-id request-id
         :runtime-version runtime-version
         :type :runtime/resync
         :payload bundle})))))

(defn request-resync!
  "Rebuild the exact requesting tab from the last durable runtime bundle. Used
  when a Workspace REPL turn exhausts repair and its provisional live forms
  must be discarded."
  [^Hub hub {:keys [session-id tab-id request-id]}]
  (when-let [channel (get-in @(:subscriptions hub) [session-id tab-id])]
    (send-resync! hub channel session-id request-id)))

(defn- subscribe!
  [^Hub hub channel {:keys [session-id request-id payload]}]
  (let [{:keys [tab-id current-version]} payload
        previous (connection-subscription hub channel)]
    (when-let [previous-session (:session-id previous)]
      (swap! (:subscriptions hub) remove-subscription
             previous-session (:tab-id previous) channel))
    (when-let [old-channel (get-in @(:subscriptions hub) [session-id tab-id])]
      (when (not= old-channel channel)
        (swap! (:connections hub)
               (fn [connections]
                 (if-let [old-connection (get connections old-channel)]
                   (assoc connections old-channel
                          (dissoc old-connection :session-id :tab-id))
                   connections)))))
    (swap! (:connections hub) update channel merge
           {:session-id session-id
            :tab-id tab-id
            :runtime-version current-version})
    (swap! (:subscriptions hub) assoc-in [session-id tab-id] channel)
    (when-let [bundle-fn (:runtime-bundle-fn hub)]
      (let [{active-version :runtime-version} (bundle-fn session-id)]
        (when (not= active-version current-version)
          (send-resync! hub channel session-id request-id))))
    true))

(defn- exact-stage-response?
  [pending channel envelope]
  (let [{:keys [session-id request-id runtime-version payload]} envelope]
    (and (= channel (:channel pending))
         (= session-id (:session-id pending))
         (= request-id (:request-id pending))
         (= runtime-version (:target-version pending))
         (= (:tab-id payload) (:tab-id pending))
         (= (:transaction-id payload) (:transaction-id pending))
         (= (:base-version payload) (:base-version pending))
         (= (:target-version payload) (:target-version pending)))))

(defn- receive-stage-response!
  [^Hub hub channel {:keys [type payload session-id request-id] :as envelope}]
  (let [transaction-id (:transaction-id payload)
        pending (get @(:pending hub) transaction-id)]
    (cond
      (nil? pending)
      (send-resync! hub channel session-id request-id)

      (not= channel (:channel pending))
      (send-resync! hub channel session-id request-id)

      (not (exact-stage-response? pending channel envelope))
      (settle-stage! hub transaction-id
                     {:status :rejected :code :runtime/stale-browser-version})

      (= :runtime/staged type)
      (settle-stage! hub transaction-id {:status :staged})

      :else
      (settle-stage! hub transaction-id
                     {:status :rejected
                      :code (or (:code payload) :runtime/client-rejected)
                      :details (:details payload)}))))

(defn- exact-repl-response?
  [pending channel envelope]
  (let [{:keys [session-id request-id runtime-version payload]} envelope]
    (and (= :repl (:kind pending))
         (= channel (:channel pending))
         (= session-id (:session-id pending))
         (= request-id (:request-id pending))
         (= runtime-version (:base-version pending))
         (= (:tab-id payload) (:tab-id pending))
         (= (:evaluation-id payload) (:evaluation-id pending))
         (= (:base-version payload) (:base-version pending)))))

(defn- receive-repl-response!
  [^Hub hub channel {:keys [type payload session-id request-id] :as envelope}]
  (let [evaluation-id (:evaluation-id payload)
        pending (get @(:pending hub) evaluation-id)]
    (cond
      (nil? pending)
      (send-resync! hub channel session-id request-id)

      (not (exact-repl-response? pending channel envelope))
      (settle-stage! hub evaluation-id
                     {:status :rejected :code :runtime/stale-browser-version})

      (= :runtime/repl-result type)
      (settle-stage! hub evaluation-id
                     {:status :accepted :result (:result payload)})

      :else
      (settle-stage! hub evaluation-id
                     {:status :rejected
                      :code (or (:code payload) :repl/client-eval-failed)
                      :details (:details payload)}))))

(defn receive!
  [^Hub hub channel frame]
  (try
    (let [message (decode-message frame)]
      (when-not (protocol/valid-client-envelope? message)
        (throw (ex-info "Client WebSocket message violates protocol v1"
                        {:code :protocol/client-message-invalid})))
      (case (:type message)
        :session/subscribe (subscribe! hub channel message)
        :runtime/staged (receive-stage-response! hub channel message)
        :runtime/rejected (receive-stage-response! hub channel message)
        :runtime/repl-result (receive-repl-response! hub channel message)
        :runtime/repl-rejected (receive-repl-response! hub channel message)))
    (catch Exception _cause
      ((:close-fn hub) channel)))
  nil)

(defn request-stage!
  [^Hub hub {:keys [session-id request-id tab-id transaction-id
                    base-version target-version files capability-version]}]
  (when-not (= target-version (inc base-version))
    (throw (ex-info "Browser stage target must follow its base version"
                    {:code :runtime/version-gap})))
  (if-not (get-in (:config hub) [:require-client-ack?] true)
    {:completion (doto (promise) (deliver {:status :staged}))}
    (let [channel (get-in @(:subscriptions hub) [session-id tab-id])]
      (when-not channel
        (throw (ex-info "The requesting browser tab is not connected"
                        {:code :runtime/requester-not-connected})))
      (let [completion (promise)
            timeout-ms (long (get-in (:config hub) [:client-ack-timeout-ms] 45000))
            pending {:channel channel
                     :session-id session-id
                     :request-id request-id
                     :tab-id tab-id
                     :transaction-id transaction-id
                     :base-version base-version
                     :target-version target-version
                     :completion completion}]
        (when (contains? @(:pending hub) transaction-id)
          (throw (ex-info "Browser stage transaction already exists"
                          {:code :runtime/stage-duplicate})))
        (swap! (:pending hub) assoc transaction-id pending)
        (let [timeout
              (.schedule
               (:scheduler hub)
               ^Runnable
               #(settle-stage! hub transaction-id
                               {:status :rejected :code :runtime/client-timeout})
               timeout-ms TimeUnit/MILLISECONDS)]
          (swap! (:pending hub) update transaction-id assoc :timeout timeout)
          (when-not
           (send-envelope!
            hub channel
            (protocol/envelope
             {:session-id session-id
              :request-id request-id
              :runtime-version target-version
              :type :runtime/stage
              :payload {:tab-id tab-id
                        :transaction-id transaction-id
                        :base-version base-version
                        :target-version target-version
                        :capability-version capability-version
                        :files files}}))
            (settle-stage! hub transaction-id
                           {:status :rejected
                            :code :runtime/requester-disconnected})))
        {:completion completion}))))

(defn await-stage!
  [^Hub hub {:keys [completion]}]
  (let [timeout-ms (+ 1000 (long (get-in (:config hub)
                                         [:client-ack-timeout-ms]
                                         45000)))
        outcome (deref completion timeout-ms ::timeout)]
    (if (= ::timeout outcome)
      (throw (ex-info "Browser stage completion was not delivered"
                      {:code :runtime/client-timeout}))
      outcome)))

(defn request-repl-eval!
  [^Hub hub {:keys [session-id request-id tab-id base-version code]}]
  (when-not (and (string? code) (<= 1 (count code) (* 64 1024)))
    (throw (ex-info "Browser REPL code must be a bounded non-empty string"
                    {:code :repl/client-code-invalid})))
  (let [channel (get-in @(:subscriptions hub) [session-id tab-id])]
    (when-not channel
      (throw (ex-info "The requesting browser tab is not connected"
                      {:code :runtime/requester-not-connected})))
    (let [evaluation-id (random-uuid)
          completion (promise)
          pending {:kind :repl
                   :channel channel
                   :session-id session-id
                   :request-id request-id
                   :tab-id tab-id
                   :evaluation-id evaluation-id
                   :base-version base-version
                   :completion completion}
          timeout-ms 10000]
      (swap! (:pending hub) assoc evaluation-id pending)
      (let [timeout (.schedule
                     (:scheduler hub)
                     ^Runnable
                     #(settle-stage! hub evaluation-id
                                     {:status :rejected
                                      :code :repl/client-eval-timeout})
                     timeout-ms TimeUnit/MILLISECONDS)]
        (swap! (:pending hub) update evaluation-id assoc :timeout timeout)
        (when-not
         (send-envelope!
          hub channel
          (protocol/envelope
           {:session-id session-id
            :request-id request-id
            :runtime-version base-version
            :type :runtime/repl-eval
            :payload {:tab-id tab-id
                      :evaluation-id evaluation-id
                      :base-version base-version
                      :code code}}))
          (settle-stage! hub evaluation-id
                         {:status :rejected
                          :code :runtime/requester-disconnected})))
      {:completion completion :evaluation-id evaluation-id})))

(defn await-repl-eval!
  [^Hub _hub {:keys [completion]}]
  (let [outcome (deref completion 11000 ::timeout)]
    (if (= ::timeout outcome)
      (throw (ex-info "Browser REPL result was not delivered"
                      {:code :repl/client-eval-timeout}))
      outcome)))

(defn send-to-tab!
  [^Hub hub session-id tab-id message]
  (when-let [channel (get-in @(:subscriptions hub) [session-id tab-id])]
    (send-envelope! hub channel message)))

(defn send-turn-event!
  [hub session-id tab-id request-id runtime-version type payload]
  (send-to-tab!
   hub session-id tab-id
   (protocol/envelope {:session-id session-id
                       :request-id request-id
                       :runtime-version runtime-version
                       :type type
                       :payload payload})))

(defn publish-activation!
  [^Hub hub {:keys [session-id request-id tab-id target-version manifest bundle]}]
  (doseq [[subscriber-tab channel] (get @(:subscriptions hub) session-id)]
    (if (= subscriber-tab tab-id)
      (send-envelope!
       hub channel
       (protocol/envelope {:session-id session-id
                           :request-id request-id
                           :runtime-version target-version
                           :type :runtime/activate
                           :payload {:transaction-version target-version
                                     :manifest manifest}}))
      (send-envelope!
       hub channel
       (protocol/envelope {:session-id session-id
                           :request-id request-id
                           :runtime-version target-version
                           :type :runtime/resync
                           :payload bundle})))))

(defn publish-product-event!
  "Deliver an ephemeral event only to tabs subscribed to the exact active
  session version. Durable state remains in SQLite and is reconstructed by an
  action after a reconnect."
  [^Hub hub session-id runtime-version topic payload]
  (let [active-version (when-let [bundle-fn (:runtime-bundle-fn hub)]
                         (:runtime-version (bundle-fn session-id)))]
    (if (and (some? active-version) (not= active-version runtime-version))
      0
      (let [request-id (random-uuid)]
        (reduce
         (fn [delivered [_tab-id channel]]
           (let [connection (connection-subscription hub channel)]
             (if (and (= session-id (:session-id connection))
                      (= runtime-version (:runtime-version connection))
                      (send-envelope!
                       hub channel
                       (protocol/envelope
                        {:session-id session-id
                         :request-id request-id
                         :runtime-version runtime-version
                         :type :product/event
                         :payload {:topic topic :value payload}})))
               (inc delivered)
               delivered)))
         0
         (get @(:subscriptions hub) session-id))))))

(defn handler
  [hub request]
  (if-not (:websocket? request)
    {:status 426
     :headers {"content-type" "text/plain; charset=utf-8"}
     :body "A WebSocket connection is required."}
    (http-kit/as-channel
     request
     {:on-open #(open! hub %)
      :on-receive #(receive! hub %1 %2)
      :on-close #(close! hub %1 %2)})))

(defn stats
  [^Hub hub]
  {:connections (count @(:connections hub))
   :sessions (count @(:subscriptions hub))
   :pending-stages (count @(:pending hub))})

(defn stop!
  [^Hub hub]
  (when (compare-and-set! (:closed? hub) false true)
    (doseq [channel (keys @(:connections hub))]
      ((:close-fn hub) channel))
    (doseq [transaction-id (keys @(:pending hub))]
      (settle-stage! hub transaction-id
                     {:status :rejected :code :websocket/closed}))
    (.shutdownNow (:scheduler hub)))
  nil)
