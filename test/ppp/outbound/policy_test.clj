(ns ppp.outbound.policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [ppp.outbound.policy :as policy]))

(defn- exception-code
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo cause
      (:code (ex-data cause)))))

(deftest public-address-policy-covers-security-boundaries
  (doseq [address ["0.0.0.0" "10.0.0.1" "100.64.0.1" "127.0.0.1"
                   "169.254.169.254" "172.31.255.255" "192.0.2.1"
                   "192.168.1.1" "198.18.0.1" "198.51.100.1"
                   "203.0.113.1" "224.0.0.1" "255.255.255.255"
                   "::" "::1" "fc00::1" "fe80::1" "ff02::1"
                   "2001:db8::1" "2002::1" "3fff::1"]]
    (is (false? (policy/public-address? address)) address))
  (doseq [address ["1.1.1.1" "8.8.8.8" "93.184.216.34"
                   "2606:4700:4700::1111" "2606:4700:4700::1001"]]
    (is (true? (policy/public-address? address)) address)))

(deftest outbound-url-contract-is-https-only-and-explicit
  (is (= "example.com" (:host (policy/parse-url! "https://example.com/v1?q=1"))))
  (doseq [[url code]
          [["http://example.com" :outbound/https-required]
           ["https://user@example.com" :outbound/userinfo-forbidden]
           ["https://example.com/#secret" :outbound/fragment-forbidden]
           ["https://example.com:8443/" :outbound/port-forbidden]
           ["https://example.com./" :outbound/invalid-host]
           ["https://example.com/a b" :outbound/invalid-url]]]
    (is (= code (exception-code #(policy/parse-url! url))) url))
  (is (= 8443
         (:port (policy/parse-url! "https://example.com:8443/"
                                   {:allowed-ports #{8443}})))))

(deftest request-headers-are-allowlisted-not-merely-denied
  (is (= {"accept" "application/json" "content-type" "application/json"}
         (policy/normalize-public-headers!
          {:Accept "application/json" "Content-Type" "application/json"})))
  (doseq [header ["Authorization" "Cookie" "Host" "Connection"
                  "Forwarded" "X-Forwarded-For" "Proxy-Authorization"]]
    (is (= :outbound/header-forbidden
           (exception-code
            #(policy/normalize-public-headers! {header "do-not-send"})))
        header))
  (is (= :outbound/header-forbidden
         (exception-code
          #(policy/normalize-public-headers! {"X-Arbitrary" "value"})))))

(deftest connector-secrets-may-authenticate-but-never-route
  (is (= {"authorization" "Bearer fixture" "cookie" "session=fixture"}
         (policy/normalize-secret-headers!
          {"Authorization" "Bearer fixture" "Cookie" "session=fixture"})))
  (doseq [header ["Host" "Forwarded" "X-Forwarded-For" "Via"
                  "Proxy-Authorization" "Connection" "Transfer-Encoding"]]
    (is (= :connector/config-invalid
           (exception-code
            #(policy/normalize-secret-headers! {header "unsafe"})))
        header)))

(deftest connector-path-policy-prevents-prefix-and-encoding-escapes
  (doseq [path ["/v1/issues/../admin"
                "/v1/issues/%2e%2e/admin"
                "/v1/issues/%252e%252e/admin"
                "/v1/issues%2f..%2fadmin"
                "/v1/issues\\..\\admin"
                "v1/issues"
                "/v1/issues?admin=true"]]
    (is (= :connector/path-invalid
           (exception-code #(policy/assert-safe-connector-path! path)))
        path))
  (is (policy/path-within-prefix? "/v1/issues/42" "/v1/issues"))
  (is (policy/path-within-prefix? "/v1/issues" "/v1/issues"))
  (is (false? (policy/path-within-prefix? "/v1/issues-admin" "/v1/issues"))))

(deftest limits-are-hard-upper-bounds
  (testing "default values match the product contract"
    (is (= 5000 (policy/bounded-timeout! nil)))
    (is (= (* 1024 1024) (policy/bounded-response-limit! nil))))
  (is (= :outbound/timeout-invalid
         (exception-code #(policy/bounded-timeout! 5001))))
  (is (= :outbound/response-limit-invalid
         (exception-code #(policy/bounded-response-limit! (inc (* 1024 1024)))))))
