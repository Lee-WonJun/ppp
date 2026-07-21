(ns ppp.client.runtime
  (:require [clojure.string :as str]
            [ppp.runtime.policy :as policy]
            [reagent.core :as r]
            [sci.core :as sci]))

(defrecord ClientRuntime
           [version context page sidebar css state state-key initial-state
            phase pending ensured timers event-handlers registrations budget
            runtime-error-fn])

(defonce state-root (r/atom {:base {}}))
(defonce base-state (r/cursor state-root [:base]))
(defonce active-runtime (r/atom nil))
(defonce staged-runtimes (atom {}))

(def ^:private execution-limit 100000)
(def ^:private rendered-node-limit 10000)
(def ^:private rendered-depth-limit 64)
(def ^:private minimum-interval-ms 50)
(def ^:private maximum-interval-ms 60000)
(def ^:private maximum-intervals 8)

(defn- normalized-attribute-name
  [attribute]
  (-> (name attribute)
      str/lower-case
      (str/replace #"[-_:]" "")))

(defn- element-name
  [tag]
  (some-> (name tag) (str/split #"[.#]" 2) first))

(defn- new-execution-budget
  []
  {:operations (atom 0)
   :nodes (atom 0)})

(defn- reset-execution-budget!
  [{:keys [operations nodes]}]
  (reset! operations 0)
  (reset! nodes 0))

(defn- tick-operation!
  [{:keys [operations]}]
  (when (> (swap! operations inc) execution-limit)
    (throw (ex-info "Generated client code exceeded its execution budget"
                    {:code :runtime/client-timeout
                     :limit execution-limit}))))

(defn- tick-node!
  [{:keys [nodes]} depth]
  (when (> depth rendered-depth-limit)
    (throw (ex-info "Generated client view is nested too deeply"
                    {:code :runtime/client-view-depth
                     :limit rendered-depth-limit})))
  (when (> (swap! nodes inc) rendered-node-limit)
    (throw (ex-info "Generated client view is too large"
                    {:code :runtime/client-view-size
                     :limit rendered-node-limit}))))

(declare sanitize-view)

(defn- sanitized-handler
  [budget handler]
  (fn [& args]
    (reset-execution-budget! budget)
    (apply handler args)))

(defn- sanitize-attributes
  [budget attributes]
  (when (> (count attributes) 128)
    (throw (ex-info "Generated element has too many attributes"
                    {:code :runtime/client-view-attributes})))
  (into
   (empty attributes)
   (map
    (fn [[attribute value]]
      (let [attribute-name (normalized-attribute-name attribute)]
        [attribute
         (if (and (str/starts-with? attribute-name "on") (ifn? value))
           (sanitized-handler budget value)
           value)])))
   attributes))

(defn- sanitize-sequential-view
  [budget values depth]
  (let [values (vec (take (inc rendered-node-limit) values))]
    (when (> (count values) rendered-node-limit)
      (throw (ex-info "Generated client view is too large"
                      {:code :runtime/client-view-size
                       :limit rendered-node-limit})))
    (doall (map #(sanitize-view budget % depth) values))))

(defn sanitize-view
  ([budget view]
   (sanitize-view budget view 0))
  ([budget view depth]
   (tick-node! budget depth)
   (cond
     (or (nil? view) (string? view) (number? view) (keyword? view) (boolean? view))
     view

     (vector? view)
     (let [tag (first view)
           tail (subvec view 1)
           sanitized
           (cond
             (= :<> tag)
             (into [:<>] (map #(sanitize-view budget % (inc depth))) tail)

             (keyword? tag)
             (let [base (element-name tag)
                   _ (when (str/blank? base)
                       (throw (ex-info "Generated client view uses an invalid element"
                                       {:code :runtime/client-view-element})))
                   attributes? (map? (first tail))
                   attributes (when attributes?
                                (sanitize-attributes budget (first tail)))
                   children (if attributes? (subvec tail 1) tail)]
               (cond-> [tag]
                 attributes? (conj attributes)
                 true (into (map #(sanitize-view budget % (inc depth)) children))))

             (ifn? tag)
             (sanitize-view budget (apply tag tail) (inc depth))

             :else
             (throw (ex-info "Generated client view has an invalid component"
                             {:code :runtime/client-view-component})))]
       (if (and (meta view) (or (vector? sanitized) (seq? sanitized)))
         (with-meta sanitized (meta view))
         sanitized))

     (sequential? view)
     (sanitize-sequential-view budget view (inc depth))

     :else
     (throw (ex-info "Generated client view contains an unsupported value"
                     {:code :runtime/client-view-value
                      :value-type (str (type view))})))))

(defn- safe-component
  [budget component]
  (fn [& args]
    (reset-execution-budget! budget)
    (sanitize-view budget (apply component args))))

(defn- string-api
  []
  {'blank? str/blank?
   'trim str/trim
   'triml str/triml
   'trimr str/trimr
   'lower-case str/lower-case
   'upper-case str/upper-case
   'includes? str/includes?
   'starts-with? str/starts-with?
   'ends-with? str/ends-with?
   'split str/split
   'split-lines str/split-lines
   'join str/join
   'replace str/replace})

(def ^:private javascript-global-name
  #"^[A-Za-z_$][A-Za-z0-9_$]*$")

(defn- browser-global-names
  []
  ;; Window exposes part of the web platform through its prototype chain.
  ;; Looking only at own properties silently drops normal CLJS targets such
  ;; as js/parent in Chromium.
  (loop [object js/globalThis
         names #{}]
    (if object
      (recur (.getPrototypeOf js/Object object)
             (into names (array-seq (.getOwnPropertyNames js/Object object))))
      names)))

(defn- browser-classes
  []
  ;; The security boundary is the opaque-origin iframe, not an ever-growing
  ;; list of blessed browser features. Mirror the frame's own JS globals into
  ;; SCI so generated products can use the platform that is actually present
  ;; (DOM, timers, Canvas/WebGL, workers, WebAssembly, observers, and future
  ;; browser APIs) without adding one host capability per feature.
  (reduce
   (fn [classes global-name]
     (if (re-matches javascript-global-name global-name)
       (try
         (let [value (aget js/globalThis global-name)]
           (if (nil? value)
             classes
             (assoc classes (symbol global-name) {:class value})))
         (catch :default _
           ;; Opaque origins deliberately make a few Window getters, such as
           ;; localStorage, throw. Those globals remain unavailable.
           classes))
       classes))
   {'js {:class js/globalThis}
    'Error {:class js/Error}
    'globalThis {:class js/globalThis}}
   (browser-global-names)))

(defn active-page-state
  []
  (or (:state @active-runtime) base-state))

(defn active-version
  []
  (some-> @active-runtime :version))

(defn- new-state-slot!
  []
  (let [state-key (random-uuid)
        current-state @(active-page-state)]
    (swap! state-root assoc state-key current-state)
    {:state-key state-key
     :state (r/cursor state-root [state-key])}))

(defn- remove-state-slot!
  [state-key]
  (swap! state-root dissoc state-key))

(defn- ensure-phase!
  [phase expected capability]
  (let [actual @phase
        allowed? (if (set? expected)
                   (contains? expected actual)
                   (= expected actual))]
    (when-not allowed?
      (throw (ex-info "Client capability is unavailable in this phase"
                      {:code :runtime/client-capability-phase
                       :capability capability
                       :expected expected})))))

(defn- stop-interval-entry!
  [timers timer-id]
  (when-let [handle (get @timers timer-id)]
    (js/clearInterval handle)
    (swap! timers dissoc timer-id))
  timer-id)

(defn- clear-intervals!
  [timers]
  (doseq [[timer-id _handle] @timers]
    (stop-interval-entry! timers timer-id))
  nil)

(defn- validate-interval!
  [timer-id interval-ms callback]
  (when-not (keyword? timer-id)
    (throw (ex-info "Generated interval id must be a keyword"
                    {:code :runtime/client-interval-id})))
  (when-not (and (int? interval-ms)
                 (<= minimum-interval-ms interval-ms maximum-interval-ms))
    (throw (ex-info "Generated interval is outside the allowed range"
                    {:code :runtime/client-interval-range
                     :minimum-ms minimum-interval-ms
                     :maximum-ms maximum-interval-ms})))
  (when-not (ifn? callback)
    (throw (ex-info "Generated interval callback must be callable"
                    {:code :runtime/client-interval-callback}))))

(defn- action-promise!
  [{:keys [phase state pending action-fn]} action-id payload target-key]
  (ensure-phase! phase :active :action!)
  (swap! pending conj target-key)
  (-> (js/Promise.resolve (action-fn action-id payload))
      (.then (fn [value]
               (when target-key
                 (swap! state
                        (fn [current]
                          (-> current
                              (assoc target-key
                                     (if (and (map? value)
                                              (contains? value :result))
                                       (:result value)
                                       value))
                              (update :runtime/action-errors dissoc target-key)))))
               value))
      (.catch
       (fn [error]
         (if target-key
           (let [data (ex-data error)
                 message (or (.-message error)
                             (:message data)
                             "The request could not be completed.")
                 failure {:error (subs (str message) 0 (min 1024 (count (str message))))
                          :code (or (:code data) :action/failed)}]
             ;; A target key is an explicit request to make the server result
             ;; observable in generated UI. Preserve that contract for both
             ;; success and failure so a rejected HTTP action cannot leave a
             ;; permanent optimistic or loading state behind.
             (swap! state
                    (fn [current]
                      (cond-> (assoc-in current
                                        [:runtime/action-errors target-key]
                                        failure)
                        (nil? (get current target-key))
                        (assoc target-key failure))))
             (throw error))
           (throw error))))
      (.finally (fn [] (swap! pending disj target-key)))))

(defn- runtime-api
  [{:keys [registrations phase state initial-state pending ensured timers budget
           runtime-error-fn]
    :as options}]
  {'register-page!
   (fn [page-id component]
     (ensure-phase! phase #{:evaluating :repl} :register-page!)
     (when-not (and (keyword? page-id) (ifn? component))
       (throw (ex-info "Invalid generated page registration"
                       {:code :runtime/client-invalid-page})))
     (when (and (= :evaluating @phase)
                (contains? (:pages @registrations) page-id))
       (throw (ex-info "Generated page is registered more than once"
                       {:code :runtime/client-duplicate-page :page-id page-id})))
     (swap! registrations assoc-in [:pages page-id] (safe-component budget component))
     page-id)

   'register-sidebar!
   (fn [component]
     (ensure-phase! phase #{:evaluating :repl} :register-sidebar!)
     (when-not (ifn? component)
       (throw (ex-info "Invalid generated sidebar registration"
                       {:code :runtime/client-invalid-sidebar})))
     (when (and (= :evaluating @phase) (:sidebar @registrations))
       (throw (ex-info "Generated sidebar is registered more than once"
                       {:code :runtime/client-duplicate-sidebar})))
     (swap! registrations assoc :sidebar (safe-component budget component))
     :sidebar)

   'navigate!
   (fn [route]
     (ensure-phase! phase :active :navigate!)
     (swap! state assoc :route route)
     route)

   'initialize-state!
   (fn [defaults]
     (ensure-phase! phase #{:evaluating :repl} :initialize-state!)
     (when-not (and (map? defaults)
                    (<= (count defaults) 256)
                    (<= (count (pr-str defaults)) 65536))
       (throw (ex-info "Generated initial state is outside the supported bounds"
                       {:code :runtime/client-initial-state-invalid})))
     (swap! initial-state merge defaults)
     (swap! state #(merge defaults %))
     nil)

   'register-event-handler!
   (fn [topic handler]
     (ensure-phase! phase #{:evaluating :repl} :register-event-handler!)
     (when-not (and (keyword? topic) (<= (count (str topic)) 96) (ifn? handler))
       (throw (ex-info "Invalid generated product event registration"
                       {:code :runtime/client-invalid-event-handler})))
     (when (and (= :evaluating @phase)
                (contains? (:events @registrations) topic))
       (throw (ex-info "Generated product event is registered more than once"
                       {:code :runtime/client-duplicate-event-handler
                        :topic topic})))
     (when (>= (count (:events @registrations)) 64)
       (throw (ex-info "Generated runtime registered too many product events"
                       {:code :runtime/client-event-handler-limit})))
     (swap! registrations assoc-in [:events topic] handler)
     topic)

   'action!
   (fn
     ([action-id payload]
      (action-promise! options action-id payload nil))
     ([action-id payload target-key]
      (action-promise! options action-id payload target-key)))

   'ensure-action!
   (fn [action-id payload target-key]
     (when (and (= :active @phase)
                ;; Action results are derived from the active server runtime.
                ;; Preserve them while staging, but refresh each key once when
                ;; a new client/server version becomes active.
                (not (contains? @ensured target-key))
                (not (contains? @pending target-key)))
       (swap! ensured conj target-key)
       (action-promise! options action-id payload target-key)))

   'start-interval!
   (fn [timer-id interval-ms callback]
     (validate-interval! timer-id interval-ms callback)
     (let [current-phase @phase]
       (if (contains? #{:evaluating :staging :active} current-phase)
         (when-not (contains? @timers timer-id)
           (when (>= (count @timers) maximum-intervals)
             (throw (ex-info "Generated runtime has too many intervals"
                             {:code :runtime/client-interval-limit
                              :limit maximum-intervals})))
           (let [handle
                 (js/setInterval
                  (fn []
                    (when (= :active @phase)
                      (try
                        (reset-execution-budget! budget)
                        (callback)
                        (catch :default error
                          (stop-interval-entry! timers timer-id)
                          (runtime-error-fn error)))))
                  interval-ms)]
             (swap! timers assoc timer-id handle)))
         (throw (ex-info "Generated interval is unavailable for this runtime"
                         {:code :runtime/client-capability-phase
                          :capability :start-interval!
                          :phase current-phase}))))
     timer-id)

   'stop-interval!
   (fn [timer-id]
     (ensure-phase! phase :active :stop-interval!)
     (stop-interval-entry! timers timer-id))

   'page-state state

   'event-value
   (fn [event]
     (.. event -target -value))

   'prevent-default!
   (fn [event]
     (.preventDefault event))})

(defn capability-inventory
  []
  {:runtime.api
   (vec (sort (map name (get-in policy/capability-catalog
                                [:client :namespaces 'runtime.api]))))
   :clojure.string
   (vec (sort (map name (keys (string-api)))))
   :denied
   (vec (sort (map str policy/client-denied-symbols)))})

(defn- assert-capability-catalog!
  [api string-namespace]
  (let [expected-api (set (get-in policy/capability-catalog
                                  [:client :namespaces 'runtime.api]))
        expected-string (set (get-in policy/capability-catalog
                                     [:client :namespaces 'clojure.string]))]
    (when-not (and (= expected-api (set (keys api)))
                   (= expected-string (set (keys string-namespace))))
      (throw (ex-info "Client capability implementation drifted from its catalog"
                      {:code :kernel/client-capability-drift}))))
  true)

(defn- source-order
  [path]
  (cond
    (str/starts-with? path "src/shared/") [0 path]
    (= path "src/client/runtime/sidebar.cljs") [2 path]
    (str/starts-with? path "src/client/") [1 path]
    :else [3 path]))

(defn- selected-source
  [source-map sidebar-enabled?]
  (->> source-map
       (filter (fn [[path _]]
                 (and (or (str/starts-with? path "src/shared/")
                          (str/starts-with? path "src/client/"))
                      (or sidebar-enabled?
                          (not= path "src/client/runtime/sidebar.cljs")))))
       (sort-by (comp source-order first))))

(defn- css-text
  [source-map]
  (->> source-map
       (filter (fn [[path _]] (str/starts-with? path "styles/")))
       (sort-by first)
       (map second)
       (str/join "\n")))

(defn- exception-code
  [cause]
  (loop [current cause
         depth 0]
    (when (and current (< depth 16))
      (or (:code (ex-data current))
          (recur (ex-cause current) (inc depth))))))

(defn stage-source!
  [{:keys [version source-map action-fn runtime-error-fn sidebar-enabled?]
    :or {action-fn (fn [_action-id _payload]
                     (js/Promise.reject
                      (js/Error. "Action transport is unavailable.")))
         runtime-error-fn (fn [_error] nil)
         sidebar-enabled? true}}]
  (when-not (and (int? version) (not (neg? version)))
    (throw (ex-info "Client runtime version must be a natural integer"
                    {:code :runtime/client-invalid-version})))
  (doseq [path ["src/shared/runtime/domain.cljc"
                "src/client/runtime/client.cljs"
                "src/client/runtime/sidebar.cljs"]]
    (when-not (string? (get source-map path))
      (throw (ex-info "Required client runtime source is missing"
                      {:code :runtime/client-missing-entrypoint :path path}))))
  (let [{:keys [state-key state]} (new-state-slot!)
        initial-state (atom {})
        registrations (atom {:pages {} :sidebar nil :events {}})
        phase (atom :evaluating)
        pending (r/atom #{})
        ensured (atom #{})
        timers (atom {})
        budget (new-execution-budget)
        api (runtime-api {:registrations registrations
                          :phase phase
                          :state state
                          :initial-state initial-state
                          :pending pending
                          :ensured ensured
                          :timers timers
                          :budget budget
                          :action-fn action-fn
                          :runtime-error-fn runtime-error-fn})
        string-namespace (string-api)]
    (try
      (assert-capability-catalog! api string-namespace)
      (let [context
            (sci/init {:namespaces {'runtime.api api
                                    'clojure.string string-namespace}
                       :classes (browser-classes)
                       :unrestricted true
                       :deny policy/client-denied-symbols
                       :interrupt-fn #(tick-operation! budget)
                       :features #{:cljs}})]
        (doseq [[path source] (selected-source source-map sidebar-enabled?)]
          (reset-execution-budget! budget)
          (sci/eval-string+ context source {:file path}))
        (when-let [error (policy/validate-runtime-css (css-text source-map))]
          (throw (ex-info "Generated CSS can load an external resource" error)))
        (let [{:keys [pages sidebar events]} @registrations
              sidebar (if sidebar-enabled? sidebar (fn [_] nil))]
          (when-not (and (= #{:home} (set (keys pages)))
                         (or (not sidebar-enabled?) (ifn? sidebar)))
            (throw (ex-info "Client runtime must register one home page and one sidebar"
                            {:code :runtime/client-registration-contract
                             :pages (vec (keys pages))
                             :sidebar? (boolean sidebar)})))
          (reset! phase :staging)
          (->ClientRuntime version context (:home pages) sidebar
                           (css-text source-map) state state-key initial-state
                           phase pending ensured timers events registrations budget
                           runtime-error-fn)))
      (catch :default cause
        (clear-intervals! timers)
        (remove-state-slot! state-key)
        (throw (ex-info "Generated client code failed staging"
                        {:code :runtime/client-stage-failed
                         :cause-code (exception-code cause)}
                        cause))))))

(defn eval-runtime!
  "Evaluate incremental CLJS forms in one already-created browser SCI runtime.
  The caller decides whether that runtime is the public active product or a
  hidden REPL branch."
  [runtime code]
  (when-not (and (string? code) (<= 1 (count code) (* 64 1024)))
    (throw (ex-info "Browser REPL evaluation requires bounded source"
                    {:code :repl/client-code-invalid})))
  (when-not runtime
    (throw (ex-info "Browser runtime is unavailable"
                    {:code :runtime/client-not-active})))
  (let [previous-phase @(:phase runtime)]
    (reset! (:phase runtime) :repl)
    (try
      (reset-execution-budget! (:budget runtime))
      (let [evaluation (sci/eval-string+ (:context runtime) code
                                         {:file "workspace-browser-repl"})
            {:keys [pages sidebar events]} @(:registrations runtime)
            updated (assoc runtime
                           :page (:home pages)
                           :sidebar sidebar
                           :event-handlers events)]
        {:runtime updated
         :result {:value (pr-str (:val evaluation))
                  :runtime-version (:version runtime)
                  :page? (ifn? (:home pages))
                  :sidebar? (ifn? sidebar)
                  :state @(:state runtime)}})
      (catch :default cause
        (throw (ex-info "Incremental browser REPL evaluation failed"
                        {:code :repl/client-eval-failed}
                        cause)))
      (finally
        (reset! (:phase runtime) previous-phase)))))

(defn retain-evaluated!
  [runtime]
  (if (= (:version runtime) (active-version))
    (reset! active-runtime runtime)
    (swap! staged-runtimes assoc (:version runtime) runtime))
  runtime)

(defn eval-live!
  "Evaluate forms in the public active runtime. Workspace browser REPL turns
  normally use eval-runtime! on a hidden candidate frame instead."
  [code]
  (let [{:keys [runtime result]} (eval-runtime! @active-runtime code)]
    (retain-evaluated! runtime)
    result))

(defn retain-stage!
  [runtime]
  (when-not (= :staging @(:phase runtime))
    (throw (ex-info "Only a successfully probed runtime can be retained"
                    {:code :runtime/client-stage-not-ready
                     :version (:version runtime)})))
  (when (contains? @staged-runtimes (:version runtime))
    (throw (ex-info "Client runtime version is already staged"
                    {:code :runtime/client-stage-duplicate
                     :version (:version runtime)})))
  (when-let [active-version (active-version)]
    (when (<= (:version runtime) active-version)
      (throw (ex-info "Client runtime version is stale"
                      {:code :runtime/client-stage-stale
                       :active-version active-version
                       :version (:version runtime)}))))
  (swap! staged-runtimes assoc (:version runtime) runtime)
  runtime)

(defn staged-runtime
  [version]
  (get @staged-runtimes version))

(defn reject-runtime!
  [runtime]
  (swap! staged-runtimes
         (fn [staged]
           (into {}
                 (remove (fn [[_ candidate]] (identical? candidate runtime)))
                 staged)))
  (when-not (identical? runtime @active-runtime)
    (reset! (:phase runtime) :rejected)
    (clear-intervals! (:timers runtime))
    (remove-state-slot! (:state-key runtime)))
  nil)

(defn discard-stage!
  [version]
  (when-let [runtime (get @staged-runtimes version)]
    (reject-runtime! runtime))
  nil)

(defn activate!
  [version]
  (let [runtime (staged-runtime version)]
    (when-not runtime
      (throw (ex-info "Client runtime was not staged"
                      {:code :runtime/client-not-staged :version version})))
    (let [previous @active-runtime
          preserved-state @(active-page-state)]
      (reset! (:state runtime) (merge @(:initial-state runtime) preserved-state))
      (reset! (:phase runtime) :active)
      (when previous
        (reset! (:phase previous) :inactive)
        (clear-intervals! (:timers previous)))
      (reset! active-runtime runtime)
      (swap! staged-runtimes dissoc version)
      (when (and previous (not= (:state-key previous) (:state-key runtime)))
        (remove-state-slot! (:state-key previous)))
      runtime)))

(defn deliver-event!
  [topic value]
  (when-let [runtime @active-runtime]
    (when-let [handler (get (:event-handlers runtime) topic)]
      (try
        (ensure-phase! (:phase runtime) :active :product-event)
        (reset-execution-budget! (:budget runtime))
        (handler value)
        true
        (catch :default error
          ((:runtime-error-fn runtime) error)
          false)))))

(defn reset-runtime!
  []
  (doseq [runtime (cond-> (vals @staged-runtimes)
                    @active-runtime (conj @active-runtime))]
    (reset! (:phase runtime) :discarded)
    (clear-intervals! (:timers runtime)))
  (reset! active-runtime nil)
  (reset! staged-runtimes {})
  (reset! state-root {:base {}})
  nil)
