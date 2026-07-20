(ns ppp.websocket-test
  (:require [clojure.test :refer [deftest is testing]]
            [ppp.shared.protocol :as protocol]
            [ppp.websocket :as websocket]))

(defn- test-hub
  ([] (test-hub {}))
  ([overrides]
   (let [sent (atom [])
         closed (atom [])
         bundle {:runtime-version 0
                 :capability-version 1
                 :files []}]
     {:sent sent
      :closed closed
      :hub
      (websocket/create-hub
       (merge {:client-ack-timeout-ms 1000
               :require-client-ack? true
               :runtime-bundle-fn (constantly bundle)
               :send-fn (fn [channel frame]
                          (swap! sent conj [channel (websocket/decode-message frame)])
                          true)
               :close-fn #(swap! closed conj %)}
              overrides))})))

(defn- client-envelope
  [session-id request-id runtime-version type payload]
  (protocol/envelope {:session-id session-id
                      :request-id request-id
                      :runtime-version runtime-version
                      :type type
                      :payload payload}))

(defn- subscribe!
  [hub channel session-id tab-id version]
  (websocket/open! hub channel)
  (websocket/receive!
   hub channel
   (websocket/encode-message
    (client-envelope session-id (random-uuid) version :session/subscribe
                     {:tab-id tab-id :current-version version}))))

(defn- stage-request
  [session-id request-id tab-id transaction-id]
  {:session-id session-id
   :request-id request-id
   :tab-id tab-id
   :transaction-id transaction-id
   :base-version 0
   :target-version 1
   :capability-version 1
   :files [{:path "src/client/runtime/client.cljs" :content "source"}]})

(deftest transit-envelope-round-trips-keywords-and-uuids
  (let [message (client-envelope (random-uuid) (random-uuid) 0
                                 :session/subscribe
                                 {:tab-id (random-uuid) :current-version 0})]
    (is (= message (-> message websocket/encode-message websocket/decode-message)))
    (is (protocol/valid-client-envelope? message))))

(deftest stale-subscription-receives-current-runtime
  (let [{:keys [hub sent]} (test-hub
                            {:runtime-bundle-fn
                             (constantly {:runtime-version 3
                                          :capability-version 1
                                          :files [{:path "styles/runtime.css"
                                                   :content ":host {}"}]})})
        session-id (random-uuid)]
    (try
      (subscribe! hub :requester session-id (random-uuid) 1)
      (is (= :runtime/resync (get-in @sent [0 1 :type])))
      (is (= 3 (get-in @sent [0 1 :runtime-version])))
      (finally
        (websocket/stop! hub)))))

(deftest subscription-readiness-is-scoped-to-tab-session-and-version
  (let [{:keys [hub]} (test-hub)
        session-id (random-uuid)
        tab-id (random-uuid)]
    (try
      (is (not (websocket/subscribed? hub session-id tab-id 0)))
      (subscribe! hub :requester session-id tab-id 0)
      (is (websocket/subscribed? hub session-id tab-id 0))
      (is (not (websocket/subscribed? hub session-id tab-id 1)))
      (is (not (websocket/subscribed? hub session-id (random-uuid) 0)))
      (finally
        (websocket/stop! hub)))))

(deftest exact-request-tab-ack-is-the-only-successful-commit-signal
  (let [{:keys [hub sent]} (test-hub)
        session-id (random-uuid)
        request-id (random-uuid)
        tab-id (random-uuid)
        transaction-id (random-uuid)]
    (try
      (subscribe! hub :requester session-id tab-id 0)
      (let [submission (websocket/request-stage!
                        hub (stage-request session-id request-id tab-id transaction-id))]
        (is (= :runtime/stage (get-in @sent [0 1 :type])))
        (websocket/receive!
         hub :requester
         (websocket/encode-message
          (client-envelope
           session-id request-id 1 :runtime/staged
           {:tab-id tab-id
            :transaction-id transaction-id
            :base-version 0
            :target-version 1})))
        (is (= {:status :staged} (websocket/await-stage! hub submission)))
        (is (zero? (:pending-stages (websocket/stats hub)))))
      (finally
        (websocket/stop! hub)))))

(deftest project-repl-evaluation-round-trips-through-the-exact-request-tab
  (let [{:keys [hub sent]} (test-hub)
        session-id (random-uuid)
        request-id (random-uuid)
        tab-id (random-uuid)]
    (try
      (subscribe! hub :requester session-id tab-id 0)
      (reset! sent [])
      (let [submission
            (websocket/request-repl-eval!
             hub {:session-id session-id
                  :request-id request-id
                  :tab-id tab-id
                  :base-version 0
                  :code "(defn page [] [:main \"live\"])"})
            request (second (first @sent))
            evaluation-id (get-in request [:payload :evaluation-id])]
        (is (= :runtime/repl-eval (:type request)))
        (is (= "(defn page [] [:main \"live\"])"
               (get-in request [:payload :code])))
        (websocket/receive!
         hub :requester
         (websocket/encode-message
          (client-envelope
           session-id request-id 0 :runtime/repl-result
           {:tab-id tab-id
            :evaluation-id evaluation-id
            :base-version 0
            :result {:value ":home" :page? true :state {:draft "kept"}}})))
        (is (= {:status :accepted
                :result {:value ":home" :page? true :state {:draft "kept"}}}
               (websocket/await-repl-eval! hub submission))))
      (finally
        (websocket/stop! hub)))))

(deftest browser-rejection-preserves-bounded-failure-details
  (let [{:keys [hub]} (test-hub)
        session-id (random-uuid)
        request-id (random-uuid)
        tab-id (random-uuid)
        transaction-id (random-uuid)
        details ["Generated client code failed staging"
                 "Unable to resolve symbol: js/parent"]]
    (try
      (subscribe! hub :requester session-id tab-id 0)
      (let [submission (websocket/request-stage!
                        hub (stage-request session-id request-id tab-id transaction-id))]
        (websocket/receive!
         hub :requester
         (websocket/encode-message
          (client-envelope
           session-id request-id 1 :runtime/rejected
           {:tab-id tab-id
            :transaction-id transaction-id
            :base-version 0
            :target-version 1
            :code :runtime/client-stage-failed
            :details details})))
        (is (= {:status :rejected
                :code :runtime/client-stage-failed
                :details details}
               (websocket/await-stage! hub submission))))
      (finally
        (websocket/stop! hub)))))

(deftest follower-tab-cannot-ack-the-requesters-stage
  (let [{:keys [hub sent]} (test-hub)
        session-id (random-uuid)
        request-id (random-uuid)
        requester-tab (random-uuid)
        follower-tab (random-uuid)
        transaction-id (random-uuid)]
    (try
      (subscribe! hub :requester session-id requester-tab 0)
      (subscribe! hub :follower session-id follower-tab 0)
      (let [submission (websocket/request-stage!
                        hub (stage-request session-id request-id
                                           requester-tab transaction-id))
            ack (client-envelope
                 session-id request-id 1 :runtime/staged
                 {:tab-id requester-tab
                  :transaction-id transaction-id
                  :base-version 0
                  :target-version 1})]
        (websocket/receive! hub :follower (websocket/encode-message ack))
        (is (= 1 (:pending-stages (websocket/stats hub))))
        (is (= :runtime/resync (get-in @sent [(dec (count @sent)) 1 :type])))
        (websocket/receive! hub :requester (websocket/encode-message ack))
        (is (= {:status :staged} (websocket/await-stage! hub submission))))
      (finally
        (websocket/stop! hub)))))

(deftest stale-requester-ack-rejects-without-commit
  (let [{:keys [hub]} (test-hub)
        session-id (random-uuid)
        request-id (random-uuid)
        tab-id (random-uuid)
        transaction-id (random-uuid)]
    (try
      (subscribe! hub :requester session-id tab-id 0)
      (let [submission (websocket/request-stage!
                        hub (stage-request session-id request-id tab-id transaction-id))]
        (websocket/receive!
         hub :requester
         (websocket/encode-message
          (client-envelope
           session-id request-id 2 :runtime/staged
           {:tab-id tab-id
            :transaction-id transaction-id
            :base-version 1
            :target-version 2})))
        (is (= {:status :rejected :code :runtime/stale-browser-version}
               (websocket/await-stage! hub submission))))
      (finally
        (websocket/stop! hub)))))

(deftest disconnect-and-timeout-both-reject-staging
  (testing "request tab disconnect"
    (let [{:keys [hub]} (test-hub)
          session-id (random-uuid)
          request-id (random-uuid)
          tab-id (random-uuid)
          transaction-id (random-uuid)]
      (try
        (subscribe! hub :requester session-id tab-id 0)
        (let [submission (websocket/request-stage!
                          hub (stage-request session-id request-id tab-id transaction-id))]
          (websocket/close! hub :requester 1001)
          (is (= {:status :rejected :code :runtime/requester-disconnected}
                 (websocket/await-stage! hub submission))))
        (finally
          (websocket/stop! hub)))))
  (testing "stage timeout"
    (let [{:keys [hub]} (test-hub {:client-ack-timeout-ms 10})
          session-id (random-uuid)
          request-id (random-uuid)
          tab-id (random-uuid)
          transaction-id (random-uuid)]
      (try
        (subscribe! hub :requester session-id tab-id 0)
        (let [submission (websocket/request-stage!
                          hub (stage-request session-id request-id tab-id transaction-id))]
          (is (= {:status :rejected :code :runtime/client-timeout}
                 (websocket/await-stage! hub submission))))
        (finally
          (websocket/stop! hub))))))

(deftest duplicate-and-late-acks-cannot-reopen-a-settled-stage
  (let [{:keys [hub sent]} (test-hub)
        session-id (random-uuid)
        request-id (random-uuid)
        tab-id (random-uuid)
        transaction-id (random-uuid)]
    (try
      (subscribe! hub :requester session-id tab-id 0)
      (let [submission (websocket/request-stage!
                        hub (stage-request session-id request-id
                                           tab-id transaction-id))
            ack
            (client-envelope
             session-id request-id 1 :runtime/staged
             {:tab-id tab-id
              :transaction-id transaction-id
              :base-version 0
              :target-version 1})]
        (websocket/receive! hub :requester (websocket/encode-message ack))
        (is (= {:status :staged} (websocket/await-stage! hub submission)))
        (websocket/receive! hub :requester (websocket/encode-message ack))
        (is (zero? (:pending-stages (websocket/stats hub))))
        (is (= :runtime/resync (get-in @sent [(dec (count @sent)) 1 :type]))))
      (finally
        (websocket/stop! hub)))))

(deftest activation-targets-requester-while-followers-resync
  (let [{:keys [hub sent]} (test-hub)
        session-id (random-uuid)
        request-id (random-uuid)
        requester-tab (random-uuid)
        follower-tab (random-uuid)]
    (try
      (subscribe! hub :requester session-id requester-tab 0)
      (subscribe! hub :follower session-id follower-tab 0)
      (reset! sent [])
      (websocket/publish-activation!
       hub {:session-id session-id
            :request-id request-id
            :tab-id requester-tab
            :target-version 1
            :manifest {:runtime-version 1}
            :bundle {:runtime-version 1 :files []}})
      (is (= :runtime/activate
             (:type (second (first (filter #(= :requester (first %)) @sent))))))
      (is (= :runtime/resync
             (:type (second (first (filter #(= :follower (first %)) @sent))))))
      (finally
        (websocket/stop! hub)))))
