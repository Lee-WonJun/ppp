(ns ppp.runtime.auth-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ppp.runtime.auth :as auth]
            [ppp.runtime.sqlite :as sqlite]
            [ppp.util.fs :as fs])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)))

(def test-config
  {:cookie-secret "product-auth-test-secret-with-more-than-thirty-two-characters"
   :cookie-secure? false
   :product-auth-session-seconds 3600})

(defn- test-root
  []
  (Files/createTempDirectory "ppp-auth-test" (make-array FileAttribute 0)))

(defn- deterministic-random
  []
  (let [counter (atom 0)]
    (fn [size]
      (let [seed (swap! counter inc)]
        (byte-array (map #(unchecked-byte (+ seed %)) (range size)))))))

(defn- test-service
  ([] (test-service (atom (Instant/parse "2026-07-16T00:00:00Z"))))
  ([clock]
   (auth/create-service
    test-config
    {:now-fn #(deref clock)
     :random-bytes-fn (deterministic-random)
     :hash-options {:memory-kib 7168 :iterations 1 :parallelism 1}
     :allow-weak-test-parameters? true})))

(defn- exception
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo cause
      cause)))

(deftest registration-login-and-revocation-use-only-public-claims
  (let [root (test-root)]
    (try
      (let [database (sqlite/init! (.resolve root "auth.sqlite"))
            service (test-service)
            session-id (random-uuid)
            registration (auth/register! service database session-id
                                         {:identifier "  Alice  "
                                          :password "correct horse"})
            token (get-in registration [:effect :token])
            stored (sqlite/execute-one!
                    database
                    ["SELECT identifier_key, password_hash FROM _ppp_auth_users"])]
        (is (= "Alice" (get-in registration [:user :identifier])))
        (is (= "alice" (:identifier_key stored)))
        (is (str/starts-with? (:password_hash stored) "$argon2id$v=19$m="))
        (is (not (str/includes? (:password_hash stored) "correct horse")))
        (is (= (:user registration)
               (:user (auth/resolve-context! service database session-id token))))
        (is (nil? (:user (auth/resolve-context! service database (random-uuid)
                                                token))))

        (let [login (auth/login! service database session-id
                                 {:identifier "ALICE"
                                  :password "correct horse"})]
          (is (= (:user registration) (:user login)))
          (auth/logout! service database
                        (auth/resolve-context! service database session-id
                                               (get-in login [:effect :token])))
          (is (nil? (:user (auth/resolve-context! service database session-id
                                                  (get-in login [:effect :token]))))))
        (is (nil? (:password-hash (:user registration))))
        (is (nil? (:token (:user registration)))))
      (finally
        (fs/delete-tree! root)))))

(deftest normalized-identifiers-are-unique-and-credential-failures-match
  (let [root (test-root)]
    (try
      (let [database (sqlite/init! (.resolve root "unique.sqlite"))
            service (test-service)
            session-id (random-uuid)]
        (auth/register! service database session-id
                        {:identifier "Designer" :password "strong passphrase"})
        (is (= :auth/identifier-taken
               (:code (ex-data
                       (exception #(auth/register! service database session-id
                                                   {:identifier " designer "
                                                    :password "another strong pass"}))))))
        (let [unknown (exception #(auth/login! service database session-id
                                               {:identifier "nobody"
                                                :password "wrong password"}))
              wrong (exception #(auth/login! service database session-id
                                             {:identifier "designer"
                                              :password "wrong password"}))]
          (is (= :auth/invalid-credentials (:code (ex-data unknown))))
          (is (= (:code (ex-data unknown)) (:code (ex-data wrong))))
          (is (= (.getMessage unknown) (.getMessage wrong)))))
      (finally
        (fs/delete-tree! root)))))

(deftest failed-logins-are-bounded-after-the-product-transaction-rolls-back
  (let [root (test-root)]
    (try
      (let [database (sqlite/init! (.resolve root "attempts.sqlite"))
            service (test-service)
            session-id (random-uuid)]
        (auth/register! service database session-id
                        {:identifier "judge" :password "correct password"})
        (dotimes [_ 5]
          (let [cause (exception #(auth/login! service database session-id
                                               {:identifier "judge"
                                                :password "wrong password"}))]
            (auth/record-login-failure! service database
                                        (:auth/login-attempt (ex-data cause)))))
        (is (= :auth/temporarily-locked
               (:code (ex-data
                       (exception #(auth/login! service database session-id
                                                {:identifier "judge"
                                                 :password "correct password"})))))))
      (finally
        (fs/delete-tree! root)))))

(deftest password-change-and-restore-revoke-live-tokens
  (let [root (test-root)]
    (try
      (let [database (sqlite/init! (.resolve root "rotation.sqlite"))
            service (test-service)
            session-id (random-uuid)
            registration (auth/register! service database session-id
                                         {:identifier "owner"
                                          :password "old password"})
            old-token (get-in registration [:effect :token])
            context (auth/resolve-context! service database session-id old-token)
            changed (auth/change-password! service database session-id context
                                           (:token-hash context)
                                           {:current-password "old password"
                                            :new-password "new password"})
            new-token (get-in changed [:effect :token])]
        (is (nil? (:user (auth/resolve-context! service database session-id
                                                old-token))))
        (is (= "owner" (get-in (auth/resolve-context! service database session-id
                                                      new-token)
                               [:user :identifier])))
        (auth/clear-operational-state! database)
        (is (= 1 (count (auth/identity-state database))))
        (is (nil? (:user (auth/resolve-context! service database session-id
                                                new-token)))))
      (finally
        (fs/delete-tree! root)))))

(deftest production-argon2id-floor-is-encoded-in-stored-credentials
  (let [root (test-root)]
    (try
      (let [database (sqlite/init! (.resolve root "production-hash.sqlite"))
            service (auth/create-service test-config)
            session-id (random-uuid)]
        (auth/register! service database session-id
                        {:identifier "production-user"
                         :password "production password"})
        (is (str/starts-with?
             (:password_hash
              (sqlite/execute-one! database
                                   ["SELECT password_hash FROM _ppp_auth_users"]))
             "$argon2id$v=19$m=19456,t=2,p=1$")))
      (finally
        (fs/delete-tree! root)))))

(deftest session-cookie-scope-and-identifier-normalization-properties
  (let [cookie-property
        (prop/for-all [left gen/uuid right gen/uuid]
                      (or (= left right)
                          (and (not= (auth/cookie-name left) (auth/cookie-name right))
                               (not= (auth/cookie-path left) (auth/cookie-path right)))))
        identifier-property
        (prop/for-all [prefix (gen/elements ["" " " "  "])
                       value (gen/not-empty gen/string-alphanumeric)]
                      (let [candidate (str prefix (subs (str value "abc") 0
                                                        (min 40 (count (str value "abc"))))
                                           prefix)]
                        (try
                          (let [first-value (auth/normalize-identifier candidate)
                                second-value (auth/normalize-identifier candidate)]
                            (and (= first-value second-value)
                                 (= (:key first-value)
                                    (str/lower-case (:display first-value)))))
                          (catch clojure.lang.ExceptionInfo cause
                            (= :auth/identifier-invalid (:code (ex-data cause)))))))]
    (is (:pass? (tc/quick-check 1000 cookie-property)))
    (is (:pass? (tc/quick-check 1000 identifier-property)))))
