(ns ppp.system
  (:require [integrant.core :as ig]
            [ppp.coordinator :as coordinator]
            [ppp.http :as http]
            [ppp.outbound.service :as outbound]
            [ppp.provider.codex :as codex]
            [ppp.provider.fake :as fake]
            [ppp.provider.queue :as provider-queue]
            [ppp.runtime.server :as server]
            [ppp.session.store :as store]
            [ppp.shared.protocol :as protocol]
            [ppp.websocket :as websocket]))

(defmethod ig/init-key :ppp/session-store
  [_ config]
  (store/create-store config))

(defmethod ig/init-key :ppp/provider
  [_ config]
  (case (:provider config)
    :fake (fake/create-provider)
    :codex (codex/create-provider config)
    (throw (ex-info "Unknown AI provider" {:provider (:provider config)}))))

(defmethod ig/init-key :ppp/provider-queue
  [_ config]
  (provider-queue/create-queue config))

(defmethod ig/halt-key! :ppp/provider-queue
  [_ queue]
  (provider-queue/stop! queue))

(defmethod ig/init-key :ppp/runtime-registry
  [_ _]
  (server/create-registry))

(defmethod ig/init-key :ppp/outbound
  [_ config]
  (outbound/create-service config))

(defmethod ig/init-key :ppp/websocket
  [_ {:keys [config session-store]}]
  (websocket/create-hub
   (assoc config
          :runtime-bundle-fn
          (fn [session-id]
            (let [manifest (store/current-manifest session-store session-id)]
              {:runtime-version (:runtime-version manifest)
               :capability-version (:capability-version manifest)
               :manifest manifest
               :files (protocol/client-runtime-files
                       (store/current-source-map session-store session-id))})))))

(defmethod ig/halt-key! :ppp/websocket
  [_ hub]
  (websocket/stop! hub))

(defmethod ig/init-key :ppp/coordinator
  [_ dependencies]
  (coordinator/initialize! (coordinator/create-coordinator dependencies)))

(defmethod ig/init-key :ppp/http
  [_ {:keys [config session-store coordinator websocket]}]
  (let [handler (http/create-handler {:config config
                                      :session-store session-store
                                      :coordinator coordinator
                                      :websocket websocket
                                      :readiness #(coordinator/readiness coordinator)})
        stop (http/start! handler config)]
    {:handler handler :stop stop}))

(defmethod ig/halt-key! :ppp/http
  [_ {:keys [stop]}]
  (when stop
    (stop :timeout 1000)))

(defn config
  [application-config]
  {:ppp/session-store application-config
   :ppp/provider application-config
   :ppp/provider-queue application-config
   :ppp/runtime-registry {}
   :ppp/outbound application-config
   :ppp/websocket {:config application-config
                   :session-store (ig/ref :ppp/session-store)}
   :ppp/coordinator {:config application-config
                     :store (ig/ref :ppp/session-store)
                     :provider (ig/ref :ppp/provider)
                     :provider-queue (ig/ref :ppp/provider-queue)
                     :registry (ig/ref :ppp/runtime-registry)
                     :outbound (ig/ref :ppp/outbound)
                     :hub (ig/ref :ppp/websocket)}
   :ppp/http {:config application-config
              :session-store (ig/ref :ppp/session-store)
              :coordinator (ig/ref :ppp/coordinator)
              :websocket (ig/ref :ppp/websocket)}})
