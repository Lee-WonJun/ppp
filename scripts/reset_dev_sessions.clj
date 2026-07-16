(ns reset-dev-sessions
  (:require [babashka.fs :as fs]))

(def environment (or (System/getenv "PPP_ENV") "development"))
(def data-root (fs/absolutize (or (System/getenv "PPP_DATA_DIR") "data")))
(def sessions-root (fs/path data-root "workspaces" "local" "sessions"))

(when (= "production" environment)
  (throw (ex-info "reset-dev-sessions refuses to run with PPP_ENV=production"
                  {:environment environment})))

(fs/create-dirs sessions-root)

(let [sessions (vec (fs/list-dir sessions-root))]
  (doseq [session sessions]
    (fs/delete-tree session))
  (println (str "Deleted " (count sessions) " development session(s) from "
                sessions-root "; kernel state was preserved."))
  (println "Restart a running development JVM to clear its in-memory runtime registry."))
