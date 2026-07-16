(ns ppp.outbound.client-test
  (:require [clojure.test :refer [deftest is]]
            [ppp.outbound.client :as client])
  (:import (java.net InetAddress)))

(def public-address (InetAddress/getByName "93.184.216.34"))
(def private-address (InetAddress/getByName "10.0.0.8"))

(defn- exception
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo cause
      cause)))

(deftest request-normalizes-output-and-pins-resolved-addresses
  (let [captured (atom nil)
        http (client/create-client
              {:resolver (constantly [public-address])
               :transport
               (fn [request]
                 (reset! captured request)
                 {:status 200
                  :headers [["Content-Type" "application/json"]
                            ["Set-Cookie" "secret=never-return"]]
                  :body "{\"ok\":true}"})})
        response (client/request! http {:url "https://example.com/data"
                                        :headers {:accept "application/json"}})]
    (is (= 200 (:status response)))
    (is (= {"content-type" "application/json"} (:headers response)))
    (is (= "{\"ok\":true}" (:body response)))
    (is (= [public-address] (:addresses @captured)))
    (is (= "example.com" (:host @captured)))
    (is (= :get (:method @captured)))))

(deftest mixed-dns-answer-fails-before-the-transport
  (let [transport-calls (atom 0)
        http (client/create-client
              {:resolver (constantly [public-address private-address])
               :transport (fn [_] (swap! transport-calls inc))})
        cause (exception #(client/request! http {:url "https://mixed.example/"}))]
    (is (= :outbound/address-forbidden (:code (ex-data cause))))
    (is (zero? @transport-calls))))

(deftest dns-resolution-is-inside-the-request-time-budget
  (let [transport-calls (atom 0)
        http (client/create-client
              {:resolver (fn [_]
                           (Thread/sleep 250)
                           [public-address])
               :transport (fn [_] (swap! transport-calls inc))})
        started (System/nanoTime)
        cause
        (exception
         #(client/request! http {:url "https://slow-dns.example/"
                                 :timeout-ms 40}))
        elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)]
    (is (= :outbound/dns-timeout (:code (ex-data cause))))
    (is (< elapsed-ms 1000.0))
    (is (zero? @transport-calls))))

(deftest every-redirect-is-reparsed-and-reresolved
  (let [resolved (atom [])
        transported (atom [])
        http (client/create-client
              {:resolver
               (fn [host]
                 (swap! resolved conj host)
                 (if (= host "private.example")
                   [private-address]
                   [public-address]))
               :transport
               (fn [{:keys [url]}]
                 (swap! transported conj url)
                 {:status 302
                  :headers [["Location" "https://private.example/admin"]]
                  :body ""})})
        cause (exception #(client/request! http {:url "https://public.example/start"}))]
    (is (= :outbound/address-forbidden (:code (ex-data cause))))
    (is (= ["public.example" "private.example"] @resolved))
    (is (= ["https://public.example/start"] @transported))))

(deftest redirect-count-is-bounded
  (let [calls (atom 0)
        http (client/create-client
              {:resolver (constantly [public-address])
               :transport
               (fn [_]
                 (let [index (swap! calls inc)]
                   {:status 302
                    :headers [["Location" (str "https://example.com/" index)]]
                    :body ""}))})
        cause (exception #(client/request! http {:url "https://example.com/0"}))]
    (is (= :outbound/redirect-limit (:code (ex-data cause))))
    (is (= 6 @calls))))

(deftest host-hooks-run-in-security-order-on-every-hop
  (let [events (atom [])
        http (client/create-client
              {:resolver (fn [host]
                           (swap! events conj [:resolve host])
                           [public-address])
               :transport
               (fn [{:keys [url headers]}]
                 (swap! events conj [:transport url (contains? headers "authorization")])
                 (if (= url "https://example.com/one")
                   {:status 307
                    :headers [["Location" "/two"]]
                    :body ""}
                   {:status 200 :headers [] :body "ok"}))})]
    (is (= 200
           (:status
            (client/request!
             http
             {:url "https://example.com/one"}
             {:authorize-uri! #(swap! events conj [:authorize (:host %)])
              :headers-after-validation
              #(do (swap! events conj [:inject (:host %)])
                   {"authorization" "secret"})}))))
    (is (= [[:authorize "example.com"]
            [:resolve "example.com"]
            [:inject "example.com"]
            [:transport "https://example.com/one" true]
            [:authorize "example.com"]
            [:resolve "example.com"]
            [:inject "example.com"]
            [:transport "https://example.com/two" true]]
           @events))))
