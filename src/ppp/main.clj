(ns ppp.main
  (:gen-class)
  (:require [integrant.core :as ig]
            [ppp.config :as config]
            [ppp.system :as system]))

(defonce running-system (atom nil))

(defn stop!
  []
  (when-let [instance @running-system]
    (ig/halt! instance)
    (reset! running-system nil)))

(defn -main
  [& _]
  (let [application-config (-> (config/load-config) config/validate!)
        instance (ig/init (system/config application-config))]
    (reset! running-system instance)
    (.addShutdownHook (Runtime/getRuntime) (Thread. stop! "ppp-shutdown"))
    (println (str "PPP listening on " (:public-base-url application-config)))
    @(promise)))
