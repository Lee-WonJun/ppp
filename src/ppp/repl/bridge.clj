(ns ppp.repl.bridge
  "Project-scoped bridge from the trusted nREPL namespace to the active
  generated product runtimes. It is never routed through public HTTP."
  (:require [ppp.runtime.server :as server]
            [ppp.session.store :as store]
            [ppp.repl.service :as repl]))

(defonce ^:private projects (atom {}))

(defn register-project!
  [session-id registry]
  (let [session-id (store/parse-session-id session-id)]
    (swap! projects update (str session-id)
           (fn [project]
             (merge {:session-id session-id
                     :registry registry
                     :client-evaluator nil
                     :turn nil}
                    project
                    {:session-id session-id :registry registry})))
    session-id))

(defn unregister-project!
  [session-id]
  (swap! projects dissoc (str (store/parse-session-id session-id)))
  nil)

(defn- project!
  [session-id]
  (or (get @projects (str (store/parse-session-id session-id)))
      (throw (ex-info "Workspace REPL project is unavailable"
                      {:code :repl/project-not-found}))))

(defn begin-turn!
  [session-id request-id]
  (let [session-id (store/parse-session-id session-id)]
    (swap! projects update (str session-id)
           (fn [project]
             (when-not project
               (throw (ex-info "Workspace REPL project is unavailable"
                               {:code :repl/project-not-found})))
             (assoc project :turn {:request-id request-id :events []}))))
  true)

(defn- record-event!
  [session-id event]
  (let [session-id (store/parse-session-id session-id)]
    (swap! projects update-in [(str session-id) :turn :events]
           (fn [events]
             (if (vector? events)
               (conj events event)
               events))))
  event)

(defn finish-turn!
  [session-id request-id]
  (let [session-id (store/parse-session-id session-id)
        key (str session-id)
        project (get @projects key)
        turn (:turn project)]
    (when-not (= request-id (:request-id turn))
      (throw (ex-info "Workspace REPL turn identity changed"
                      {:code :repl/turn-conflict})))
    (swap! projects update key assoc :turn nil)
    (:events turn)))

(defn describe-server
  [session-id]
  (let [{:keys [registry session-id]} (project! session-id)]
    (try
      (let [result (server/describe-live registry session-id)]
        (record-event! session-id {:operation :server/inspect
                                   :status :accepted
                                   :result result})
        result)
      (catch Exception cause
        (record-event! session-id {:operation :server/inspect
                                   :status :rejected
                                   :code (:code (ex-data cause))})
        (throw cause)))))

(defn inspect-workspace-database
  [session-id]
  (let [{:keys [registry session-id]} (project! session-id)]
    (try
      (let [result (server/inspect-workspace-database registry session-id)]
        (record-event! session-id {:operation :database/inspect
                                   :status :accepted
                                   :result result})
        result)
      (catch Exception cause
        (record-event! session-id {:operation :database/inspect
                                   :status :rejected
                                   :code (:code (ex-data cause))})
        (throw cause)))))

(defn query-workspace-database!
  [session-id sql params]
  (let [{:keys [registry session-id]} (project! session-id)]
    (try
      (let [result (server/query-workspace-database! registry session-id sql params)]
        (record-event! session-id {:operation :database/query
                                   :status :accepted
                                   :sql sql
                                   :result result})
        result)
      (catch Exception cause
        (record-event! session-id {:operation :database/query
                                   :status :rejected
                                   :sql sql
                                   :code (:code (ex-data cause))})
        (throw cause)))))

(defn mutate-workspace-database!
  [session-id sql params]
  (let [{:keys [registry session-id]} (project! session-id)]
    (try
      (let [result (server/mutate-workspace-database! registry session-id sql params)]
        (record-event! session-id {:operation :database/mutate
                                   :status :accepted
                                   :sql sql
                                   :result result})
        result)
      (catch Exception cause
        (record-event! session-id {:operation :database/mutate
                                   :status :rejected
                                   :sql sql
                                   :code (:code (ex-data cause))})
        (throw cause)))))

(defn eval-server!
  [session-id form-or-code]
  (let [{:keys [registry session-id]} (project! session-id)]
    (try
      (let [host-form? (not (string? form-or-code))
            source (if host-form? (pr-str form-or-code) form-or-code)
            result
            (if host-form?
              (let [expected-ns (repl/workspace-namespace session-id)]
                (server/eval-host-live!
                 registry session-id source
                 #(binding [*ns* (the-ns expected-ns)]
                    (eval form-or-code))))
              ;; Transitional compatibility for old repair threads. New
              ;; Workspace REPL turns submit quoted host forms and therefore
              ;; redefine real JVM Vars above.
              (server/eval-live! registry session-id source))]
        (record-event! session-id {:operation :server/eval
                                   :status :accepted
                                   :form source
                                   :evaluation-realm (if host-form? :jvm-var :sci-guest)
                                   :result (dissoc result :value)})
        result)
      (catch Exception cause
        (record-event! session-id {:operation :server/eval
                                   :status :rejected
                                   :form (if (string? form-or-code)
                                           form-or-code
                                           (pr-str form-or-code))
                                   :code (:code (ex-data cause))
                                   :cause-code (:cause-code (ex-data cause))})
        (throw cause)))))

(defn call-runtime!
  [session-id capability & args]
  (let [{:keys [registry session-id]} (project! session-id)]
    (server/call-live-capability! registry session-id capability args)))

(defn invoke-server!
  [session-id action-id payload]
  (let [{:keys [registry session-id]} (project! session-id)]
    (try
      (let [result (server/invoke! registry session-id action-id payload)]
        (record-event! session-id {:operation :server/invoke
                                   :status :accepted
                                   :action-id action-id
                                   :result result})
        result)
      (catch Exception cause
        (record-event! session-id {:operation :server/invoke
                                   :status :rejected
                                   :action-id action-id
                                   :code (:code (ex-data cause))})
        (throw cause)))))

(defn migrate-server!
  [session-id name sql]
  (let [{:keys [registry session-id]} (project! session-id)]
    (try
      (let [result (server/migrate-live! registry session-id name sql)]
        (record-event! session-id {:operation :server/migrate
                                   :status :accepted
                                   :name name
                                   :sql sql
                                   :result result})
        result)
      (catch Exception cause
        (record-event! session-id {:operation :server/migrate
                                   :status :rejected
                                   :name name
                                   :sql sql
                                   :code (:code (ex-data cause))})
        (throw cause)))))

(defn bind-client-evaluator!
  [session-id request-id evaluator]
  (when-not (ifn? evaluator)
    (throw (ex-info "Browser REPL evaluator must be callable"
                    {:code :repl/client-evaluator-invalid})))
  (let [session-id (store/parse-session-id session-id)]
    (swap! projects update (str session-id)
           (fn [project]
             (when-not project
               (throw (ex-info "Workspace REPL project is unavailable"
                               {:code :repl/project-not-found})))
             (assoc project :client-evaluator {:request-id request-id
                                               :evaluate evaluator}))))
  true)

(defn clear-client-evaluator!
  [session-id request-id]
  (let [session-id (store/parse-session-id session-id)]
    (swap! projects update (str session-id)
           (fn [project]
             (if (= request-id (get-in project [:client-evaluator :request-id]))
               (assoc project :client-evaluator nil)
               project))))
  nil)

(defn eval-client!
  [session-id code]
  (let [{:keys [client-evaluator session-id]} (project! session-id)]
    (when-not client-evaluator
      (throw (ex-info "No requesting browser is attached to this REPL turn"
                      {:code :repl/client-unavailable})))
    (try
      (let [result ((:evaluate client-evaluator) code)]
        (record-event! session-id {:operation :client/eval
                                   :status :accepted
                                   :form code
                                   :result result})
        result)
      (catch Exception cause
        (record-event! session-id {:operation :client/eval
                                   :status :rejected
                                   :form code
                                   :code (:code (ex-data cause))})
        (throw cause)))))
