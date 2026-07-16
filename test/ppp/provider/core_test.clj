(ns ppp.provider.core-test
  (:require [clojure.test :refer [deftest is]]
            [ppp.provider.core :as provider]
            [ppp.session.store :as store]
            [ppp.util.fs :as fs])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- exception-code
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo cause
      (:code (ex-data cause)))))

(deftest codex-thread-is-durable-and-resettable
  (let [root (Files/createTempDirectory
              "ppp-provider-thread-test"
              (make-array FileAttribute 0))]
    (try
      (let [session-store (store/create-store {:data-dir root})
            session-id (:id (store/create-session! session-store))
            thread-id "22222222-2222-4222-8222-222222222222"]
        (store/set-codex-thread! session-store session-id thread-id)
        (is (= thread-id (:codex-thread-id (store/get-session session-store session-id))))
        (store/reset-codex-thread! session-store session-id)
        (is (nil? (:codex-thread-id (store/get-session session-store session-id))))
        (is (nil? (:thread-id
                   (provider/reset-thread-request {:thread-id thread-id}))))
        (is (= :provider/thread-invalid
               (exception-code #(store/set-codex-thread! session-store
                                                         session-id
                                                         "../../auth.json")))))
      (finally
        (fs/delete-tree! root)))))
