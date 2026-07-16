(ns ppp.integration.resource-plane-http-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [ppp.coordinator :as coordinator]
            [ppp.http :as http]
            [ppp.outbound.service :as outbound]
            [ppp.provider.fake :as fake]
            [ppp.provider.queue :as provider-queue]
            [ppp.runtime.resources :as resources]
            [ppp.runtime.server :as server]
            [ppp.session.store :as store]
            [ppp.shared.protocol :as protocol]
            [ppp.util.fs :as fs]
            [ppp.websocket :as websocket])
  (:import (java.io ByteArrayInputStream)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(defn- utf8-bytes
  [value]
  (.getBytes ^String value StandardCharsets/UTF_8))

(defn- signature
  [secret ^bytes body]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. (utf8-bytes secret) "HmacSHA256")))]
    (str "sha256="
         (apply str (map #(format "%02x" (bit-and 0xff %)) (.doFinal mac body))))))

(defn- request
  [session-id body signature]
  {:request-method :post
   :uri (str "/public/sessions/" session-id "/events")
   :remote-addr "203.0.113.9"
   :headers {"content-type" "application/json"
             "x-webhook-signature" signature}
   :body (ByteArrayInputStream. body)})

(defn- setup
  []
  (let [root (Files/createTempDirectory "ppp-resource-http-test"
                                        (make-array FileAttribute 0))
        connectors-file (.resolve root "connectors.edn")
        _ (fs/atomic-write-string!
           connectors-file
           (pr-str {:ingress-verifiers
                    {:judge-hook
                     {:description "Judge result hook"
                      :algorithm :hmac-sha256
                      :header "x-webhook-signature"
                      :prefix "sha256="
                      :secret {:env "FAKE_JUDGE_SECRET"}}}}))
        config {:data-dir root
                :connectors-file connectors-file
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
        outbound-service (outbound/create-service
                          (assoc config :outbound-env
                                 #(when (= "FAKE_JUDGE_SECRET" %) "test-secret")))
        sent (atom [])
        channel (Object.)
        hub (websocket/create-hub
             (assoc config
                    :runtime-bundle-fn
                    (fn [session-id]
                      (let [manifest (store/current-manifest session-store session-id)]
                        {:runtime-version (:runtime-version manifest)
                         :manifest manifest
                         :files []}))
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
                      :outbound outbound-service
                      :hub hub})
        session (coordinator/create-session! coordinator)
        session-id (:id session)
        source
        (assoc store/initial-source
               "src/server/runtime/server.clj"
               (str
                "(ns runtime.server (:require [runtime.api :as api]))\n"
                "(defn receive! [request]\n"
                "  (api/search-upsert! :hooks \"latest\" {:text (pr-str (:body request)) :metadata {:method (:method request)}})\n"
                "  (api/publish! :hook/received {:accepted true})\n"
                "  {:status 202 :body {:accepted true :received (:body request)}})\n"
                "(api/register-ingress! :events {:verification :judge-hook} receive!)\n"))
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
        handler (http/create-handler {:config config
                                      :session-store session-store
                                      :coordinator coordinator
                                      :websocket hub})]
    {:root root :queue queue :hub hub :session-id session-id :sent sent
     :coordinator coordinator
     :handler handler :database (:database runtime) :resource-service resource-service}))

(defn- close!
  [{:keys [root queue hub]}]
  (provider-queue/stop! queue)
  (websocket/stop! hub)
  (fs/delete-tree! root))

(deftest public-ingress-verifies-before-one-transaction-and-publishes-after-commit
  (let [context (setup)]
    (try
      (let [payload (utf8-bytes (json/write-str {:score 42}))
            response ((:handler context)
                      (request (:session-id context) payload
                               (signature "test-secret" payload)))
            response-body (json/read-str (:body response) :key-fn keyword)]
        (is (= 202 (:status response)))
        (is (= {:score 42} (get-in response-body [:received])))
        (is (= ["latest"]
               (mapv :id (resources/search-query (:resource-service context)
                                                 (:database context) :hooks
                                                 "score" {}))))
        (is (= :product/event (:type (last @(:sent context)))))
        (is (= :hook/received (get-in (last @(:sent context)) [:payload :topic]))))

      (testing "an invalid signature produces no data or event side effect"
        (let [before-events (count @(:sent context))
              response ((:handler context)
                        (request (:session-id context) (utf8-bytes "{\"score\":99}")
                                 "sha256=00"))]
          (is (= 401 (:status response)))
          (is (= before-events (count @(:sent context))))))

      (testing "the bounded public body is rejected before generated work"
        (let [too-large (byte-array (inc (* 1024 1024)))
              response ((:handler context)
                        (request (:session-id context) too-large
                                 (signature "test-secret" too-large)))]
          (is (= 413 (:status response)))))
      (finally
        (close! context)))))

(deftest public-ingress-rate-limit-prevents-handler-and-preserves-namespaced-reason
  (let [context (setup)]
    (try
      (let [payload (utf8-bytes "{}")]
        (dotimes [_ 60]
          (is (= 401 (:status ((:handler context)
                               (request (:session-id context) payload
                                        "sha256=00"))))))
        (let [response ((:handler context)
                        (request (:session-id context) payload
                                 (signature "test-secret" payload)))
              body (json/read-str (:body response) :key-fn keyword)]
          (is (= 429 (:status response)))
          (is (= "ingress/rate-limited" (get-in body [:error :code])))
          (is (empty? @(:sent context)))
          (is (empty? (resources/search-query (:resource-service context)
                                              (:database context) :hooks
                                              "score" {})))))
      (finally
        (close! context)))))
