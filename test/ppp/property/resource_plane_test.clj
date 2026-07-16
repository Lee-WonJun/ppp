(ns ppp.property.resource-plane-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ppp.outbound.service :as outbound]
            [ppp.runtime.resources :as resources]
            [ppp.runtime.sqlite :as sqlite]
            [ppp.shared.protocol :as protocol]
            [ppp.util.fs :as fs]
            [ppp.websocket :as websocket])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)
           (java.util Base64)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(defn- test-root
  [prefix]
  (Files/createTempDirectory prefix (make-array FileAttribute 0)))

(defn- service
  ([] (resources/create-service {}))
  ([clock]
   (resources/create-service {} {:now-fn #(deref clock)})))

(defn- base64
  [values]
  (.encodeToString (Base64/getEncoder) (byte-array values)))

(defn- signed
  [secret ^bytes body]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec.
                      (.getBytes ^String secret StandardCharsets/UTF_8)
                      "HmacSHA256")))]
    (str "sha256="
         (apply str (map #(format "%02x" (bit-and 0xff %)) (.doFinal mac body))))))

(deftest pbt-11-arbitrary-bytes-round-trip-with-identical-metadata
  (let [root (test-root "ppp-blob-property")]
    (try
      (let [database (sqlite/init! (.resolve root "blobs.sqlite"))
            service (service)
            result
            (tc/quick-check
             1000
             (prop/for-all
              [values (gen/vector gen/byte 0 256)]
              (let [stored (resources/blob-put!
                            service database nil
                            {:id "property-object"
                             :name "property.bin"
                             :content-type "application/octet-stream"
                             :content-base64 (base64 values)})
                    loaded (resources/blob-get service database "property-object")]
                (and (= (count values) (:size stored) (:size loaded))
                     (= (:sha256 stored) (:sha256 loaded))
                     (= "property.bin" (:name loaded))
                     (= (vec (byte-array values))
                        (vec (.decode (Base64/getDecoder)
                                      ^String (:content-base64 loaded))))))))]
        (is (:pass? result) (pr-str (select-keys result [:num-tests :seed :fail]))))
      (finally
        (fs/delete-tree! root)))))

(deftest pbt-12-events-never-cross-session-or-runtime-subscriptions
  (let [sent (atom [])
        hub (websocket/create-hub
             {:send-fn (fn [channel frame]
                         (swap! sent conj [channel (websocket/decode-message frame)])
                         true)
              :close-fn (constantly true)
              :runtime-bundle-fn (fn [_] {:runtime-version 4})})
        session-a (random-uuid)
        session-b (random-uuid)
        eligible (Object.)
        stale (Object.)
        other (Object.)]
    (try
      (doseq [[channel session version] [[eligible session-a 4]
                                         [stale session-a 3]
                                         [other session-b 4]]]
        (websocket/open! hub channel)
        (websocket/receive!
         hub channel
         (websocket/encode-message
          (protocol/envelope {:session-id session
                              :request-id (random-uuid)
                              :runtime-version version
                              :type :session/subscribe
                              :payload {:tab-id (random-uuid)
                                        :current-version version}}))))
      (let [result
            (tc/quick-check
             1000
             (prop/for-all
              [selected-session (gen/elements [session-a session-b])
               version (gen/choose 0 6)
               payload gen/small-integer]
              (do
                (reset! sent [])
                (let [delivered (websocket/publish-product-event!
                                 hub selected-session version :scores/changed
                                 {:value payload})
                      expected (if (and (= session-a selected-session)
                                        (= 4 version))
                                 1
                                 (if (and (= session-b selected-session)
                                          (= 4 version))
                                   1
                                   0))]
                  (and (= expected delivered (count @sent))
                       (every? #(= selected-session (get-in % [1 :session-id]))
                               @sent)
                       (every? #(= version (get-in % [1 :runtime-version]))
                               @sent))))))]
        (is (:pass? result) (pr-str (select-keys result [:num-tests :seed :fail]))))
      (finally
        (websocket/stop! hub)))))

(def job-operation
  (gen/tuple (gen/elements [:schedule :cancel :claim :fail :complete :restore])
             (gen/choose 0 19)))

(deftest pbt-13-job-sequences-preserve-idempotency-attempts-and-restore-terminality
  (let [root (test-root "ppp-job-property")]
    (try
      (let [clock (atom (Instant/parse "2026-07-16T00:00:00Z"))
            database (sqlite/init! (.resolve root "jobs.sqlite"))
            service (service clock)
            result
            (tc/quick-check
             1
             (prop/for-all
              [operations (gen/vector job-operation 1000)]
              (let [ids (atom {})
                    claimed (atom nil)]
                (doseq [[operation key] operations]
                  (swap! clock #(.plusSeconds ^Instant % 20))
                  (case operation
                    :schedule
                    (let [job (resources/schedule-job!
                               service database :property/run {:key key}
                               {:idempotency-key (str "key-" key)
                                :max-attempts 3})]
                      (if-let [known (get @ids key)]
                        (assert (= known (:id job)))
                        (swap! ids assoc key (:id job))))

                    :cancel
                    (when-let [id (get @ids key)]
                      (when (= :pending (:status (resources/job-status service database id)))
                        (resources/cancel-job! service database id)))

                    :claim
                    (when-not @claimed
                      (reset! claimed
                              (resources/claim-next-job! service database
                                                         #{:property/run})))

                    :fail
                    (when-let [job @claimed]
                      (resources/fail-job! service database (:id job)
                                           :property/retry)
                      (reset! claimed nil))

                    :complete
                    (when-let [job @claimed]
                      (resources/complete-job! service database (:id job) {:ok true})
                      (reset! claimed nil))

                    :restore
                    (do
                      (resources/cancel-operational-jobs! service database)
                      (reset! claimed nil))))
                (resources/cancel-operational-jobs! service database)
                (let [rows (sqlite/execute!
                            database
                            ["SELECT handler, idempotency_key, status, attempt,
                                     max_attempts FROM _ppp_jobs"])]
                  (and (= (count rows)
                          (count (distinct (map (juxt :handler :idempotency_key)
                                                rows))))
                       (every? #(<= 0 (:attempt %) (:max_attempts %) 5) rows)
                       (every? #(contains? #{"completed" "failed" "cancelled"}
                                           (:status %))
                               rows)
                       (nil? (resources/claim-next-job! service database
                                                        #{:property/run})))))))]
        (is (:pass? result) (pr-str (select-keys result [:num-tests :seed :fail]))))
      (finally
        (fs/delete-tree! root)))))

(deftest pbt-14-ingress-signatures-have-no-acceptance-bypass
  (let [root (test-root "ppp-ingress-property")]
    (try
      (let [configuration (.resolve root "connectors.edn")
            _ (fs/atomic-write-string!
               configuration
               (pr-str {:ingress-verifiers
                        {:hook {:description "Property verifier"
                                :algorithm :hmac-sha256
                                :header "x-webhook-signature"
                                :prefix "sha256="
                                :secret {:env "FAKE_HOOK_SECRET"}}}}))
            service (outbound/create-service
                     {:connectors-file configuration
                      :outbound-env #(when (= "FAKE_HOOK_SECRET" %) "property-secret")})
            result
            (tc/quick-check
             1000
             (prop/for-all
              [values (gen/vector gen/byte 0 512)
               valid? gen/boolean]
              (let [body (byte-array values)
                    correct (signed "property-secret" body)
                    supplied (if valid? correct (str correct "00"))
                    accepted?
                    (try
                      (outbound/verify-ingress! service :hook
                                                {"x-webhook-signature" supplied}
                                                body)
                      true
                      (catch clojure.lang.ExceptionInfo _ false))]
                (= valid? accepted?))))]
        (is (:pass? result) (pr-str (select-keys result [:num-tests :seed :fail])))
        (is (= [{:alias :hook
                 :description "Property verifier"
                 :algorithm :hmac-sha256
                 :header "x-webhook-signature"
                 :prefix "sha256="}]
               (outbound/ingress-catalog service))))
      (finally
        (fs/delete-tree! root)))))

(deftest pbt-15-search-is-bounded-deterministic-and-session-local
  (let [root (test-root "ppp-search-property")]
    (try
      (let [service (service)
            database-a (sqlite/init! (.resolve root "a.sqlite"))
            database-b (sqlite/init! (.resolve root "b.sqlite"))
            words ["한글" "space" "robot" "협업" "design"]]
        (doseq [id (range 50)]
          (resources/search-upsert!
           service database-a :projects (str "a-" id)
           {:text (str (nth words (mod id (count words))) " project " id)
            :metadata {:session :a :id id}
            :vector [(double (mod id 3)) (double (mod id 5))]})
          (resources/search-upsert!
           service database-b :projects (str "b-" id)
           {:text (str (nth words (mod id (count words))) " private " id)
            :metadata {:session :b :id id}
            :vector [(double (mod id 3)) (double (mod id 5))]}))
        (let [result
              (tc/quick-check
               1000
               (prop/for-all
                [word (gen/elements (conj words ""))
                 x (gen/choose 0 2)
                 y (gen/choose 0 4)
                 limit (gen/choose 1 20)]
                (let [options {:vector [(double x) (double y)] :limit limit}
                      first-result (resources/search-query service database-a
                                                           :projects word options)
                      second-result (resources/search-query service database-a
                                                            :projects word options)]
                  (and (= first-result second-result)
                       (<= (count first-result) limit)
                       (every? #(str/starts-with? (:id %) "a-")
                               first-result)
                       (every? #(= :a (get-in % [:metadata :session]))
                               first-result)))))]
          (is (:pass? result)
              (pr-str (select-keys result [:num-tests :seed :fail])))))
      (finally
        (fs/delete-tree! root)))))
