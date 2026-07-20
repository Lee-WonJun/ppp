(ns ppp.client.frame
  (:require [clojure.string :as str]
            [ppp.client.composer :as composer]
            [ppp.client.frame-protocol :as protocol]
            [ppp.client.runtime :as runtime]
            [ppp.shared.protocol :as shared-protocol]
            [reagent.core :as r]
            [reagent.dom.client :as rdom]))

(defonce root (atom nil))
(defonce displayed-runtime (r/atom nil))
(defonce render-epoch (r/atom 0))
(defonce sidebar-model (r/atom {}))
(defonce composer-draft (r/atom ""))
(defonce composer-draft-revision (atom 0))
(defonce sidebar-open? (r/atom false))
(defonce canvas-context (r/atom {}))
(defonce pending-actions (atom {}))
(defonce state-watch-key (keyword (str "frame-state-" (random-uuid))))
(defonce stage-state (atom {:settled? true :phase :idle}))
(defonce render-flush-queued? (atom false))
(defonce diagnostics-installed? (atom false))

(declare schedule-render-flush!)

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

(defn- diagnostic!
  [value]
  ;; Staging failures already travel through the transaction rejection path.
  ;; Runtime evidence is intentionally limited to the currently active product.
  (when (= :active (:phase @stage-state))
    (try
      (when-let [diagnostic (shared-protocol/normalize-client-diagnostic value)]
        (post! :frame/diagnostic diagnostic))
      (catch :default _ nil)))
  nil)

(defn- throwable-message
  [value]
  (cond
    (string? value) value
    (some? value) (or (ex-message value) (.-message value))
    :else nil))

(defn- throwable-code
  [value]
  (when value
    (or (:cause-code (ex-data value))
        (:code (ex-data value)))))

(defn- diagnostic-argument
  [value]
  (cond
    (string? value) value
    (number? value) (str value)
    (boolean? value) (str value)
    (some? value) (throwable-message value)
    :else nil))

(defn- diagnostic-console-message
  [arguments]
  (->> arguments
       (keep diagnostic-argument)
       (take 4)
       (str/join " ")))

(defn- safe-network-url
  [input]
  (try
    (let [raw (if (string? input) input (.-url input))
          parsed (js/URL. raw (.-href js/location))
          scheme (.-protocol parsed)]
      (if (contains? #{"http:" "https:"} scheme)
        (str scheme "//" (.-host parsed) (or (.-pathname parsed) "/"))
        "/"))
    (catch :default _ "/")))

(defn- fetch-method
  [input init]
  (str/upper-case
   (str (or (some-> init .-method)
            (when-not (string? input) (.-method input))
            "GET"))))

(defn- install-diagnostics!
  []
  (when (compare-and-set! diagnostics-installed? false true)
    (let [original-warn (.-warn js/console)
          original-error (.-error js/console)
          original-fetch (.-fetch js/window)]
      (set! (.-warn js/console)
            (fn [& arguments]
              (.apply original-warn js/console (to-array arguments))
              (diagnostic! {:kind :console
                            :level :warn
                            :message (diagnostic-console-message arguments)})))
      (set! (.-error js/console)
            (fn [& arguments]
              (.apply original-error js/console (to-array arguments))
              (diagnostic! {:kind :console
                            :level :error
                            :message (diagnostic-console-message arguments)})))
      (set! (.-fetch js/window)
            (fn [input & [init]]
              (let [method (fetch-method input init)
                    url (safe-network-url input)]
                (-> (.call original-fetch js/window input init)
                    (.then
                     (fn [response]
                       (when-not (.-ok response)
                         (diagnostic! {:kind :network
                                       :method method
                                       :url url
                                       :status (.-status response)}))
                       response))
                    (.catch
                     (fn [error]
                       (diagnostic! {:kind :network
                                     :method method
                                     :url url
                                     :message (or (throwable-message error)
                                                  "The request failed.")})
                       (throw error)))))))
      (.addEventListener
       js/window "error"
       (fn [event]
         (let [error (.-error event)]
           (diagnostic! {:kind :runtime
                         :code (or (throwable-code error)
                                   :runtime/window-error)
                         :message (or (throwable-message error)
                                      (.-message event)
                                      "The product interaction failed.")}))))
      (.addEventListener
       js/window "unhandledrejection"
       (fn [event]
         (let [reason (.-reason event)]
           (diagnostic! {:kind :runtime
                         :code (or (throwable-code reason)
                                   :runtime/unhandled-rejection)
                         :message (or (throwable-message reason)
                                      "A product request was not handled.")}))))))
  nil)

(defn- sidebar-event!
  ([event]
   (sidebar-event! event nil))
  ([event value]
   (post! :frame/sidebar-event {:event event :value value})))

(defn- model-draft-revision
  [model]
  (let [revision (:draft-revision model)]
    (if (number? revision) revision 0)))

(defn- reset-composer-draft!
  [model]
  (reset! composer-draft (str (or (:draft model) "")))
  (reset! composer-draft-revision (model-draft-revision model)))

(defn- sync-sidebar-model!
  [model]
  (let [model (or model {})
        revision (model-draft-revision model)]
    (reset! sidebar-model model)
    ;; Draft input is immediate frame-local state. The parent retains a copy
    ;; for submission and recovery, but an older echo must never rewrite an
    ;; active IME candidate and terminate the browser's composition session.
    (when (>= revision @composer-draft-revision)
      (reset! composer-draft-revision revision)
      (reset! composer-draft (str (or (:draft model) ""))))))

(defn- change-composer-draft!
  [value]
  (let [draft (str value)
        revision (swap! composer-draft-revision inc)]
    (reset! composer-draft draft)
    (sidebar-event! :draft-change {:draft draft :revision revision})
    (schedule-render-flush!)))

(defn- sidebar-props
  []
  (assoc @sidebar-model
         :draft @composer-draft
         :select-session! #(sidebar-event! :select-session %)
         :new-session! #(sidebar-event! :new-session)
         :all-projects! #(sidebar-event! :all-projects)
         :restore! #(sidebar-event! :restore %)
         :draft-change! change-composer-draft!
         :send! #(sidebar-event! :send)))

(defn- handle-sidebar-key-down!
  [event]
  (when (composer/message-textarea-event? event)
    (let [{:keys [busy?]} @sidebar-model]
      (composer/handle-key-down!
       event busy? @composer-draft #(sidebar-event! :send)))))

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
    (when (= :active phase)
      (diagnostic! {:kind :runtime
                    :code (:code payload)
                    :message (or (throwable-message error)
                                 "The active product failed.")}))
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
       (swap! pending-actions assoc request-id {:resolve resolve
                                                :reject reject
                                                :action-id action-id})
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
      (let [model (or sidebar-model {})]
        (reset! ppp.client.frame/sidebar-model model)
        (reset-composer-draft! model))
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

(defn- repl-eval!
  [{:keys [request-id code]}]
  (try
    (let [{:keys [runtime result]}
          (runtime/eval-runtime! @displayed-runtime code)]
      (runtime/retain-evaluated! runtime)
      (reset! displayed-runtime runtime)
      (swap! render-epoch inc)
      (r/flush)
      (publish-state! @(:state runtime))
      (post! :frame/repl-result {:request-id request-id :result result}))
    (catch :default error
      (post! :frame/repl-rejected
             {:request-id request-id
              :code (or (runtime-error-code error) :repl/client-eval-failed)
              :details (runtime-error-details error)}))))

(defn- action-result!
  [{:keys [request-id value message code status]} success?]
  (when-let [{:keys [resolve reject action-id]} (get @pending-actions request-id)]
    (swap! pending-actions dissoc request-id)
    (if success?
      (resolve value)
      (do
        (diagnostic! {:kind :action
                      :action-id action-id
                      :code code
                      :status status
                      :message (or message "The product action failed.")})
        (reject (ex-info (or message "The product action failed.")
                         {:code code :status status}))))))

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
              :host/repl-eval (repl-eval! payload)
              :host/sidebar-model
              (do
                (sync-sidebar-model! (:model payload))
                (schedule-render-flush!))
              :host/sidebar-open
              (do
                (reset! sidebar-open? (boolean (:open? payload)))
                (schedule-render-flush!))
              :host/action-result (action-result! payload true)
              :host/action-error (action-result! payload false)
              :host/product-event
              (do
                (runtime/deliver-event! (:topic payload) (:value payload))
                (schedule-render-flush!))
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
      (install-diagnostics!)
      (.addEventListener js/window "message" receive!)
      (post! :frame/ready {}))))
