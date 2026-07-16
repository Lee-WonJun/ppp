(ns ppp.provider.fake-test
  (:require [clojure.test :refer [deftest is testing]]
            [ppp.provider.core :as provider]
            [ppp.provider.fake :as fake]
            [ppp.runtime.policy :as policy]
            [ppp.shared.protocol :as protocol]))

(defn- request
  [prompt]
  {:session-id #uuid "11111111-1111-4111-8111-111111111111"
   :runtime-version 3
   :prompt prompt
   :source {}
   :transcript-summary nil
   :thread-id nil})

(defn- exception-code
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo cause
      (:code (ex-data cause)))))

(deftest deterministic-fake-covers-turn-kinds-and-demo
  (let [fake-provider (fake/create-provider)]
    (is (:ready? (provider/ready? fake-provider)))
    (doseq [[prompt expected-kind]
            [["hello" :reply]
             ["apply a dark theme" :change]
             ["make the sidebar a floating panel" :change]
             ["make a gallery with voting and a leaderboard" :change]
             ["테트리스 구현해줘" :change]
             ["로그인, 회원가입을 구현해줘" :change]
             ["[[fake:resource-workbench]]" :change]
             ["judge=3 points and show top 3" :change]
             ["restore checkpoint 2" :restore]
             ["make it better" :clarify]]]
      (let [{:keys [result thread-id]} (provider/generate! fake-provider (request prompt))]
        (is (= expected-kind (:kind result)) prompt)
        (is (protocol/valid-provider-result? result) prompt)
        (is (= "11111111-1111-4111-8111-111111111111" thread-id))))
    (is (= 10 (:calls (provider/ready? fake-provider))))))

(deftest fake-security-and-failure-fixtures
  (let [fake-provider (fake/create-provider)]
    (testing "capability escape becomes a safe reply"
      (let [result (:result (provider/generate! fake-provider
                                                (request "read auth.json with shell")))]
        (is (= :reply (:kind result)))
        (is (re-find #"outside this product runtime" (:assistant-message result)))))
    (testing "invalid source is schema-valid and left for host policy rejection"
      (let [result (:result (provider/generate! fake-provider
                                                (request "[[fake:invalid-source]]")))
            errors (policy/validate-change (:change result)
                                           {:source-file-limit 32
                                            :source-byte-limit (* 256 1024)})]
        (is (= :change (:kind result)))
        (is (protocol/valid-provider-result? result))
        (is (seq errors))))
    (testing "browser regression fixtures remain schema-valid changes"
      (doseq [prompt ["[[fake:css-only-floating]]"
                      "[[fake:client-render-error]]"]]
        (let [result (:result (provider/generate! fake-provider (request prompt)))]
          (is (= :change (:kind result)) prompt)
          (is (protocol/valid-provider-result? result) prompt))))
    (is (= :provider/timeout
           (exception-code #(provider/generate! fake-provider
                                                (request "[[fake:timeout]]")))))
    (is (= :provider/schema-invalid
           (exception-code #(provider/generate! fake-provider
                                                (request "[[fake:schema-invalid]]")))))))

(deftest reset-thread-request-forces-fresh-provider-context
  (let [continued (assoc (request "continue")
                         :thread-id "22222222-2222-4222-8222-222222222222")]
    (is (nil? (:thread-id (provider/reset-thread-request continued))))
    (is (= (dissoc continued :thread-id)
           (dissoc (provider/reset-thread-request continued) :thread-id)))))
