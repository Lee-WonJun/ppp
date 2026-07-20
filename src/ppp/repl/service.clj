(ns ppp.repl.service
  "Loopback nREPL lifecycle for the trusted Workspace REPL profile.

  This service is deliberately not an HTTP capability and never binds a public
  address. A workspace owns one long-lived nREPL client session. The standard
  nREPL evaluator changes Vars in the running PPP JVM; callers must therefore
  enable this profile only inside a workspace isolation boundary."
  (:require [clojure.string :as str]
            [nrepl.core :as nrepl]
            [nrepl.server :as nrepl-server])
  (:import (java.io Closeable)
           (java.util UUID)))

(defrecord WorkspaceSession [workspace-id namespace transport client])
(defrecord ReplService [server port sessions lock])

(def ^:private result-character-limit (* 256 1024))

(defn- parse-workspace-id
  [value]
  (try
    (str (UUID/fromString (str value)))
    (catch IllegalArgumentException cause
      (throw (ex-info "Workspace REPL requires a UUID project identifier"
                      {:code :repl/workspace-id-invalid}
                      cause)))))

(defn workspace-namespace
  [workspace-id]
  (-> (parse-workspace-id workspace-id)
      (str/replace "-" "_")
      (#(symbol (str "ppp.workspace.session_" %)))))

(defn start!
  ([] (start! {}))
  ([{:keys [bind port]
     :or {bind "127.0.0.1" port 0}}]
   (when-not (contains? #{"127.0.0.1" "localhost" "::1"} bind)
     (throw (ex-info "Workspace nREPL must bind to loopback"
                     {:code :repl/public-bind-denied :bind bind})))
   (let [server (nrepl-server/start-server :bind bind :port port)]
     (->ReplService server (:port server) (atom {}) (Object.)))))

(defn- close-transport!
  [transport]
  (when (instance? Closeable transport)
    (.close ^Closeable transport)))

(defn stop!
  [^ReplService service]
  (doseq [{:keys [transport]} (vals @(:sessions service))]
    (try
      (close-transport! transport)
      (catch Exception _ nil)))
  (reset! (:sessions service) {})
  (when-let [server (:server service)]
    (nrepl-server/stop-server server))
  nil)

(defn- response-error
  [responses]
  (let [statuses (into #{} (mapcat :status) responses)
        error-text (->> responses (keep :err) (apply str))]
    (when (or (seq error-text)
              (some statuses ["eval-error" "namespace-not-found" "error"]))
      (ex-info "Workspace nREPL evaluation failed"
               {:code :repl/eval-failed
                :status statuses
                :error (when-not (str/blank? error-text)
                         (subs error-text 0 (min 4096 (count error-text))))}))))

(defn- collect-response
  [responses]
  (let [responses
        (:responses
         (reduce
          (fn [{:keys [characters responses]} response]
            (let [characters (+ characters
                                (reduce + 0
                                        (map count
                                             (keep response [:value :out :err]))))]
              (when (> characters result-character-limit)
                (throw (ex-info "Workspace nREPL result exceeded its bound"
                                {:code :repl/result-too-large})))
              {:characters characters :responses (conj responses response)}))
          {:characters 0 :responses []}
          responses))]
    (when-let [cause (response-error responses)]
      (throw cause))
    {:values (vec (keep :value responses))
     :out (apply str (keep :out responses))
     :err (apply str (keep :err responses))
     :status (into #{} (mapcat :status) responses)
     :namespace (some :ns responses)}))

(defn open-workspace!
  [^ReplService service workspace-id]
  (let [workspace-id (parse-workspace-id workspace-id)]
    (or (get @(:sessions service) workspace-id)
        (let [transport (nrepl/connect :host "127.0.0.1" :port (:port service))
              client (nrepl/client transport 10000)
              session-client (nrepl/client-session client)
              namespace (workspace-namespace workspace-id)
              session (->WorkspaceSession workspace-id namespace transport
                                          session-client)]
          (try
            (collect-response
             (nrepl/message
              session-client
              {:op "eval"
               :code (str "(do (create-ns '" namespace ")"
                          " (in-ns '" namespace ")"
                          " (clojure.core/refer 'clojure.core)"
                          " :workspace/ready)")}))
            (let [sessions (swap! (:sessions service)
                                  #(if (contains? % workspace-id)
                                     %
                                     (assoc % workspace-id session)))
                  winner (get sessions workspace-id)]
              (when-not (identical? session winner)
                (close-transport! transport))
              winner)
            (catch Exception cause
              (close-transport! transport)
              (throw cause)))))))

(defn workspace-session
  [^ReplService service workspace-id]
  (get @(:sessions service) (parse-workspace-id workspace-id)))

(defn eval!
  [^ReplService service workspace-id code]
  (when-not (and (string? code) (<= 1 (count code) (* 64 1024)))
    (throw (ex-info "Workspace nREPL code must be a bounded non-empty string"
                    {:code :repl/code-invalid})))
  (let [{:keys [namespace client]} (open-workspace! service workspace-id)]
    (collect-response
     (nrepl/message client {:op "eval" :ns (str namespace) :code code}))))

(defn close-workspace!
  [^ReplService service workspace-id]
  (let [workspace-id (parse-workspace-id workspace-id)]
    (locking service
      (when-let [{:keys [transport namespace]} (get @(:sessions service) workspace-id)]
        (swap! (:sessions service) dissoc workspace-id)
        (close-transport! transport)
        (when (find-ns namespace)
          (remove-ns namespace)))))
  nil)

(defn endpoint
  "Return non-secret loopback metadata for readiness and the project-scoped
  Codex tool installer. Never expose it through the public bootstrap API."
  [^ReplService service]
  {:host "127.0.0.1" :port (:port service)})
