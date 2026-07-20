(ns ppp.runtime.resources-test
  (:require [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]
            [ppp.runtime.resources :as resources]
            [ppp.runtime.sqlite :as sqlite]
            [ppp.util.fs :as fs])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)
           (java.util Base64 UUID)))

(defn- test-root
  []
  (Files/createTempDirectory "ppp-resources-test"
                             (make-array FileAttribute 0)))

(defn- service
  ([] (service (atom (Instant/parse "2026-07-16T00:00:00Z"))))
  ([clock]
   (let [counter (atom 0)]
     (resources/create-service
      {}
      {:now-fn #(deref clock)
       :random-uuid-fn
       #(UUID/fromString
         (format "00000000-0000-0000-0000-%012d" (swap! counter inc)))}))))

(defn- encode
  [value]
  (.encodeToString (Base64/getEncoder)
                   (.getBytes ^String value StandardCharsets/UTF_8)))

(defn- decode
  [value]
  (String. (.decode (Base64/getDecoder) ^String value)
           StandardCharsets/UTF_8))

(defn- exception-code
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo cause
      (:code (ex-data cause)))))

(deftest durable-objects-round-trip-replace-list-and-delete
  (let [root (test-root)]
    (try
      (let [database (sqlite/init! (.resolve root "objects.sqlite"))
            service (service)]
        (is (= {:id "poster"
                :name "poster.txt"
                :content-type "text/plain"
                :size 5}
               (select-keys
                (resources/blob-put! service database {:id "user-1"}
                                     {:id "poster"
                                      :name "poster.txt"
                                      :content-type "text/plain"
                                      :content-base64 (encode "hello")})
                [:id :name :content-type :size])))
        (is (= "hello" (decode (:content-base64
                                (resources/blob-get service database "poster")))))
        (is (= ["poster"] (mapv :id (resources/blob-list service database))))
        (let [generated (resources/blob-put! service database nil
                                             {:name "기획 문서.final.txt"
                                              :content-type "text/plain"
                                              :content-base64 (encode "안녕하세요")})]
          (is (uuid? (parse-uuid (:id generated))))
          (is (= "기획 문서.final.txt" (:name generated)))
          (is (= "안녕하세요"
                 (decode (:content-base64
                          (resources/blob-get service database (:id generated)))))))
        (resources/blob-put! service database nil
                             {:id "poster"
                              :name "poster.txt"
                              :content-type "text/plain"
                              :content-base64 (encode "replaced")})
        (is (= "replaced" (decode (:content-base64
                                   (resources/blob-get service database "poster")))))
        (is (true? (:deleted? (resources/blob-delete! service database "poster"))))
        (is (false? (:deleted? (resources/blob-delete! service database "poster"))))
        (is (= :blob/id-invalid
               (exception-code
                #(resources/blob-put! service database nil
                                      {:id "../escape"
                                       :name "x.txt"
                                       :content-type "text/plain"
                                       :content-base64 (encode "x")}))))
        (is (= :blob/id-invalid
               (exception-code #(resources/blob-get service database nil))))
        (is (= :blob/id-invalid
               (exception-code #(resources/blob-delete! service database nil)))))
      (finally
        (fs/delete-tree! root)))))

(deftest object-limits-reject-before-mutating-the-previous-value
  (let [root (test-root)]
    (try
      (let [database (sqlite/init! (.resolve root "object-limits.sqlite"))
            service (resources/create-service {:blob-object-limit 4
                                               :blob-count-limit 1})]
        (resources/blob-put! service database nil
                             {:id "kept"
                              :name "kept.bin"
                              :content-type "application/octet-stream"
                              :content-base64 (.encodeToString (Base64/getEncoder)
                                                               (byte-array [1 2 3 4]))})
        (let [before (resources/resource-hash service database)]
          (is (= :blob/too-large
                 (exception-code
                  #(resources/blob-put! service database nil
                                        {:id "kept"
                                         :name "replacement.bin"
                                         :content-type "application/octet-stream"
                                         :content-base64 (.encodeToString
                                                          (Base64/getEncoder)
                                                          (byte-array [5 6 7 8 9]))}))))
          (is (= before (resources/resource-hash service database)))
          (is (= [1 2 3 4]
                 (vec (.decode (Base64/getDecoder)
                               ^String (:content-base64
                                        (resources/blob-get service database "kept"))))))
          (is (= :blob/count-limit
                 (exception-code
                  #(resources/blob-put! service database nil
                                        {:id "second"
                                         :name "second.bin"
                                         :content-type "application/octet-stream"
                                         :content-base64 (.encodeToString
                                                          (Base64/getEncoder)
                                                          (byte-array [1]))}))))
          (is (= before (resources/resource-hash service database)))))
      (finally
        (fs/delete-tree! root)))))

(deftest unicode-full-text-and-caller-vectors-share-one-search-contract
  (let [root (test-root)]
    (try
      (let [database (sqlite/init! (.resolve root "search.sqlite"))
            service (service)]
        (resources/search-upsert! service database :projects "alpha"
                                  {:text "한글 실시간 협업 화이트보드"
                                   :metadata {:title "Alpha"}
                                   :vector [1.0 0.0]})
        (resources/search-upsert! service database :projects "beta"
                                  {:text "English project gallery"
                                   :metadata {:title "Beta"}
                                   :vector [0.0 1.0]})
        (is (= ["alpha"]
               (mapv :id (resources/search-query service database :projects
                                                 "한글 협업" {:limit 5}))))
        (is (= "beta"
               (:id (first (resources/search-query service database :projects
                                                   "" {:vector [0.0 1.0]
                                                       :limit 1})))))
        (is (true? (:deleted? (resources/search-delete! service database
                                                        :projects "alpha"))))
        (is (empty? (resources/search-query service database :projects
                                            "한글" {:limit 5}))))
      (finally
        (fs/delete-tree! root)))))

(deftest search-document-limit-is-pre-mutation-and-allows-replacement
  (let [root (test-root)]
    (try
      (let [database (sqlite/init! (.resolve root "search-limit.sqlite"))
            service (resources/create-service {:search-document-limit 1})]
        (resources/search-upsert! service database :notes "kept"
                                  {:text "original 한글" :metadata {:version 1}})
        (let [before (resources/resource-hash service database)]
          (is (= :search/document-limit
                 (exception-code
                  #(resources/search-upsert! service database :notes "second"
                                             {:text "second"
                                              :metadata {:version 1}}))))
          (is (= before (resources/resource-hash service database))))
        (resources/search-upsert! service database :notes "kept"
                                  {:text "replacement 한글" :metadata {:version 2}})
        (is (= {:version 2}
               (:metadata (first (resources/search-query service database :notes
                                                         "replacement" {}))))))
      (finally
        (fs/delete-tree! root)))))

(deftest jobs-are-idempotent-leased-retried-completed-and-cancelled
  (let [root (test-root)]
    (try
      (let [clock (atom (Instant/parse "2026-07-16T00:00:00Z"))
            database (sqlite/init! (.resolve root "jobs.sqlite"))
            service (service clock)
            first-job (resources/schedule-job! service database :mail/send
                                               {:recipient "judge"}
                                               {:idempotency-key "welcome"
                                                :max-attempts 2})
            duplicate (resources/schedule-job! service database :mail/send
                                               {:recipient "ignored"}
                                               {:idempotency-key "welcome"
                                                :max-attempts 2})]
        (is (= (:id first-job) (:id duplicate)))
        (let [claimed (resources/claim-next-job! service database #{:mail/send})]
          (is (= {:recipient "judge"} (:payload claimed)))
          (is (= 1 (:attempt claimed)))
          (is (= :pending (:status (resources/fail-job! service database
                                                        (:id claimed)
                                                        :mail/temporary)))))
        (swap! clock #(.plusSeconds ^Instant % 2))
        (let [retried (resources/claim-next-job! service database #{:mail/send})]
          (is (= 2 (:attempt retried)))
          (is (= :completed
                 (:status (resources/complete-job! service database (:id retried)
                                                   {:sent true})))))
        (let [pending (resources/schedule-job! service database :report/build
                                               {} {:delay-ms 10000})]
          (is (= :cancelled
                 (:status (resources/cancel-job! service database (:id pending))))))
        (let [pending (resources/schedule-job! service database :report/build
                                               {:fresh true}
                                               {:delay-ms 10000
                                                :idempotency-key "restore"})]
          (resources/cancel-operational-jobs! service database)
          (is (= :cancelled
                 (:status (resources/job-status service database (:id pending))))))
        (let [leased (resources/schedule-job! service database :report/recover
                                              {} {})
              first-claim (resources/claim-next-job! service database
                                                     #{:report/recover})]
          (is (= (:id leased) (:id first-claim)))
          (swap! clock #(.plusSeconds ^Instant % 11))
          (let [recovered (resources/claim-next-job! service database
                                                     #{:report/recover})]
            (is (= (:id leased) (:id recovered)))
            (is (= 2 (:attempt recovered)))
            (resources/complete-job! service database (:id recovered)
                                     {:recovered true})))
        (let [final-lease (resources/schedule-job! service database :report/final
                                                   {} {:max-attempts 1})
              claimed (resources/claim-next-job! service database #{:report/final})]
          (is (= (:id final-lease) (:id claimed)))
          (swap! clock #(.plusSeconds ^Instant % 11))
          (is (nil? (resources/claim-next-job! service database #{:report/final})))
          (is (= :failed
                 (:status (resources/job-status service database
                                                (:id final-lease)))))
          (is (= :job/lease-expired
                 (:last-error-code (resources/job-status service database
                                                         (:id final-lease))))))
        (is (map? (resources/resource-state service database)))
        (is (string? (resources/resource-hash service database))))
      (finally
        (fs/delete-tree! root)))))

(deftest resource-writes-follow-an-enclosing-sqlite-transaction
  (let [root (test-root)]
    (try
      (let [database (sqlite/init! (.resolve root "rollback.sqlite"))
            service (service)]
        (with-open [connection (jdbc/get-connection database)]
          (jdbc/with-transaction [transaction connection {:rollback-only true}]
            (resources/blob-put! service transaction nil
                                 {:id "temporary"
                                  :name "temporary.txt"
                                  :content-type "text/plain"
                                  :content-base64 (encode "temporary")})
            (is (= ["temporary"]
                   (mapv :id (resources/blob-list service transaction))))))
        (is (empty? (resources/blob-list service database))))
      (finally
        (fs/delete-tree! root)))))
