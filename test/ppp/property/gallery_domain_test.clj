(ns ppp.property.gallery-domain-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ppp.provider.core :as provider]
            [ppp.provider.fake :as fake]
            [ppp.runtime.server :as server]
            [ppp.runtime.sqlite :as sqlite]
            [ppp.session.store :as store]
            [ppp.util.fs :as fs])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- test-root
  []
  (Files/createTempDirectory "ppp-gallery-test"
                             (make-array FileAttribute 0)))

(defn- provider-change
  [prompt runtime-version]
  (-> (provider/generate!
       (fake/create-provider)
       {:session-id #uuid "11111111-1111-4111-8111-111111111111"
        :runtime-version runtime-version
        :prompt prompt
        :source store/initial-source
        :transcript-summary nil
        :thread-id nil})
      :result
      :change))

(defn- apply-change
  [source {:keys [writes deletes]}]
  (-> (reduce (fn [current {:keys [path content]}]
                (assoc current path content))
              source
              writes)
      (#(apply dissoc % deletes))))

(defn- assigned-migrations
  [migrations starting-index]
  (mapv (fn [offset {:keys [name sql]}]
          {:file-name (format "%06d-%s.sql" (+ starting-index offset 1) name)
           :name name
           :sql sql})
        (range)
        migrations))

(deftest fake-gallery-seeds-once-persists-and-ranks-deterministically
  (let [root (test-root)]
    (try
      (let [blank-db (.resolve root "blank.sqlite")
            gallery-db (.resolve root "gallery.sqlite")
            scoring-db (.resolve root "scoring.sqlite")
            _ (sqlite/init! blank-db)
            gallery-change (provider-change "Create a gallery with voting and a leaderboard" 0)
            gallery-source (apply-change store/initial-source gallery-change)
            migrations (assigned-migrations (:migrations gallery-change) 0)
            gallery-runtime
            (server/stage! {:source-map gallery-source
                            :source-database-path blank-db
                            :database-path gallery-db
                            :migrations migrations
                            :version 1
                            :timeout-ms 1000})
            registry (server/create-registry)
            session-id (random-uuid)]
        (server/activate! registry session-id gallery-runtime)
        (let [initial (get-in (server/invoke! registry session-id :projects/list {})
                              [:result :projects])]
          (is (= 6 (count initial)))
          (is (= [1 2 3 4 5 6] (mapv :id initial))))
        (sqlite/apply-migrations! (:database gallery-runtime) migrations)
        (is (= 6 (:count
                  (sqlite/execute-one! (:database gallery-runtime)
                                       ["SELECT COUNT(*) AS count FROM projects"]))))

        (server/invoke! registry session-id :votes/create
                        {:project-id 2 :voter-type "judge"})
        (dotimes [_ 3]
          (server/invoke! registry session-id :votes/create
                          {:project-id 1 :voter-type "public"}))

        (let [scoring-change (provider-change
                              "Judge=3 points, public=1 point, and show the top 3 podium"
                              1)
              scoring-source (apply-change gallery-source scoring-change)
              scoring-runtime
              (server/stage! {:source-map scoring-source
                              :source-database-path gallery-db
                              :database-path scoring-db
                              :migrations []
                              :version 2
                              :timeout-ms 1000})]
          (server/activate! registry session-id scoring-runtime)
          (let [{:keys [projects weights]}
                (:result (server/invoke! registry session-id :projects/list {}))]
            (is (= {:public 1 :judge 3} weights))
            (is (= 6 (count projects)))
            (is (= [1 2] (mapv :id (take 2 projects))))
            (is (= [3 3] (mapv :score (take 2 projects))))
            (is (= 4 (:count
                      (sqlite/execute-one! (:database scoring-runtime)
                                           ["SELECT COUNT(*) AS count FROM votes"])))))))
      (finally
        (fs/delete-tree! root)))))

(deftest generated-scoring-and-tie-property
  (let [root (test-root)]
    (try
      (let [scoring-change (provider-change
                            "Judge=3 points, public=1 point, and show the top 3 podium"
                            1)
            domain (some (fn [{:keys [path content]}]
                           (when (= path "src/shared/runtime/domain.cljc") content))
                         (:writes scoring-change))
            server-source
            "(ns runtime.server\n  (:require [runtime.api :as api]\n            [runtime.domain :as domain]))\n\n(defn check-domain [{:keys [votes projects]}]\n  {:score (domain/score votes)\n   :order (mapv :id (sort-by domain/ranking-key projects))})\n\n(api/register-action! :domain/check check-domain)\n"
            runtime
            (server/stage! {:source-map
                            {"src/shared/runtime/domain.cljc" domain
                             "src/server/runtime/server.clj" server-source
                             "test/runtime/domain_test.cljc"
                             "(ns runtime.domain-test (:require [clojure.test :refer [deftest is]] [runtime.domain :as domain]))\n(deftest scoring-smoke (is (= 4 (domain/score [:public :judge]))))\n"}
                            :database-path (.resolve root "property.sqlite")
                            :version 2
                            :timeout-ms 1000})
            registry (server/create-registry)
            session-id (random-uuid)
            _ (server/activate! registry session-id runtime)
            result
            (tc/quick-check
             1000
             (prop/for-all [votes (gen/vector (gen/elements [:public :judge]) 0 40)
                            scores (gen/vector (gen/choose 0 100) 6)
                            created (gen/vector (gen/choose 0 5) 6)]
                           (let [projects (mapv (fn [index score created-at]
                                                  {:id (inc index)
                                                   :score score
                                                   :created_at created-at})
                                                (range 6) scores created)
                                 expected-score (+ (count (filter #{:public} votes))
                                                   (* 3 (count (filter #{:judge} votes))))
                                 expected-order
                                 (mapv :id
                                       (sort-by (fn [{:keys [score created_at id]}]
                                                  [(- score) created_at id])
                                                projects))
                                 actual
                                 (:result
                                  (server/invoke! registry session-id :domain/check
                                                  {:votes votes :projects projects}))]
                             (and (= expected-score (:score actual))
                                  (= expected-order (:order actual)))))
             :seed 9007)]
        (testing "1,000 generated vote and tie sequences"
          (is (= 1000 (:num-tests result)))
          (is (:pass? result) (pr-str (dissoc result :result-data)))))
      (finally
        (fs/delete-tree! root)))))
