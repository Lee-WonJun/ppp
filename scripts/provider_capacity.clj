(ns provider-capacity
  (:require [clojure.string :as str]
            [ppp.config :as config]
            [ppp.provider.budget :as budget]))

(def application-config (-> (config/load-config) config/validate!))
(def provider-budget (budget/create-budget application-config))
(def command (or (first *command-line-args*) "status"))

(case command
  "status"
  (let [{:keys [enabled? limit used remaining retry-after-seconds]}
        (budget/inspect-status provider-budget)]
    (println (str "Provider capacity: "
                  (if enabled? "metered" "unmetered fake provider")))
    (when enabled?
      (println (str "Used in rolling window: " used "/" limit))
      (println (str "Remaining starts: " remaining))
      (when (pos? retry-after-seconds)
        (println (str "Available again in about " retry-after-seconds
                      " seconds.")))))

  "reset"
  (do
    (budget/reset-ledger! provider-budget)
    (println (str "Provider capacity ledger reset under "
                  (:data-dir application-config)
                  ". Restart the application before accepting new turns.")))

  (throw (ex-info "Use status or reset"
                  {:command (str/trim command)})))
