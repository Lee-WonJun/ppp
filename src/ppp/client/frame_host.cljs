(ns ppp.client.frame-host
  (:require [ppp.client.frame-protocol :as protocol]
            [reagent.core :as r]))

(defrecord FrameRuntime [version channel element state source-map sidebar-enabled?])

(defonce host-node (atom nil))
(defonce active-runtime (r/atom nil))
(defonce staged-runtimes (atom {}))
(defonce frames (atom {}))
(defonce pending-stages (atom {}))
(defonce pending-repl-evals (atom {}))
(defonce active-state (r/atom {}))
(defonce listener-installed? (atom false))
(defonce callbacks
  (atom {:action-fn (fn [_ _]
                      (js/Promise.reject
                       (js/Error. "Action transport is unavailable.")))
         :sidebar-event-fn (fn [_ _] nil)
         :safe-mode-fn (fn [] nil)
         :runtime-error-fn (fn [_] nil)
         :diagnostic-fn (fn [_] nil)
         :sidebar-model-fn (constantly {})
         :sidebar-open-fn (constantly false)
         :session-id-fn (constantly nil)}))

(def ^:private frame-load-timeout-ms 30000)
(def ^:private frame-render-timeout-ms 10000)
(def ^:private repl-eval-timeout-ms 10000)

(defn- clear-timeout!
  [timeout]
  (when timeout
    (js/clearTimeout timeout)))

(defn- post!
  [runtime type payload]
  (when-let [target (some-> runtime :element .-contentWindow)]
    (.postMessage target
                  (protocol/encode
                   (protocol/envelope (:channel runtime) type payload))
                  "*")))

(defn- dispose-frame!
  [runtime]
  (when runtime
    (post! runtime :host/dispose {})
    (swap! frames dissoc (:channel runtime))
    (.remove (:element runtime)))
  nil)

(defn register-host!
  [node]
  (reset! host-node node)
  (when (and node @active-runtime)
    (.append node (:element @active-runtime)))
  node)

(defn configure!
  [options]
  (swap! callbacks merge options)
  nil)

(defn active-version
  []
  (:version @active-runtime))

(defn active-page-state
  []
  active-state)

(defn- frame-for-event
  [event message]
  (let [runtime (get @frames (:channel message))]
    (when (and runtime
               (identical? (.-source event)
                           (some-> runtime :element .-contentWindow)))
      runtime)))

(defn- settle-stage!
  [runtime outcome value]
  (when-let [{:keys [resolve reject load-timeout render-timeout]}
             (get @pending-stages (:channel runtime))]
    (clear-timeout! load-timeout)
    (clear-timeout! render-timeout)
    (swap! pending-stages dissoc (:channel runtime))
    (if (= :resolve outcome)
      (do
        (swap! staged-runtimes assoc (:version runtime) runtime)
        (resolve runtime))
      (do
        (dispose-frame! runtime)
        (reject value)))))

(defn- action-result!
  [runtime {:keys [request-id action-id payload]}]
  (-> (js/Promise.resolve ((:action-fn @callbacks) action-id payload))
      (.then #(post! runtime :host/action-result
                     {:request-id request-id :value %}))
      (.catch (fn [cause]
                (let [data (ex-data cause)]
                  (post! runtime :host/action-error
                         {:request-id request-id
                          :code (:code data)
                          :status (:status data)
                          :message (or (ex-message cause)
                                       (.-message cause)
                                       "The product action failed.")}))))))

(defn- settle-repl-eval!
  [runtime {:keys [request-id result code details]} success?]
  (when-let [{:keys [resolve reject timeout expected-runtime]}
             (get @pending-repl-evals request-id)]
    (when (identical? runtime expected-runtime)
      (clear-timeout! timeout)
      (swap! pending-repl-evals dissoc request-id)
      (if success?
        (resolve result)
        (reject (ex-info "Browser REPL evaluation failed"
                         {:code (or code :repl/client-eval-failed)
                          :details details}))))))

(defn- receive!
  [event]
  (try
    (let [message (protocol/decode (.-data event))]
      (when (protocol/valid-envelope? protocol/frame-message-types message)
        (when-let [runtime (frame-for-event event message)]
          (let [{:keys [type payload]} message]
            (case type
              :frame/ready
              (when-let [{:keys [source-map phase load-timeout]}
                         (get @pending-stages (:channel runtime))]
                (when (= :loading phase)
                  (clear-timeout! load-timeout)
                  (let [render-timeout
                        (js/setTimeout
                         (fn []
                           (settle-stage!
                            runtime :reject
                            (ex-info "Generated frame rendering timed out"
                                     {:code :runtime/client-frame-render-timeout
                                      :phase :rendering})))
                         frame-render-timeout-ms)]
                    (swap! pending-stages update (:channel runtime)
                           assoc
                           :phase :rendering
                           :load-timeout nil
                           :render-timeout render-timeout)
                    (post! runtime :host/stage
                           {:version (:version runtime)
                            :files (mapv (fn [[path content]]
                                           {:path path :content content})
                                         source-map)
                            :state @(:state runtime)
                            :session-id ((:session-id-fn @callbacks))
                            :sidebar-model ((:sidebar-model-fn @callbacks))
                            :sidebar-open? ((:sidebar-open-fn @callbacks))
                            :sidebar-enabled? (:sidebar-enabled? runtime)}))))

              :frame/staged
              (settle-stage! runtime :resolve runtime)

              :frame/rejected
              (settle-stage!
               runtime :reject
               (ex-info "Generated frame failed staging"
                        {:code (or (:code payload) :runtime/client-frame-rejected)
                         :phase (:phase payload)
                         :details (:details payload)}))

              :frame/repl-result
              (settle-repl-eval! runtime payload true)

              :frame/repl-rejected
              (settle-repl-eval! runtime payload false)

              :frame/state
              (do
                (reset! (:state runtime) (:state payload))
                (when (identical? runtime @active-runtime)
                  (reset! active-state (:state payload))))

              :frame/action
              (action-result! runtime payload)

              :frame/sidebar-event
              ((:sidebar-event-fn @callbacks) (:event payload) (:value payload))

              :frame/safe-mode
              ((:safe-mode-fn @callbacks))

              :frame/runtime-error
              ((:runtime-error-fn @callbacks)
               (ex-info "Generated frame runtime failed"
                        {:code (or (:code payload) :runtime/client-frame-runtime)
                         :phase (:phase payload)
                         :details (:details payload)}))

              :frame/diagnostic
              (when (identical? runtime @active-runtime)
                ((:diagnostic-fn @callbacks) payload))

              nil)))))
    (catch :default _
      ;; Ignore malformed or oversized messages. A valid frame that never
      ;; completes still fails through the bounded staging timeout.
      nil)))

(defn install-listener!
  []
  (when (compare-and-set! listener-installed? false true)
    (.addEventListener js/window "message" receive!))
  nil)

(defn- new-frame!
  [version source-map sidebar-enabled?]
  (when-not @host-node
    (throw (ex-info "Generated frame host is not mounted"
                    {:code :runtime/client-frame-host-missing})))
  (let [channel (str (random-uuid))
        element (.createElement js/document "iframe")
        state (atom @active-state)
        runtime (->FrameRuntime version channel element state source-map
                                sidebar-enabled?)]
    (.setAttribute element "title" "Programmable product")
    (.setAttribute element "sandbox" "allow-scripts allow-forms allow-modals allow-downloads")
    (.setAttribute element "referrerpolicy" "no-referrer")
    (.setAttribute element "data-ppp-runtime-frame" (str version))
    (.setAttribute element "data-ppp-runtime-channel" channel)
    (set! (.-className element) "ppp-runtime-frame ppp-runtime-frame-staged")
    (set! (.-src element) (str "/frame.html#channel=" channel))
    (swap! frames assoc channel runtime)
    runtime))

(defn stage!
  ([version source-map]
   (stage! version source-map {}))
  ([version source-map {:keys [sidebar-enabled?]
                        :or {sidebar-enabled? true}}]
   (install-listener!)
   ;; A durable source stage replaces the exploratory REPL branch for the same
   ;; target version. Never activate the hidden exploratory frame itself.
   (when-let [previous (get @staged-runtimes version)]
     (swap! staged-runtimes dissoc version)
     (dispose-frame! previous))
   (let [runtime (new-frame! version source-map sidebar-enabled?)]
     (js/Promise.
      (fn [resolve reject]
        (let [load-timeout
              (js/setTimeout
               (fn []
                 (settle-stage!
                  runtime :reject
                  (ex-info "Generated frame loading timed out"
                           {:code :runtime/client-frame-load-timeout
                            :phase :loading})))
               frame-load-timeout-ms)]
          (swap! pending-stages assoc (:channel runtime)
                 {:resolve resolve
                  :reject reject
                  :phase :loading
                  :load-timeout load-timeout
                  :render-timeout nil
                  :source-map source-map})
          ;; Install the pending transaction before attaching the iframe. A
          ;; cached frame can post :frame/ready immediately after attachment.
          (.append @host-node (:element runtime))))))))

(defn activate!
  [version]
  (let [runtime (get @staged-runtimes version)]
    (when-not runtime
      (throw (ex-info "Generated frame was not staged"
                      {:code :runtime/client-frame-not-staged
                       :version version})))
    (let [previous @active-runtime]
      (swap! staged-runtimes dissoc version)
      (reset! active-runtime runtime)
      (reset! active-state @(:state runtime))
      (set! (.-className (:element runtime)) "ppp-runtime-frame")
      (post! runtime :host/activate {:version version})
      (when previous
        (dispose-frame! previous))
      runtime)))

(defn recycle-active!
  "Rebuild the current product in a fresh opaque frame and atomically remove
  the old one. Browser-owned timers and listeners cannot survive frame
  destruction, while serializable page state is handed to the replacement."
  [sidebar-enabled?]
  (if-let [runtime @active-runtime]
    (let [version (:version runtime)
          source-map (:source-map runtime)]
      (-> (stage! version source-map {:sidebar-enabled? sidebar-enabled?})
          (.then (fn [_] (activate! version)))))
    (js/Promise.resolve nil)))

(defn discard-stage!
  [version]
  (when-let [runtime (get @staged-runtimes version)]
    (swap! staged-runtimes dissoc version)
    (dispose-frame! runtime))
  nil)

(defn set-state!
  [value]
  (reset! active-state value)
  (when-let [runtime @active-runtime]
    (reset! (:state runtime) value)
    (post! runtime :host/set-state {:state value}))
  value)

(defn update-sidebar!
  []
  (when-let [runtime @active-runtime]
    (post! runtime :host/sidebar-model
           {:model ((:sidebar-model-fn @callbacks))})
    (post! runtime :host/sidebar-open
           {:open? ((:sidebar-open-fn @callbacks))}))
  nil)

(defn deliver-product-event!
  [topic value]
  (when-let [runtime @active-runtime]
    (post! runtime :host/product-event {:topic topic :value value}))
  nil)

(defn- eval-in-frame!
  [runtime code]
  (if runtime
    (let [request-id (str (random-uuid))]
      (js/Promise.
       (fn [resolve reject]
         (let [timeout
               (js/setTimeout
                (fn []
                  (swap! pending-repl-evals dissoc request-id)
                  (reject (ex-info "Browser REPL evaluation timed out"
                                   {:code :repl/client-eval-timeout})))
                repl-eval-timeout-ms)]
           (swap! pending-repl-evals assoc request-id
                  {:resolve resolve
                   :reject reject
                   :timeout timeout
                   :expected-runtime runtime})
           (post! runtime :host/repl-eval {:request-id request-id :code code})))))
    (js/Promise.reject
     (ex-info "Browser runtime is unavailable"
              {:code :runtime/client-not-active}))))

(defn eval-live!
  "Evaluate CLJS forms in the public active opaque frame."
  [code]
  (eval-in-frame! @active-runtime code))

(defn eval-branch!
  "Evaluate forms in a hidden frame cloned from the active product. Successive
  calls for target-version retain the same SCI context and serializable state.
  A later durable source stage for that version replaces this frame."
  [target-version code]
  (if-let [runtime (get @staged-runtimes target-version)]
    (eval-in-frame! runtime code)
    (if-let [active @active-runtime]
      (-> (stage! target-version (:source-map active)
                  {:sidebar-enabled? (:sidebar-enabled? active)})
          (.then #(eval-in-frame! % code)))
      (js/Promise.reject
       (ex-info "Browser runtime is not active"
                {:code :runtime/client-not-active})))))

(defn reset-runtime!
  []
  (doseq [[_ pending] @pending-stages]
    (clear-timeout! (:load-timeout pending))
    (clear-timeout! (:render-timeout pending))
    ((:reject pending)
     (ex-info "Generated frame was reset"
              {:code :runtime/client-frame-reset})))
  (reset! pending-stages {})
  (doseq [[_ {:keys [reject timeout]}] @pending-repl-evals]
    (clear-timeout! timeout)
    (reject (ex-info "Browser REPL evaluation was reset"
                     {:code :repl/client-eval-reset})))
  (reset! pending-repl-evals {})
  (doseq [runtime (vals @frames)]
    (dispose-frame! runtime))
  (reset! frames {})
  (reset! staged-runtimes {})
  (reset! active-runtime nil)
  (reset! active-state {})
  nil)
