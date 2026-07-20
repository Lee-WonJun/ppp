(ns ppp.client.transport
  (:require [cognitect.transit :as transit]
            [ppp.shared.protocol :as protocol]))

(defonce tab-id (random-uuid))
(defonce socket (atom nil))
(defonce reconnect-timer (atom nil))
(defonce reconnect-attempt (atom 0))
(defonce stopped? (atom false))
(defonce diagnostics (atom {:received nil :dispatched nil :error nil}))
(defonce callbacks
  (atom {:session-fn (constantly nil)
         :version-fn (constantly 0)
         :on-message (fn [_])
         :on-status (fn [_])}))

(def writer (transit/writer :json))
(def reader (transit/reader :json))

(declare connect! subscribe!)

(defn- websocket-url
  []
  (str (if (= "https:" (.-protocol js/location)) "wss://" "ws://")
       (.-host js/location)
       "/ws"))

(defn- notify-status!
  [status]
  ((:on-status @callbacks) status))

(defn connected?
  []
  (= (.-OPEN js/WebSocket) (some-> @socket .-readyState)))

(defn send!
  [message]
  (if (connected?)
    (do
      (.send @socket (transit/write writer message))
      true)
    false))

(defn envelope
  [session-id request-id runtime-version type payload]
  (protocol/envelope {:session-id (if (uuid? session-id)
                                    session-id
                                    (uuid session-id))
                      :request-id request-id
                      :runtime-version (or runtime-version 0)
                      :type type
                      :payload payload}))

(defn subscribe!
  []
  (when-let [session-id ((:session-fn @callbacks))]
    (let [version (or ((:version-fn @callbacks)) 0)]
      (send! (envelope session-id (random-uuid) version
                       :session/subscribe
                       {:tab-id tab-id :current-version version})))))

(defn send-stage-result!
  [{:keys [session-id request-id target-version transaction-id base-version
           status code details]}]
  (send!
   (envelope session-id request-id target-version
             (if (= :staged status) :runtime/staged :runtime/rejected)
             (cond-> {:tab-id tab-id
                      :transaction-id transaction-id
                      :base-version base-version
                      :target-version target-version}
               (not= :staged status)
               (assoc :code (or code :runtime/client-rejected)
                      :details (vec (take 4 details)))))))

(defn send-repl-result!
  [{:keys [session-id request-id runtime-version evaluation-id status result
           code details]}]
  (send!
   (envelope session-id request-id runtime-version
             (if (= :accepted status)
               :runtime/repl-result
               :runtime/repl-rejected)
             (cond-> {:tab-id tab-id
                      :evaluation-id evaluation-id
                      :base-version runtime-version}
               (= :accepted status) (assoc :result result)
               (not= :accepted status)
               (assoc :code (or code :repl/client-eval-failed)
                      :details (vec (take 4 details)))))))

(defn- schedule-reconnect!
  []
  (when (and (not @stopped?) (nil? @reconnect-timer))
    (let [attempt (swap! reconnect-attempt inc)
          delay (min 5000 (* 250 (js/Math.pow 2 (dec attempt))))]
      (reset! reconnect-timer
              (js/setTimeout
               (fn []
                 (reset! reconnect-timer nil)
                 (connect!))
               delay)))))

(defn- receive!
  [event]
  (try
    (let [message (transit/read reader (.-data event))
          valid? (protocol/valid-envelope? message)
          known? (contains? protocol/server-message-types (:type message))]
      (reset! diagnostics {:received {:type (:type message)
                                      :valid? valid?
                                      :known? known?}
                           :dispatched (:dispatched @diagnostics)
                           :error nil})
      (when (and valid? known?)
        ((:on-message @callbacks) message)
        (swap! diagnostics assoc :dispatched (:type message))))
    (catch :default error
      (swap! diagnostics assoc :error {:message (.-message error)
                                       :data (ex-data error)})
      (when-let [current @socket]
        (.close current 1002 "Invalid protocol message")))))

(defn connect!
  ([]
   (connect! nil))
  ([options]
   (when options
     (swap! callbacks merge options))
   (reset! stopped? false)
   (when-not (or (connected?)
                 (= (.-CONNECTING js/WebSocket)
                    (some-> @socket .-readyState)))
     (let [current (js/WebSocket. (websocket-url))]
       (reset! socket current)
       (set! (.-onopen current)
             (fn []
               (reset! reconnect-attempt 0)
               (notify-status! :connected)
               (subscribe!)))
       (set! (.-onmessage current) receive!)
       (set! (.-onerror current)
             (fn [_]
               (notify-status! :disconnected)))
       (set! (.-onclose current)
             (fn [_]
               (when (identical? current @socket)
                 (reset! socket nil))
               (notify-status! :disconnected)
               (schedule-reconnect!)))))
   nil))

(defn stop!
  []
  (reset! stopped? true)
  (when-let [timer @reconnect-timer]
    (js/clearTimeout timer)
    (reset! reconnect-timer nil))
  (when-let [current @socket]
    (set! (.-onclose current) nil)
    (.close current 1000 "Host stopped")
    (reset! socket nil))
  nil)
