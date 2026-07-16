(ns ppp.client.core
  (:require [clojure.string :as str]
            [ppp.client.composer :as composer]
            [ppp.client.frame-host :as frame]
            [ppp.client.transport :as transport]
            [reagent.core :as r]
            [reagent.dom.client :as rdom]))

(defonce root (atom nil))
(defonce load-sequence (atom 0))
(defonce hold-timer (atom nil))
(defonce hold-fired? (atom false))
(defonce keyboard-installed? (atom false))
(defonce frame-sync-installed? (atom false))
(defonce last-runtime-error (atom nil))

(defonce app-state
  (r/atom {:phase :loading
           :sidebar-open? false
           :safe-mode? false
           :safe-mode-transition? false
           :sidebar-failed? false
           :sessions []
           :session-id nil
           :saved-runtime-version nil
           :csrf-token nil
           :connection :disconnected
           :messages []
           :checkpoints []
           :progress nil
           :draft ""
           :message nil
           :runtime-error nil}))

(declare create-session!
         handle-server-message!
         load-checkpoints!
         load-session-runtime!
         render-active-surfaces!
         select-session!
         stage-client-runtime!)

(defn- json-headers
  ([] (json-headers nil))
  ([csrf-token]
   (cond-> {"content-type" "application/json"}
     csrf-token (assoc "x-ppp-csrf" csrf-token))))

(defn- fetch-json!
  [url {:keys [method body csrf-token]
        :or {method "GET"}}]
  (-> (js/fetch
       url
       (clj->js
        (cond-> {:method method
                 :credentials "same-origin"
                 :headers (json-headers csrf-token)}
          body (assoc :body (js/JSON.stringify (clj->js body))))))
      (.then
       (fn [response]
         (-> (.json response)
             (.then
              (fn [value]
                (let [data (js->clj value :keywordize-keys true)]
                  (if (.-ok response)
                    data
                    (throw (js/Error.
                            (or (get-in data [:error :message])
                                "The workspace request failed."))))))))))))

(defn- clear-fragment!
  []
  (.replaceState js/history nil ""
                 (str (.-pathname js/location) (.-search js/location))))

(defn- fragment-access-code
  []
  (let [hash (.-hash js/location)
        fragment (subs hash (min 1 (count hash)))
        params (js/URLSearchParams. fragment)]
    (.get params "access")))

(defn- url-session-id
  []
  (.get (js/URLSearchParams. (.-search js/location)) "session"))

(defn- replace-session-in-url!
  [session-id]
  (let [params (js/URLSearchParams. (.-search js/location))]
    (.set params "session" session-id)
    (.replaceState js/history nil ""
                   (str (.-pathname js/location) "?" (.toString params)))))

(defn- selected-value
  [event]
  (.. event -target -value))

(defn- runtime-action-id
  [action-id]
  (if (keyword? action-id)
    (subs (str action-id) 1)
    (str action-id)))

(defn- invoke-action!
  [action-id payload]
  (let [{:keys [session-id csrf-token]} @app-state]
    (if session-id
      (fetch-json!
       (str "/api/sessions/" (js/encodeURIComponent session-id)
            "/actions/" (js/encodeURIComponent (runtime-action-id action-id)))
       {:method "POST"
        :body payload
        :csrf-token csrf-token})
      (js/Promise.reject (js/Error. "No product session is selected.")))))

(defn- scheduled-runtime-error!
  [error]
  (reset! last-runtime-error
          {:message (.-message error)
           :data (ex-data error)
           :stack (.-stack error)})
  (swap! app-state assoc
         :runtime-error
         "An automatic interaction stopped after its generated callback failed. The rest of the product is still running."))

(defn- append-message!
  [role text & [status]]
  (swap! app-state update :messages conj
         {:id (random-uuid) :role role :text text :status status}))

(defn- current-base-version
  []
  (or (frame/active-version)
      (:saved-runtime-version @app-state)))

(defn- submit-draft!
  []
  (let [draft (str/trim (:draft @app-state))
        {:keys [session-id csrf-token]} @app-state
        version (current-base-version)]
    (when-not (str/blank? draft)
      (append-message! :user draft)
      (swap! app-state assoc :draft "" :progress "Generating")
      (if (and session-id (some? version) (transport/connected?))
        (-> (fetch-json!
             (str "/api/sessions/" (js/encodeURIComponent session-id) "/turns")
             {:method "POST"
              :body {:prompt draft
                     :requestTabId (str transport/tab-id)
                     :baseVersion version}
              :csrf-token csrf-token})
            (.catch
             (fn [error]
               (swap! app-state assoc :progress nil)
               (append-message! :assistant (.-message error) "Not applied"))))
        (do
          (swap! app-state assoc :progress nil)
          (append-message!
           :assistant
           "The live connection is still opening. Wait a moment and try again."
           "Not applied"))))))

(defn- load-checkpoints!
  [session-id]
  (when session-id
    (-> (fetch-json!
         (str "/api/sessions/" (js/encodeURIComponent session-id)
              "/checkpoints") {})
        (.then
         (fn [{:keys [checkpoints]}]
           (when (= session-id (:session-id @app-state))
             (swap! app-state assoc :checkpoints (vec checkpoints)))))
        (.catch
         (fn [_error]
           ;; Checkpoints are a recovery aid. A transient list failure must not
           ;; replace or unmount the running product.
           nil)))))

(defn- restore-checkpoint!
  [checkpoint-version]
  (let [{:keys [session-id csrf-token progress]} @app-state
        version (current-base-version)]
    (when (and session-id (nil? progress) (some? version) (transport/connected?))
      (swap! app-state assoc :progress "Validating")
      (-> (fetch-json!
           (str "/api/sessions/" (js/encodeURIComponent session-id) "/restores")
           {:method "POST"
            :body {:checkpointVersion checkpoint-version
                   :requestTabId (str transport/tab-id)
                   :baseVersion version}
            :csrf-token csrf-token})
          (.catch
           (fn [error]
             (swap! app-state assoc :progress nil)
             (append-message! :assistant (.-message error) "Not restored")))))))

(defn- frame-sidebar-model
  []
  (let [{:keys [sessions session-id messages checkpoints draft progress]} @app-state]
    {:sessions sessions
     :session-id session-id
     :messages messages
     :checkpoints checkpoints
     :draft draft
     :busy? (some? progress)
     :progress progress}))

(defn- handle-frame-sidebar-event!
  [event value]
  (case event
    :select-session (select-session! value)
    :new-session (create-session!)
    :restore (restore-checkpoint! value)
    :draft-change (swap! app-state assoc :draft (str value))
    :send (submit-draft!)
    nil))

(defn- fallback-sidebar
  []
  (let [{:keys [sessions session-id messages checkpoints draft progress runtime-error]}
        @app-state]
    [:aside.ppp-fallback-sidebar {:aria-label "Product conversation"}
     [:header.ppp-fallback-header
      [:select
       {:aria-label "Current session"
        :value (or session-id "")
        :on-change #(select-session! (selected-value %))}
       (for [{:keys [id title]} sessions]
         ^{:key id} [:option {:value id} title])]
      [:button {:type "button"
                :aria-label "New session"
                :on-click create-session!}
       "+"]]
     [:section.ppp-fallback-conversation {:aria-live "polite"}
      (cond
        runtime-error
        [:div.ppp-fallback-notice
         [:strong "Your current product is unchanged."]
         [:p runtime-error]
         [:button {:type "button"
                   :on-click #(load-session-runtime! session-id)}
          "Try again"]]

        (seq messages)
        (for [{:keys [id role text status]} messages]
          ^{:key id}
          [:article {:class (str "ppp-fallback-message ppp-fallback-message-"
                                 (name role))}
           [:p text]
           (when status [:small status])])

        :else
        [:div.ppp-fallback-empty
         [:strong "Start with the outcome."]
         [:p "Describe a product, a rule, or a visual change."]])
      (when progress [:p.ppp-fallback-progress progress])]
     (when (seq checkpoints)
       [:section.ppp-fallback-checkpoints {:aria-label "Checkpoints"}
        [:strong "Checkpoints"]
        (for [{:keys [runtime-version title]} (reverse checkpoints)]
          ^{:key runtime-version}
          [:button {:type "button"
                    :disabled (some? progress)
                    :on-click #(restore-checkpoint! runtime-version)}
           title])])
     [:form.ppp-fallback-composer
      {:on-submit (fn [event]
                    (.preventDefault event)
                    (submit-draft!))}
      [:textarea {:aria-label "Message"
                  :placeholder "What should this product do?"
                  :value draft
                  :disabled (some? progress)
                  :on-key-down #(composer/handle-key-down!
                                 % (some? progress) draft submit-draft!)
                  :on-change #(swap! app-state assoc :draft (selected-value %))}]
      [:button {:type "submit"
                :disabled (or (some? progress) (str/blank? draft))}
       (if progress "Working" "Send")]]]))

(defn- set-safe-mode!
  [enabled?]
  (let [{current? :safe-mode?
         transition? :safe-mode-transition?} @app-state]
    (when (and (not= enabled? current?) (not transition?))
      (let [recycle? (some? @frame/active-runtime)]
        (swap! app-state assoc
               :safe-mode? enabled?
               :safe-mode-transition? recycle?
               :sidebar-open? (or enabled? (:sidebar-open? @app-state)))
        (when recycle?
          (-> (frame/recycle-active! (not enabled?))
              (.then
               (fn [_]
                 (swap! app-state assoc :safe-mode-transition? false)
                 (frame/update-sidebar!)
                 (render-active-surfaces!)))
              (.catch
               (fn [error]
                 ;; Safe Mode is the immutable recovery boundary. If its
                 ;; isolated replacement cannot start, fail closed by removing
                 ;; every generated frame instead of retaining its side effects.
                 (reset! last-runtime-error
                         {:message (.-message error)
                          :data (ex-data error)
                          :stack (.-stack error)})
                 (frame/reset-runtime!)
                 (swap! app-state assoc
                        :safe-mode? true
                        :safe-mode-transition? false
                        :sidebar-open? true
                        :runtime-error
                        "Safe Mode stopped the programmable product because its isolated recovery view could not start.")
                 (render-active-surfaces!))))))))
  enabled?)

(defn- safe-mode-sidebar
  []
  (let [{:keys [sessions session-id checkpoints progress runtime-error
                safe-mode-transition?]} @app-state]
    [:aside.ppp-safe-sidebar {:aria-label "Safe Mode"}
     [:header.ppp-safe-header
      [:div
       [:strong "Safe Mode"]
       [:p "The programmable conversation panel is paused."]]
      [:button {:type "button"
                :disabled safe-mode-transition?
                :on-click #(set-safe-mode! false)}
       (if safe-mode-transition? "Securing product" "Exit Safe Mode")]]
     [:section.ppp-safe-content {:aria-live "polite"}
      [:label {:for "ppp-safe-session"} "Current session"]
      [:select#ppp-safe-session
       {:value (or session-id "")
        :on-change #(let [selected (selected-value %)]
                      (set-safe-mode! false)
                      (select-session! selected))}
       (for [{:keys [id title]} sessions]
         ^{:key id} [:option {:value id} title])]
      (when runtime-error [:p {:role "alert"} runtime-error])
      (when (seq checkpoints)
        [:div.ppp-safe-checkpoints {:aria-label "Checkpoints"}
         [:strong "Restore a checkpoint"]
         (for [{:keys [runtime-version title]} (reverse checkpoints)]
           ^{:key runtime-version}
           [:button {:type "button"
                     :disabled (some? progress)
                     :on-click #(restore-checkpoint! runtime-version)}
            title])])
      [:div.ppp-safe-actions
       [:button {:type "button"
                 :on-click #(do
                              (set-safe-mode! false)
                              (load-session-runtime! session-id))}
        "Try again"]
       [:button {:type "button"
                 :on-click #(do
                              (set-safe-mode! false)
                              (create-session!))}
        "New session"]]]]))

(defn render-active-surfaces!
  []
  ;; The authenticated parent owns only recovery UI. Generated surfaces render
  ;; in an opaque-origin frame and are synchronized over the frame bridge.
  (r/flush))

(defn stage-client-runtime!
  [version source-map]
  (-> (frame/stage! version source-map
                    {:sidebar-enabled? (not (:safe-mode? @app-state))})
      (.catch
       (fn [error]
         (reset! last-runtime-error
                 {:message (.-message error)
                  :data (ex-data error)
                  :stack (.-stack error)})
         (throw error)))))

(defn- activate-client-runtime!
  [version]
  (let [client-runtime (frame/activate! version)]
    ;; HTTP bootstrap and WebSocket resync may race and intentionally cancel
    ;; an older staged frame. A later successful activation is authoritative;
    ;; it must clear that obsolete cancellation diagnostic.
    (reset! last-runtime-error nil)
    (swap! app-state assoc :runtime-error nil :sidebar-failed? false)
    (frame/update-sidebar!)
    (render-active-surfaces!)
    client-runtime))

(defn- runtime-source-map
  [files]
  (into {} (map (juxt :path :content)) files))

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
  (let [details (:details (ex-data error))]
    (->> details
         (filter string?)
         (map #(subs % 0 (min 240 (count %))))
         (take 4)
         vec)))

(defn- runtime-load-failure-message
  [error]
  (let [code (runtime-error-code error)
        detail (last (runtime-error-details error))]
    (case code
      :runtime/client-frame-load-timeout
      "The product view did not finish loading within 30 seconds. Your saved session is unchanged."

      :runtime/client-frame-render-timeout
      "The product view loaded, but it did not finish drawing within 10 seconds. Your saved session is unchanged."

      :runtime/client-registration-contract
      "The saved product view is incomplete because it did not provide both the canvas and conversation panel. Your saved session is unchanged."

      :runtime/client-stage-failed
      (str "The saved product view could not start."
           (when detail (str " Reason: " detail "."))
           " Your saved session is unchanged.")

      (str "The product view failed during "
           (name (or (:phase (ex-data error)) :startup))
           "."
           (when detail (str " Reason: " detail "."))
           " Your saved session is unchanged."))))

(def progress-labels
  {:generating "Generating"
   :validating "Validating"
   :applying "Applying"
   :applied "Applied"})

(defn- stage-from-message!
  [{:keys [session-id request-id payload]}]
  (let [{:keys [tab-id transaction-id base-version target-version files]} payload
        current-session (:session-id @app-state)
        known-version (current-base-version)
        response {:session-id session-id
                  :request-id request-id
                  :transaction-id transaction-id
                  :base-version base-version
                  :target-version target-version}]
    (if-not (and (= (str session-id) current-session)
                 (= tab-id transport/tab-id)
                 (= base-version known-version)
                 (= target-version (inc base-version)))
      (transport/send-stage-result!
       (assoc response :status :rejected :code :runtime/stale-browser-version))
      (-> (stage-client-runtime! target-version (runtime-source-map files))
          (.then
           (fn [_]
             (transport/send-stage-result! (assoc response :status :staged))))
          (.catch
           (fn [error]
             (let [details (runtime-error-details error)]
               (transport/send-stage-result!
                (assoc response
                       :status :rejected
                       :code (or (runtime-error-code error)
                                 :runtime/client-rejected)
                       :details details)))))))))

(defn- activate-from-message!
  [{:keys [session-id runtime-version]}]
  (when (= (str session-id) (:session-id @app-state))
    (try
      (activate-client-runtime! runtime-version)
      (swap! app-state assoc :saved-runtime-version runtime-version)
      (transport/subscribe!)
      (catch :default error
        (reset! last-runtime-error
                {:message (.-message error)
                 :data (ex-data error)
                 :stack (.-stack error)})
        (swap! app-state assoc
               :runtime-error
               "The saved product changed, but this tab needs to resync its view.")
        (load-session-runtime! (:session-id @app-state))))))

(defn- resync-from-message!
  [{:keys [session-id runtime-version payload]}]
  (when (= (str session-id) (:session-id @app-state))
    (swap! load-sequence inc)
    (frame/reset-runtime!)
    (swap! app-state assoc :saved-runtime-version runtime-version)
    (-> (stage-client-runtime! runtime-version
                               (runtime-source-map (:files payload)))
        (.then (fn [_]
                 (activate-client-runtime! runtime-version)
                 (transport/subscribe!)))
        (.catch
         (fn [error]
           (reset! last-runtime-error
                   {:message (.-message error)
                    :data (ex-data error)
                    :stack (.-stack error)})
           (swap! app-state assoc
                  :runtime-error
                  "The current product view could not be synchronized.")
           (transport/subscribe!))))))

(defn handle-server-message!
  [{:keys [type payload runtime-version] :as message}]
  (case type
    :turn/queued
    (swap! app-state assoc :progress "Generating")

    :turn/progress
    (swap! app-state assoc :progress (get progress-labels (:phase payload) "Working"))

    :runtime/stage
    (stage-from-message! message)

    :runtime/activate
    (activate-from-message! message)

    :runtime/resync
    (resync-from-message! message)

    :turn/completed
    (do
      (swap! app-state assoc :progress nil)
      (append-message!
       :assistant
       (if (= :clarify (:kind payload))
         (:clarification-question payload)
         (:assistant-message payload))
       (when (= :change (:kind payload))
         (str "Checkpoint " runtime-version)))
      (load-checkpoints! (:session-id @app-state)))

    :turn/failed
    (do
      (swap! app-state assoc :progress nil)
      (append-message! :assistant (:message payload) "Not applied")
      (when (= :runtime/stale-browser-version (:code payload))
        (load-session-runtime! (:session-id @app-state))))

    nil))

(defn load-session-runtime!
  [session-id]
  (when session-id
    (let [request-number (swap! load-sequence inc)]
      (frame/reset-runtime!)
      (swap! app-state assoc :runtime-error nil :sidebar-failed? false
             :checkpoints [])
      (load-checkpoints! session-id)
      (render-active-surfaces!)
      (-> (fetch-json!
           (str "/api/sessions/" (js/encodeURIComponent session-id) "/runtime") {})
          (.then
           (fn [{:keys [runtime-version files]}]
             (when (and (= request-number @load-sequence)
                        (= session-id (:session-id @app-state)))
               (swap! app-state assoc :saved-runtime-version runtime-version)
               (stage-client-runtime!
                runtime-version
                (into {} (map (juxt :path :content)) files)))))
          (.then
           (fn [client-runtime]
             (when client-runtime
               (if (and (= request-number @load-sequence)
                        (= session-id (:session-id @app-state)))
                 (activate-client-runtime! (:version client-runtime))
                 (frame/discard-stage! (:version client-runtime))))
             (transport/subscribe!)))
          (.catch
           (fn [error]
             (reset! last-runtime-error
                     {:message (.-message error)
                      :data (ex-data error)
                      :stack (.-stack error)})
             (when (and (= request-number @load-sequence)
                        (= session-id (:session-id @app-state)))
               (swap! app-state assoc
                      :runtime-error
                      (runtime-load-failure-message error)))
             ;; A broken generated view must still be repairable from the
             ;; immutable recovery sidebar. Subscribe at the saved server
             ;; version even though no client runtime could be activated.
             (transport/subscribe!)))))))

(defn select-session!
  [session-id]
  (when (and session-id (not (str/blank? session-id)))
    (replace-session-in-url! session-id)
    (swap! app-state assoc
           :session-id session-id
           :saved-runtime-version nil
           :runtime-error nil)
    (load-session-runtime! session-id)
    (transport/subscribe!)))

(defn- request-session!
  []
  (fetch-json! "/api/sessions"
               {:method "POST"
                :body {}
                :csrf-token (:csrf-token @app-state)}))

(defn- add-session!
  [session]
  (swap! app-state
         (fn [state]
           (-> state
               (update :sessions conj session)
               (assoc :phase :ready :message nil))))
  (select-session! (:id session))
  session)

(defn- accept-bootstrap!
  [{:keys [csrf-token sessions]}]
  (let [sessions (vec sessions)
        available (set (map :id sessions))
        preferred (or (:session-id @app-state) (url-session-id))
        selected (if (contains? available preferred)
                   preferred
                   (:id (first sessions)))]
    (swap! app-state assoc
           :phase (if (empty? sessions) :loading :ready)
           :csrf-token csrf-token
           :sessions sessions
           :session-id selected
           :message nil)
    (transport/connect!
     {:session-fn #(:session-id @app-state)
      :version-fn current-base-version
      :on-message handle-server-message!
      :on-status #(swap! app-state assoc :connection %)})
    (if selected
      (select-session! selected)
      (-> (request-session!)
          (.then add-session!)))))

(defn bootstrap!
  []
  (-> (fetch-json! "/api/bootstrap" {})
      (.then accept-bootstrap!)
      (.catch
       (fn [error]
         (swap! app-state assoc
                :phase :access-required
                :message (.-message error))))))

(defn- exchange-access!
  [code]
  (clear-fragment!)
  (-> (fetch-json! "/api/access" {:method "POST" :body {:code code}})
      (.then (fn [_] (bootstrap!)))
      (.catch
       (fn [error]
         (swap! app-state assoc
                :phase :access-required
                :message (.-message error))))))

(defn create-session!
  []
  (-> (request-session!)
      (.then add-session!)
      (.catch
       (fn [error]
         (swap! app-state assoc :runtime-error (.-message error))))))

(defn- cancel-hold!
  []
  (when @hold-timer
    (js/clearTimeout @hold-timer)
    (reset! hold-timer nil)))

(defn- start-hold!
  []
  (cancel-hold!)
  (reset! hold-fired? false)
  (reset! hold-timer
          (js/setTimeout
           (fn []
             (reset! hold-fired? true)
             (reset! hold-timer nil)
             (set-safe-mode! true))
           700)))

(defn- handle-click!
  []
  (cancel-hold!)
  (if @hold-fired?
    (reset! hold-fired? false)
    (if (:safe-mode? @app-state)
      (set-safe-mode! false)
      (swap! app-state update :sidebar-open? not))))

(defn- shortcut!
  [event]
  (when (and (.-ctrlKey event)
             (.-altKey event)
             (.-shiftKey event)
             (= "p" (str/lower-case (.-key event))))
    (.preventDefault event)
    (set-safe-mode! true)))

(defn- install-keyboard-shortcut!
  []
  (when (compare-and-set! keyboard-installed? false true)
    (.addEventListener js/window "keydown" shortcut!)))

(defn- install-frame-sync!
  []
  (when (compare-and-set! frame-sync-installed? false true)
    (add-watch app-state ::frame-sync
               (fn [_ _ _ _]
                 (frame/update-sidebar!)))))

(defn host-shell
  []
  (let [{:keys [phase sidebar-open? safe-mode? runtime-error message]} @app-state
        ready? (= :ready phase)
        recovery? (or safe-mode? runtime-error (nil? @frame/active-runtime))]
    [:main.ppp-host
     {:aria-label "Programmable product workspace"}
     [:div.ppp-frame-host
      {:aria-label "Product canvas"
       :ref frame/register-host!}]
     (when (and ready? sidebar-open? recovery?)
       [:div.ppp-recovery-host
        (if safe-mode?
          [safe-mode-sidebar]
          [fallback-sidebar])])
     (when ready?
       [:button.ppp-handle
        {:type "button"
         :aria-label (cond
                       safe-mode? "Close Safe Mode"
                       sidebar-open? "Close product conversation"
                       :else "Open product conversation")
         :aria-expanded sidebar-open?
         :title "Open conversation. Hold for Safe Mode."
         :on-pointer-down start-hold!
         :on-pointer-up cancel-hold!
         :on-pointer-cancel cancel-hold!
         :on-pointer-leave cancel-hold!
         :on-click handle-click!}])
     (when (and message (not ready?))
       [:p.ppp-host-message
        {:role "status"
         :data-tone (if (= :access-required phase) "error" "neutral")}
        message])]))

(defn- local-test-runtime?
  []
  (and (contains? #{"localhost" "127.0.0.1" "[::1]"} (.-hostname js/location))
       (= "1" (.get (js/URLSearchParams. (.-search js/location)) "test-runtime"))))

(defn- install-test-hook!
  []
  (when (local-test-runtime?)
    (let [api
          #js {:stage (fn [version source]
                        (stage-client-runtime! version (js->clj source)))
               :activate activate-client-runtime!
               :openSidebar #(swap! app-state assoc :sidebar-open? true)
               :safeMode set-safe-mode!
               :simulateRuntimeFailure
               (fn []
                 (frame/reset-runtime!)
                 (swap! app-state assoc
                        :runtime-error
                        "The product view could not be loaded. Your saved session is unchanged.")
                 (render-active-surfaces!)
                 (transport/subscribe!))
               :setState (fn [value]
                           (frame/set-state!
                            (merge @(frame/active-page-state)
                                   (js->clj value :keywordize-keys true))))
               :submitPrompt (fn [value]
                               (swap! app-state assoc :draft (str value))
                               (submit-draft!))
               :snapshot (fn []
                           (clj->js
                            {:version (frame/active-version)
                             :saved-version (:saved-runtime-version @app-state)
                             :state @(frame/active-page-state)
                             :session-id (:session-id @app-state)
                             :messages (:messages @app-state)
                             :progress (:progress @app-state)
                             :connection (:connection @app-state)
                             :safe-mode (:safe-mode? @app-state)
                             :sidebar-open (:sidebar-open? @app-state)
                             :debug-error @last-runtime-error
                             :transport @transport/diagnostics}))}]
      (js/Object.defineProperty
       js/window "__PPP_TEST__"
       #js {:value api :configurable true :enumerable false :writable false}))))

(defn ^:export init!
  []
  (when-let [container (.getElementById js/document "ppp-host")]
    (let [react-root (or @root (rdom/create-root container))]
      (reset! root react-root)
      (install-keyboard-shortcut!)
      (frame/configure!
       {:action-fn invoke-action!
        :sidebar-event-fn handle-frame-sidebar-event!
        :safe-mode-fn #(set-safe-mode! true)
        :runtime-error-fn scheduled-runtime-error!
        :sidebar-model-fn frame-sidebar-model
        :sidebar-open-fn #(and (:sidebar-open? @app-state)
                               (not (:safe-mode? @app-state)))
        :session-id-fn #(:session-id @app-state)})
      (frame/install-listener!)
      (install-frame-sync!)
      (rdom/render react-root [host-shell])
      (install-test-hook!)
      (if-let [code (fragment-access-code)]
        (exchange-access! code)
        (bootstrap!)))))
