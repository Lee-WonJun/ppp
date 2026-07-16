(ns ppp.scheduler-test
  (:require [clojure.test :refer [deftest is]]
            [ppp.coordinator :as coordinator]
            [ppp.outbound.service :as outbound]
            [ppp.provider.fake :as fake]
            [ppp.provider.queue :as provider-queue]
            [ppp.runtime.resources :as resources]
            [ppp.runtime.server :as server]
            [ppp.runtime.sqlite :as sqlite]
            [ppp.scheduler :as scheduler]
            [ppp.session.store :as store]
            [ppp.shared.protocol :as protocol]
            [ppp.util.fs :as fs]
            [ppp.websocket :as websocket])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(deftest kernel-scheduler-completes-due-generated-work-without-another-user-action
  (let [root (Files/createTempDirectory "ppp-scheduler-test"
                                        (make-array FileAttribute 0))
        config {:data-dir root
                :prompt-limit 4000
                :queue-capacity 8
                :provider-timeout-ms 5000
                :client-ack-timeout-ms 100
                :require-client-ack? false
                :source-file-limit 32
                :source-byte-limit (* 256 1024)
                :session-db-limit (* 25 1024 1024)
                :checkpoint-limit (* 256 1024 1024)
                :instance-limit (* 2 1024 1024 1024)}
        session-store (store/create-store config)
        queue (provider-queue/create-queue config)
        registry (server/create-registry)
        resource-service (resources/create-service config)
        sent (atom [])
        channel (Object.)
        hub (websocket/create-hub
             (assoc config
                    :runtime-bundle-fn
                    (fn [session-id]
                      {:runtime-version
                       (:runtime-version (store/current-manifest session-store
                                                                 session-id))})
                    :send-fn (fn [_ frame]
                               (swap! sent conj (websocket/decode-message frame))
                               true)
                    :close-fn (constantly true)))
        coordinator (coordinator/create-coordinator
                     {:config config
                      :store session-store
                      :provider (fake/create-provider)
                      :provider-queue queue
                      :registry registry
                      :resource-service resource-service
                      :outbound (outbound/create-service {})
                      :hub hub})
        session (coordinator/create-session! coordinator)
        session-id (:id session)
        source
        (assoc store/initial-source
               "src/server/runtime/server.clj"
               (str
                "(ns runtime.server (:require [runtime.api :as api]))\n"
                "(defn build! [{:keys [id]}]\n"
                "  (api/search-upsert! :reports id {:text \"finished report\" :metadata {:id id}})\n"
                "  (api/publish! :reports/finished {:id id})\n"
                "  {:finished id})\n"
                "(defn schedule! [{:keys [id]}]\n"
                "  (api/schedule-job! :reports/build {:id id} {:idempotency-key id}))\n"
                "(api/register-job! :reports/build build!)\n"
                "(api/register-action! :reports/schedule schedule!)\n"))
        runtime (server/stage! {:source-map source
                                :database-path (store/current-db-path session-store
                                                                      session-id)
                                :session-id session-id
                                :resource-service resource-service
                                :version 0
                                :run-tests? false})
        _ (server/activate! registry session-id runtime)
        tab-id (random-uuid)
        _ (websocket/open! hub channel)
        _ (websocket/receive!
           hub channel
           (websocket/encode-message
            (protocol/envelope {:session-id session-id
                                :request-id (random-uuid)
                                :runtime-version 0
                                :type :session/subscribe
                                :payload {:tab-id tab-id :current-version 0}})))
        runner (scheduler/start! coordinator {:job-poll-interval-ms 50})]
    (try
      (let [scheduled (coordinator/invoke-action! coordinator session-id
                                                  :reports/schedule
                                                  {:id "daily"})
            job-id (get-in scheduled [:result :id])
            deadline (+ (System/currentTimeMillis) 5000)]
        (loop []
          (let [status (:status (resources/job-status resource-service
                                                      (:database runtime) job-id))]
            (when (and (not= :completed status)
                       (< (System/currentTimeMillis) deadline))
              (Thread/sleep 25)
              (recur))))
        (is (= :completed
               (:status (resources/job-status resource-service
                                              (:database runtime) job-id))))
        (is (= ["daily"]
               (mapv :id (resources/search-query resource-service
                                                 (:database runtime)
                                                 :reports "finished" {}))))
        (is (= :product/event (:type (last @sent))))
        (is (= :reports/finished (get-in (last @sent) [:payload :topic])))
        (is (scheduler/running? runner))
        (is (= 0 (sqlite/runtime-version (:database runtime)))))
      (finally
        (scheduler/stop! runner)
        (provider-queue/stop! queue)
        (websocket/stop! hub)
        (fs/delete-tree! root)))))
