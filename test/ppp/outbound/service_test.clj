(ns ppp.outbound.service-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ppp.outbound.service :as service]
            [ppp.util.fs :as fs])
  (:import (java.net InetAddress)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def public-address (InetAddress/getByName "93.184.216.34"))
(def private-address (InetAddress/getByName "10.0.0.9"))

(def connector-config
  {:connectors
   {:issues
    {:description "Read and create issue records"
     :base-url "https://api.example.com"
     :allow {:methods #{:get :post}
             :path-prefixes ["/v1/issues"]
             :query-parameters ["state" "limit"]
             :body "JSON issue object"}
     :secret-headers {"Authorization" {:env "EXAMPLE_TOKEN"}}
     :timeout-ms 4000
     :response-limit-bytes 4096}}})

(defn- write-config-file
  [root value]
  (let [path (.resolve root "connectors.edn")]
    (Files/writeString path (pr-str value) StandardCharsets/UTF_8
                       (make-array java.nio.file.OpenOption 0))
    path))

(defn- test-config-file
  [root]
  (write-config-file root connector-config))

(defn- exception
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo cause
      cause)))

(deftest connector-catalog-is-useful-and-secret-free
  (let [root (Files/createTempDirectory "ppp-connectors"
                                        (make-array FileAttribute 0))]
    (try
      (let [outbound
            (service/create-service
             {:connectors-file (test-config-file root)
              :outbound-resolver (constantly [public-address])
              :outbound-transport (constantly {:status 200 :headers [] :body "ok"})
              :outbound-env (constantly "super-secret-value")})
            catalog (service/catalog outbound)
            serialized (pr-str catalog)]
        (is (= [{:alias :issues
                 :description "Read and create issue records"
                 :methods [:get :post]
                 :path-prefixes ["/v1/issues"]
                 :query-parameters ["state" "limit"]
                 :body "JSON issue object"}]
               catalog))
        (is (not (re-find #"EXAMPLE_TOKEN|super-secret-value|api\.example\.com"
                          serialized))))
      (finally
        (fs/delete-tree! root)))))

(deftest connector-validates-before-injecting-and-transmits-after-validation
  (let [root (Files/createTempDirectory "ppp-connectors-order"
                                        (make-array FileAttribute 0))]
    (try
      (let [events (atom [])
            outbound
            (service/create-service
             {:connectors-file (test-config-file root)
              :outbound-resolver
              (fn [host]
                (swap! events conj [:resolved host])
                [public-address])
              :outbound-env
              (fn [env-name]
                (swap! events conj [:credential env-name])
                "Bearer secret")
              :outbound-transport
              (fn [{:keys [headers url]}]
                (swap! events conj [:transport url (get headers "authorization")])
                {:status 200
                 :headers [["Content-Type" "application/json"]
                           ["Set-Cookie" "never expose"]]
                 :body "{}"})})
            result
            (service/connector-request!
             outbound :issues
             {:method :get
              :path "/v1/issues/42"
              :query {:state "open" :limit 10}
              :headers {:accept "application/json"}})]
        (is (= 200 (:status result)))
        (is (= {"content-type" "application/json"} (:headers result)))
        (is (= [[:resolved "api.example.com"]
                [:credential "EXAMPLE_TOKEN"]
                [:transport
                 "https://api.example.com/v1/issues/42?state=open&limit=10"
                 "Bearer secret"]]
               @events)))
      (finally
        (fs/delete-tree! root)))))

(deftest forbidden-targets-never-read-the-credential
  (let [root (Files/createTempDirectory "ppp-connectors-forbidden"
                                        (make-array FileAttribute 0))]
    (try
      (let [credential-reads (atom 0)
            transport-calls (atom 0)
            outbound
            (service/create-service
             {:connectors-file (test-config-file root)
              :outbound-resolver (constantly [private-address])
              :outbound-env (fn [_]
                              (swap! credential-reads inc)
                              "secret")
              :outbound-transport (fn [_] (swap! transport-calls inc))})
            cause
            (exception
             #(service/connector-request!
               outbound :issues
               {:method :get :path "/v1/issues"}))]
        (is (= :outbound/address-forbidden (:code (ex-data cause))))
        (is (zero? @credential-reads))
        (is (zero? @transport-calls)))
      (finally
        (fs/delete-tree! root)))))

(deftest connector-query-and-body-stay-inside-the-declared-contract
  (let [root (Files/createTempDirectory "ppp-connectors-contract"
                                        (make-array FileAttribute 0))]
    (try
      (let [credential-reads (atom 0)
            resolver-calls (atom 0)
            outbound
            (service/create-service
             {:connectors-file (test-config-file root)
              :outbound-resolver (fn [_]
                                   (swap! resolver-calls inc)
                                   [public-address])
              :outbound-env (fn [_]
                              (swap! credential-reads inc)
                              "secret")
              :outbound-transport
              (constantly {:status 200 :headers [] :body "ok"})})]
        (doseq [request [{:method :get
                          :path "/v1/issues"
                          :query {:admin true}}
                         {:method :get
                          :path "/v1/issues"
                          :query {:state "open" "state" "closed"}}]]
          (is (contains? #{:connector/query-forbidden :connector/query-invalid}
                         (:code (ex-data
                                 (exception
                                  #(service/connector-request!
                                    outbound :issues request)))))))
        (is (zero? @resolver-calls))
        (is (zero? @credential-reads)))

      (let [without-body (update-in connector-config
                                    [:connectors :issues :allow]
                                    dissoc :body)
            outbound
            (service/create-service
             {:connectors-file (write-config-file root without-body)
              :outbound-resolver (constantly [public-address])
              :outbound-env (constantly "secret")
              :outbound-transport
              (constantly {:status 200 :headers [] :body "ok"})})]
        (is (= :connector/body-forbidden
               (:code
                (ex-data
                 (exception
                  #(service/connector-request!
                    outbound :issues
                    {:method :post :path "/v1/issues" :body "{}"})))))))
      (finally
        (fs/delete-tree! root)))))

(deftest redirects-must-remain-inside-the-connector-contract
  (let [root (Files/createTempDirectory "ppp-connectors-redirect"
                                        (make-array FileAttribute 0))]
    (try
      (let [credential-reads (atom 0)
            resolved (atom [])
            outbound
            (service/create-service
             {:connectors-file (test-config-file root)
              :outbound-resolver
              (fn [host]
                (swap! resolved conj host)
                [public-address])
              :outbound-env (fn [_]
                              (swap! credential-reads inc)
                              "secret")
              :outbound-transport
              (constantly
               {:status 302
                :headers [["Location" "https://evil.example/collect"]]
                :body ""})})
            cause
            (exception
             #(service/connector-request!
               outbound :issues
               {:method :get :path "/v1/issues"}))]
        (is (= :connector/target-forbidden (:code (ex-data cause))))
        (is (= ["api.example.com"] @resolved))
        (is (= 1 @credential-reads))
        (is (not (str/includes? (pr-str (ex-data cause)) "secret"))))
      (finally
        (fs/delete-tree! root)))))

(deftest connector-prefix-normalization-fails-closed
  (let [root (Files/createTempDirectory "ppp-connectors-prefix"
                                        (make-array FileAttribute 0))]
    (try
      (let [outbound
            (service/create-service
             {:connectors-file (test-config-file root)
              :outbound-resolver (constantly [public-address])
              :outbound-transport
              (constantly {:status 200 :headers [] :body "ok"})
              :outbound-env (constantly "secret")})]
        (doseq [path ["/v1/issues-admin" "/v1/issues/../admin"
                      "/v1/issues/%2e%2e/admin"]]
          (is (some? (exception
                      #(service/connector-request!
                        outbound :issues {:method :get :path path})))
              path))
        (testing "generated code cannot substitute a raw URL"
          (is (= :connector/url-forbidden
                 (:code
                  (ex-data
                   (exception
                    #(service/connector-request!
                      outbound :issues
                      {:method :get
                       :path "/v1/issues"
                       :url "https://evil.example"}))))))))
      (finally
        (fs/delete-tree! root)))))

(deftest ambiguous-or-routing-capable-connector-config-is-rejected
  (let [root (Files/createTempDirectory "ppp-connectors-config"
                                        (make-array FileAttribute 0))]
    (try
      (doseq [value
              [(assoc connector-config :unknown true)
               {:connectors
                {:issues (assoc-in (get-in connector-config
                                           [:connectors :issues])
                                   [:secret-headers "Forwarded"]
                                   {:env "FORWARD_SECRET"})}}
               {:connectors
                {:issues (assoc (get-in connector-config [:connectors :issues])
                                :unknown true)}}
               {:connectors
                {:issues (get-in connector-config [:connectors :issues])
                 "issues" (get-in connector-config [:connectors :issues])}}]]
        (let [cause
              (exception
               #(service/create-service
                 {:connectors-file (write-config-file root value)}))]
          (is (= :connector/config-invalid (:code (ex-data cause)))
              (pr-str value))))
      (finally
        (fs/delete-tree! root)))))
