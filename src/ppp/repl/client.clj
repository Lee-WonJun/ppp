(ns ppp.repl.client
  "Small standard nREPL client used by the project-scoped Codex Skill.

  Code is read from stdin so generated forms never pass through a shell command
  line. The endpoint is loopback-only and belongs to the trusted Workspace REPL
  execution profile."
  (:gen-class)
  (:require [clojure.edn :as edn]
            [nrepl.core :as nrepl]
            [ppp.repl.service :as service]))

(defn- parse-port
  [value]
  (let [port (parse-long (str value))]
    (when-not (and port (<= 1 port 65535))
      (throw (ex-info "Invalid nREPL port" {:code :repl/port-invalid})))
    port))

(defn eval-endpoint!
  [{:keys [host port workspace-id code timeout-ms]
    :or {host "127.0.0.1" timeout-ms 10000}}]
  (when-not (contains? #{"127.0.0.1" "localhost" "::1"} host)
    (throw (ex-info "The project REPL client accepts loopback only"
                    {:code :repl/public-connect-denied})))
  (let [namespace (service/workspace-namespace workspace-id)]
    (with-open [transport (nrepl/connect :host host :port (parse-port port))]
      (let [client (nrepl/client transport timeout-ms)
            responses (vec (nrepl/message client {:op "eval"
                                                  :ns (str namespace)
                                                  :code code}))
            statuses (into #{} (mapcat :status) responses)
            errors (apply str (keep :err responses))]
        (cond-> {:values (vec (keep :value responses))
                 :out (apply str (keep :out responses))
                 :status statuses
                 :namespace (some :ns responses)}
          (seq errors) (assoc :error errors))))))

(defn -main
  [& [config-path]]
  (when-not config-path
    (throw (ex-info "Usage: ppp-repl <connection.edn>; code is read on stdin"
                    {:code :repl/usage})))
  (let [config (edn/read-string (slurp config-path))
        code (slurp *in*)
        result (eval-endpoint! (assoc config :code code))]
    (prn result)
    (when (seq (:error result))
      (System/exit 1))))
