(ns ppp.client.frame
  (:require [clojure.string :as str]
            [ppp.client.composer :as composer]
            [ppp.client.frame-protocol :as protocol]
            [ppp.client.runtime :as runtime]
            [reagent.core :as r]
            [reagent.dom.client :as rdom]))

(defonce root (atom nil))
(defonce displayed-runtime (r/atom nil))
(defonce render-epoch (r/atom 0))
(defonce sidebar-model (r/atom {}))
(defonce sidebar-open? (r/atom false))
(defonce canvas-context (r/atom {}))
(defonce pending-actions (atom {}))
(defonce state-watch-key (keyword (str "frame-state-" (random-uuid))))
(defonce stage-state (atom {:settled? true :phase :idle}))
(defonce render-flush-queued? (atom false))

(defn- channel
  []
  (.get (js/URLSearchParams. (subs (.-hash js/location) 1)) "channel"))

(defn- post!
  [type payload]
  (.postMessage js/parent
                (protocol/encode (protocol/envelope (channel) type payload))
                "*"))

(defn- runtime-error-code
  [error]
  (loop [current error
         depth 0]
    (when (and current (< depth 16))
      (or (:cause-code (ex-data current))
          (:code (ex-data current))
          (recur (ex-cause current) (inc depth))))))

(defn- runtime-error-details
  [error]
  (loop [current error
         depth 0
         details []]
    (if (and current (< depth 8))
      (recur (ex-cause current)
             (inc depth)
             (cond-> details
               (not (str/blank? (.-message current)))
               (conj (.-message current))))
      (vec (distinct details)))))

(defn- sidebar-event!
  ([event]
   (sidebar-event! event nil))
  ([event value]
   (post! :frame/sidebar-event {:event event :value value})))

(defn- sidebar-props
  []
  (assoc @sidebar-model
         :select-session! #(sidebar-event! :select-session %)
         :new-session! #(sidebar-event! :new-session)
         :restore! #(sidebar-event! :restore %)
         :draft-change! #(sidebar-event! :draft-change %)
         :send! #(sidebar-event! :send)))

(defn- handle-sidebar-key-down!
  [event]
  (when (composer/message-textarea-event? event)
    (let [{:keys [busy? draft]} @sidebar-model]
      (composer/handle-key-down!
       event busy? draft #(sidebar-event! :send)))))

(defn- settle-stage!
  [outcome payload]
  (when-not (:settled? @stage-state)
    (swap! stage-state assoc :settled? true)
    (post! outcome payload)))

(defn- stage-commit-ref!
  [version node]
  (let [{:keys [phase settled? commit-queued?]} @stage-state]
    (when (and node
               (= :staging phase)
               (not settled?)
               (not commit-queued?)
               (= version (:version @stage-state)))
      (swap! stage-state assoc :commit-queued? true)
      ;; React calls this fixed-shell ref only after the generated page and
      ;; sidebar share one DOM commit. A microtask lets synchronous React error
      ;; callbacks reject first, without relying on animation scheduling that
      ;; hidden/background frames are allowed to suppress.
      (js/queueMicrotask
       (fn []
         (let [state @stage-state]
           (when (and (= :staging (:phase state))
                      (not (:settled? state))
                      (= version (:version state)))
             (settle-stage! :frame/staged {:version version}))))))))

(defn- frame-shell
  []
  (let [epoch @render-epoch]
    (when-let [client-runtime @displayed-runtime]
      [:<>
       [:div.ppp-frame-canvas {:data-ppp-surface "page"}
        (with-meta [(:page client-runtime) @canvas-context]
          {:key (str "page-" epoch)})]
       (when @sidebar-open?
         [:div.ppp-frame-sidebar {:data-ppp-surface "sidebar"
                                  :on-key-down handle-sidebar-key-down!}
          (with-meta [(:sidebar client-runtime) (sidebar-props)]
            {:key (str "sidebar-" epoch)})])
       [:span {:data-ppp-stage-commit (:version client-runtime)
               :aria-hidden true
               :hidden true
               :ref #(stage-commit-ref! (:version client-runtime) %)}]])))

(defn- runtime-failed!
  [error]
  (let [phase (:phase @stage-state)
        payload {:code (or (runtime-error-code error)
                           (if (= :staging phase)
                             :runtime/client-render-failed
                             :runtime/client-frame-runtime))
                 :phase phase
                 :details (runtime-error-details error)}]
    (if (= :staging (:phase @stage-state))
      (settle-stage! :frame/rejected payload)
      (post! :frame/runtime-error payload))))

(defn- schedule-render-flush!
  []
  (when (compare-and-set! render-flush-queued? false true)
    (js/queueMicrotask
     (fn []
       (reset! render-flush-queued? false)
       (try
         (r/flush)
         (catch :default error
           (runtime-failed! error))))))
  nil)

(defn- action!
  [action-id payload]
  (let [request-id (str (random-uuid))]
    (js/Promise.
     (fn [resolve reject]
       (swap! pending-actions assoc request-id {:resolve resolve :reject reject})
       (post! :frame/action
              {:request-id request-id
               :action-id action-id
               :payload payload})))))

(defn- publish-state!
  [state]
  (try
    (post! :frame/state {:state state})
    (catch :default _
      (post! :frame/runtime-error
             {:code :runtime/client-state-not-serializable
              :phase :state-handoff}))))

(defn- watch-runtime-state!
  [client-runtime]
  (add-watch (:state client-runtime) state-watch-key
             (fn [_ _ _ next-state]
               (publish-state! next-state)
               (schedule-render-flush!))))

(defn- remove-runtime-watch!
  []
  (when-let [client-runtime @displayed-runtime]
    (remove-watch (:state client-runtime) state-watch-key)))

(defn- source-map
  [files]
  (into {} (map (juxt :path :content)) files))

(defn- stage!
  [{:keys [version files state session-id sidebar-model sidebar-open?
           sidebar-enabled?]}]
  (remove-runtime-watch!)
  (runtime/reset-runtime!)
  (reset! runtime/base-state (or state {}))
  (try
    (let [client-runtime
          (runtime/stage-source!
           {:version version
            :source-map (source-map files)
            :action-fn action!
            :runtime-error-fn runtime-failed!
            :sidebar-enabled? (not= false sidebar-enabled?)})]
      (runtime/retain-stage! client-runtime)
      (reset! canvas-context {:session-id session-id})
      (reset! ppp.client.frame/sidebar-model (or sidebar-model {}))
      (reset! ppp.client.frame/sidebar-open? (boolean sidebar-open?))
      (reset! displayed-runtime client-runtime)
      (swap! render-epoch inc)
      (when-let [style (.getElementById js/document "ppp-generated-style")]
        (set! (.-textContent style)
              (str/replace (:css client-runtime) ":host" ":root")))
      (watch-runtime-state! client-runtime)
      (reset! stage-state {:settled? false
                           :phase :staging
                           :version version
                           :commit-queued? false})
      (r/flush))
    (catch :default error
      (reset! stage-state {:settled? false
                           :phase :staging
                           :version version
                           :commit-queued? false})
      (runtime-failed! error))))

(defn- activate!
  [{:keys [version]}]
  (try
    (let [client-runtime (runtime/activate! version)]
      (reset! displayed-runtime client-runtime)
      (reset! stage-state {:settled? true :phase :active :version version})
      ;; Staging renders with host actions suppressed. Activation must render
      ;; the same component identity once more after the runtime phase becomes
      ;; active so ensure-action!, timers, and other lifecycle work can start.
      (swap! render-epoch inc)
      (r/flush)
      (post! :frame/activated {:version version}))
    (catch :default error
      (runtime-failed! error))))

(defn- action-result!
  [{:keys [request-id value message]} success?]
  (when-let [{:keys [resolve reject]} (get @pending-actions request-id)]
    (swap! pending-actions dissoc request-id)
    (if success?
      (resolve value)
      (reject (js/Error. (or message "The product action failed."))))))

(defn- receive!
  [event]
  (when (identical? (.-source event) js/parent)
    (try
      (let [message (protocol/decode (.-data event))]
        (when (and (protocol/valid-envelope? protocol/host-message-types message)
                   (= (channel) (:channel message)))
          (let [{:keys [type payload]} message]
            (case type
              :host/stage (stage! payload)
              :host/activate (activate! payload)
              :host/sidebar-model
              (do
                (reset! sidebar-model (:model payload))
                (schedule-render-flush!))
              :host/sidebar-open
              (do
                (reset! sidebar-open? (boolean (:open? payload)))
                (schedule-render-flush!))
              :host/action-result (action-result! payload true)
              :host/action-error (action-result! payload false)
              :host/set-state
              (when-let [client-runtime @displayed-runtime]
                (reset! (:state client-runtime) (:state payload))
                (schedule-render-flush!))
              :host/dispose
              (do
                (remove-runtime-watch!)
                (runtime/reset-runtime!)
                (reset! displayed-runtime nil))
              nil))))
      (catch :default _
        nil))))

(defn- safe-mode-shortcut!
  [event]
  (when (and (.-ctrlKey event)
             (.-altKey event)
             (.-shiftKey event)
             (= "p" (str/lower-case (.-key event))))
    (.preventDefault event)
    (.stopImmediatePropagation event)
    (post! :frame/safe-mode {})))

(defn ^:export init!
  []
  (when-let [container (.getElementById js/document "ppp-frame-root")]
    (let [error-handler (fn [error & _] (runtime-failed! error))
          react-root
          (rdom/create-root
           container
           #js {:onUncaughtError error-handler
                :onCaughtError error-handler
                :onRecoverableError error-handler})]
      (reset! root react-root)
      (rdom/render react-root [frame-shell])
      ;; Key events do not bubble across iframe boundaries. Install the
      ;; immutable recovery shortcut before any generated source is evaluated
      ;; and relay only this reserved chord to the authenticated parent.
      (.addEventListener js/window "keydown" safe-mode-shortcut! true)
      (.addEventListener js/window "message" receive!)
      (post! :frame/ready {}))))
