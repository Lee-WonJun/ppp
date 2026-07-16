(ns ppp.property.http-policy-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ppp.outbound.client :as client]
            [ppp.outbound.policy :as policy])
  (:import (java.net InetAddress)))

(def byte-gen (gen/choose 0 255))

(def blocked-ipv4-gen
  (gen/one-of
   [(gen/fmap (fn [[a b c]] (str "10." a "." b "." c))
              (gen/tuple byte-gen byte-gen byte-gen))
    (gen/fmap (fn [[a b c]] (str "127." a "." b "." c))
              (gen/tuple byte-gen byte-gen byte-gen))
    (gen/fmap (fn [[a b]] (str "169.254." a "." b))
              (gen/tuple byte-gen byte-gen))
    (gen/fmap (fn [[a b c]] (str "172." (+ 16 a) "." b "." c))
              (gen/tuple (gen/choose 0 15) byte-gen byte-gen))
    (gen/fmap (fn [[a b]] (str "192.168." a "." b))
              (gen/tuple byte-gen byte-gen))
    (gen/fmap (fn [[a b c]] (str "100." (+ 64 a) "." b "." c))
              (gen/tuple (gen/choose 0 63) byte-gen byte-gen))
    (gen/elements ["0.0.0.0" "192.0.2.1" "198.18.0.1"
                   "198.51.100.1" "203.0.113.1"
                   "224.0.0.1" "255.255.255.255"])]))

(def public-ipv4-gen
  (gen/fmap (fn [[prefix a b c]]
              (str prefix "." a "." b "." c))
            (gen/tuple (gen/elements [1 8 9 11 23 45 93 151])
                       byte-gen byte-gen byte-gen)))

(defn- exception-code
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo cause
      (:code (ex-data cause)))))

(deftest generated-addresses-and-mixed-dns-sets-fail-closed
  (let [address-property
        (tc/quick-check
         1000
         (prop/for-all [blocked blocked-ipv4-gen
                        public public-ipv4-gen]
                       (and (false? (policy/public-address? blocked))
                            (true? (policy/public-address? public))))
         :seed 90061)
        dns-property
        (tc/quick-check
         1000
         (prop/for-all [blocked blocked-ipv4-gen
                        public-values (gen/vector public-ipv4-gen 0 5)
                        blocked-index (gen/choose 0 5)]
                       (let [values (vec (map #(InetAddress/getByName %)
                                              public-values))
                             blocked-address (InetAddress/getByName blocked)
                             index (min blocked-index (count values))
                             answers (vec (concat (subvec values 0 index)
                                                  [blocked-address]
                                                  (subvec values index)))
                             transport-count (atom 0)
                             http
                             (client/create-client
                              {:resolver (constantly answers)
                               :transport (fn [_]
                                            (swap! transport-count inc)
                                            {:status 200 :headers [] :body "ok"})})]
                         (and (= :outbound/address-forbidden
                                 (exception-code
                                  #(client/request!
                                    http {:url "https://example.com"})))
                              (zero? @transport-count))))
         :seed 90062)]
    (is (:pass? address-property)
        (pr-str (dissoc address-property :result-data)))
    (is (:pass? dns-property)
        (pr-str (dissoc dns-property :result-data)))))

(deftest generated-url-schemes-never-bypass-https
  (let [result
        (tc/quick-check
         1000
         (prop/for-all [scheme (gen/elements ["https" "http" "ftp" "file" "ws"])
                        suffix (gen/choose 1 999999)]
                       (let [url (str scheme "://example.com/resource/" suffix)
                             code (exception-code #(policy/parse-url! url))]
                         (if (= scheme "https")
                           (nil? code)
                           (= :outbound/https-required code))))
         :seed 90063)]
    (is (:pass? result) (pr-str (dissoc result :result-data)))))

(deftest generated-redirect-chains-are-bounded-and-revalidated
  (let [result
        (tc/quick-check
         1000
         (prop/for-all [private-flags (gen/vector gen/boolean 1 8)]
                       (let [hosts
                             (mapv (fn [index private?]
                                     (str (if private? "private-" "public-")
                                          index ".example"))
                                   (range)
                                   private-flags)
                             http
                             (client/create-client
                              {:resolver
                               (fn [host]
                                 [(InetAddress/getByName
                                   (if (str/starts-with? host "private-")
                                     "10.0.0.1"
                                     "93.184.216.34"))])
                               :transport
                               (fn [{:keys [host]}]
                                 (let [index (parse-long
                                              (second (re-find #"-(\d+)\."
                                                               host)))
                                       next-host (get hosts (inc index))]
                                   (if next-host
                                     {:status 302
                                      :headers [["Location"
                                                 (str "https://" next-host "/")]]
                                      :body ""}
                                     {:status 200 :headers [] :body "ok"})))})
                             expected-success?
                             (and (not-any? true? private-flags)
                                  (<= (dec (count hosts)) policy/max-redirects))
                             code
                             (exception-code
                              #(client/request!
                                http {:url (str "https://" (first hosts) "/")}))]
                         (= expected-success? (nil? code))))
         :seed 90064)]
    (is (:pass? result) (pr-str (dissoc result :result-data)))))
