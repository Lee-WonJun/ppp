(ns ppp.runtime.policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ppp.runtime.policy :as policy]))

(deftest source-path-boundary
  (testing "approved source families"
    (is (policy/allowed-source-path? "src/server/runtime/server.clj"))
    (is (policy/allowed-source-path? "src/client/runtime/client.cljs"))
    (is (policy/allowed-source-path? "src/shared/runtime/domain.cljc"))
    (is (policy/allowed-source-path? "styles/runtime.css"))
    (is (policy/allowed-source-path? "test/runtime/domain_test.cljc")))
  (testing "host and traversal paths"
    (is (not (policy/allowed-source-path? "../../auth.json")))
    (is (not (policy/allowed-source-path? "/etc/passwd")))
    (is (not (policy/allowed-source-path? "src/ppp/access.clj")))
    (is (not (policy/allowed-source-path? "migrations/001.sql")))))

(deftest utf8-source-limit
  (is (= 3 (policy/utf8-size "PPP")))
  (is (= 3 (policy/utf8-size "한"))))

(deftest generated-style-runs-inside-the-client-sandbox
  (is (nil? (policy/validate-runtime-css
             ".card { background: #fff; transform: translateY(1px); }")))
  (doseq [css ["@import 'https://example.com/x.css'"
               ".x { background: URL(https://example.com/x) }"
               ".x { background: -webkit-image-set('https://example.com/x' 1x) }"
               ".x { background: u\\72l(https://example.com/x) }"]]
    (is (nil? (policy/validate-runtime-css css)) css)))

(deftest generated-client-may-use-local-state-inside-the-disposable-frame
  (is (nil?
       (policy/validate-write
        {:path "src/client/runtime/client.cljs"
         :content
         "(ns runtime.client) (def game-state (atom {:x 0}))"})))
  (is (nil?
       (policy/validate-write
        {:path "src/client/runtime/client.cljs"
         :content
         "(ns runtime.client) (swap! api/page-state assoc :x 1)"}))))

(deftest traversal-property
  (let [result
        (tc/quick-check
         1000
         (prop/for-all [prefix (gen/elements ["../" "../../" "/" "src/../" "styles/../"])
                        leaf (gen/not-empty gen/string-alphanumeric)]
                       (not (policy/allowed-source-path? (str prefix leaf ".clj")))))]
    (is (:pass? result) (pr-str (dissoc result :result-data)))))

(deftest migration-policy
  (is (nil? (policy/validate-migration
             {:name "create-votes"
              :sql "CREATE TABLE votes (id INTEGER PRIMARY KEY); INSERT INTO votes (id) VALUES (1);"})))
  (is (nil? (policy/validate-migration
             {:name "create-votes.sql"
              :sql "CREATE TABLE votes (id INTEGER PRIMARY KEY);"})))
  (doseq [sql ["ATTACH DATABASE '/tmp/x' AS x"
               "PRAGMA writable_schema = ON"
               "CREATE TABLE _ppp_owned (id INTEGER)"
               "DROP TABLE projects"]]
    (is (some? (policy/validate-migration {:name "blocked" :sql sql})) sql)))

(deftest migration-name-normalization-leaves-host-sequencing-to-the-kernel
  (is (= "create-gallery"
         (policy/normalize-migration-name "create-gallery.sql")))
  (is (= "001_create_gallery"
         (policy/normalize-migration-name "001_create_gallery.SQL")))
  (is (= "already-a-slug"
         (policy/normalize-migration-name "already-a-slug"))))

(deftest sql-scanner-distinguishes-code-from-data
  (let [sql "-- ATTACH in a comment\nINSERT INTO notes (body) VALUES ('PRAGMA; DROP; ATTACH');"]
    (is (nil? (policy/validate-migration {:name "seed-notes" :sql sql})))
    (is (= 1 (count (policy/split-sql-statements sql))))
    (is (= 1 (count (policy/split-sql-statements
                     "INSERT INTO notes (body) VALUES ('one;two');"))))
    (is (= :sql/unbalanced
           (:code (policy/validate-migration
                   {:name "broken" :sql "INSERT INTO notes VALUES ('open);"}))))))

(deftest forbidden-sql-token-property
  (let [result
        (tc/quick-check
         1000
         (prop/for-all [token (gen/elements ["ATTACH" "detach" "PrAgMa"
                                             "VACUUM" "load_extension"
                                             "TEMPORARY" "readfile" "writefile"])
                        prefix (gen/elements ["" "  " "\n\t" "-- harmless\n"
                                              "/* harmless */ "])]
                       (some? (policy/validate-migration
                               {:name "blocked"
                                :sql (str prefix token " x")}))))]
    (is (:pass? result) (pr-str (dissoc result :result-data)))))
