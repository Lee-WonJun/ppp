(ns ppp.runtime.server
  (:require [cognitect.transit :as transit]
            [clojure.string :as str]
            [clojure.test]
            [next.jdbc :as jdbc]
            [ppp.runtime.policy :as policy]
            [ppp.runtime.sqlite :as sqlite]
            [sci.core :as sci])
  (:import (clojure.lang LineNumberingPushbackReader)
           (java.io ByteArrayOutputStream StringReader)
           (java.nio.file Path)
           (java.sql Connection SQLException)))

(def required-source-paths
  #{"src/shared/runtime/domain.cljc"
    "src/server/runtime/server.clj"
    "test/runtime/domain_test.cljc"})

(def ^:private closed-classes
  {'java.lang.AssertionError {:class AssertionError :closed true}
   'java.lang.Exception {:class Exception :closed true}
   'java.lang.Throwable {:class Throwable :closed true}
   'java.lang.IllegalArgumentException {:class IllegalArgumentException :closed true}
   'clojure.lang.Delay {:class clojure.lang.Delay :closed true}
   'clojure.lang.ExceptionInfo {:class clojure.lang.ExceptionInfo :closed true}
   'clojure.lang.LineNumberingPushbackReader
   {:class clojure.lang.LineNumberingPushbackReader :closed true}
   'clojure.lang.LazySeq {:class clojure.lang.LazySeq :closed true}
   'java.lang.String {:class String :closed true}
   'java.io.StringWriter {:class java.io.StringWriter :closed true}
   'java.io.StringReader {:class java.io.StringReader :closed true}
   'java.lang.Integer {:class Integer :closed true}
   'java.lang.Number {:class Number :closed true}
   'java.lang.Double {:class Double :closed true}
   'java.lang.ArithmeticException {:class ArithmeticException :closed true}
   'java.lang.Object {:class Object :closed true}
   'sci.lang.IVar {:class sci.lang.IVar :closed true}
   'sci.lang.Type {:class sci.lang.Type :closed true}
   'sci.lang.Var {:class sci.lang.Var :closed true}})

(defrecord ServerRuntime [version context actions database database-path
                          deadline phase connection timeout-ms response-limit])
(defrecord Registry [active])

(defn create-registry
  []
  (->Registry (atom {})))

(defn- ensure-phase!
  [^ThreadLocal phase expected capability]
  (let [actual (.get phase)
        allowed? (if (set? expected)
                   (contains? expected actual)
                   (= expected actual))]
    (when-not allowed?
      (throw (ex-info "Runtime capability is unavailable in this phase"
                      {:code :runtime/capability-phase
                       :capability capability
                       :expected expected})))))

(defn- string-api
  []
  {'blank? str/blank?
   'trim str/trim
   'triml str/triml
   'trimr str/trimr
   'lower-case str/lower-case
   'upper-case str/upper-case
   'includes? str/includes?
   'starts-with? str/starts-with?
   'ends-with? str/ends-with?
   'split str/split
   'split-lines str/split-lines
   'join str/join
   'replace str/replace})

(defn- read-source-forms
  [path source]
  (try
    (with-open [reader (LineNumberingPushbackReader. (StringReader. source))]
      (binding [*read-eval* false]
        (let [eof (Object.)]
          (loop [forms []]
            (let [form (read {:eof eof
                              :read-cond :allow
                              :features #{:clj}}
                             reader)]
              (if (identical? eof form)
                forms
                (recur (conj forms form))))))))
    (catch Exception cause
      (throw (ex-info "Generated server source could not be analyzed"
                      {:code :runtime/server-stage-failed
                       :path path
                       :cause-code :runtime/source-read-failed}
                      cause)))))

(defn- source-string-literals
  [path source]
  (->> (read-source-forms path source)
       (mapcat #(tree-seq coll? seq %))
       (filter string?)
       set))

(defn- valid-sql-template?
  [sql write?]
  (try
    (sqlite/validate-action-sql-template! sql write?)
    true
    (catch clojure.lang.ExceptionInfo _ false)))

(defn- declared-action-sql
  [selected]
  (let [strings (into #{} (mapcat (fn [[path source]]
                                    (source-string-literals path source))) selected)]
    {:query (into #{} (filter #(valid-sql-template? % false)) strings)
     :execute (into #{} (filter #(valid-sql-template? % true)) strings)}))

(defn- assert-declared-sql!
  [allowed-sql kind sql]
  (when-not (and (string? sql) (contains? (get allowed-sql kind) (str/trim sql)))
    (throw (ex-info "Generated actions may execute only SQL declared in their source"
                    {:code :action/sql-not-declared
                     :operation kind})))
  sql)

(defn- runtime-api
  [{:keys [actions phase connection allowed-sql public-http-fn connector-http-fn]}]
  (let [public-http-fn
        (or public-http-fn
            (fn [_request]
              (throw (ex-info "Public HTTP is not configured for this runtime"
                              {:code :runtime/http-unavailable}))))
        connector-http-fn
        (or connector-http-fn
            (fn [_alias _request]
              (throw (ex-info "No named connector is configured"
                              {:code :runtime/connector-unavailable}))))]
    {'register-action!
     (fn [action-id handler]
       (ensure-phase! phase :staging :register-action!)
       (when-not (and (keyword? action-id)
                      (<= (count (str action-id)) 96)
                      (ifn? handler))
         (throw (ex-info "Invalid generated action registration"
                         {:code :runtime/invalid-action})))
       (when (contains? @actions action-id)
         (throw (ex-info "Generated action is registered more than once"
                         {:code :runtime/duplicate-action :action-id action-id})))
       (when (>= (count @actions) 64)
         (throw (ex-info "Generated runtime registered too many actions"
                         {:code :runtime/too-many-actions})))
       (swap! actions assoc action-id handler)
       action-id)

     'query!
     (fn
       ([sql]
        (ensure-phase! phase #{:action :test} :query!)
        (assert-declared-sql! allowed-sql :query sql)
        (sqlite/query! (.get ^ThreadLocal connection) sql []))
       ([sql params]
        (ensure-phase! phase #{:action :test} :query!)
        (assert-declared-sql! allowed-sql :query sql)
        (sqlite/query! (.get ^ThreadLocal connection) sql (vec params))))

     'execute!
     (fn
       ([sql]
        (ensure-phase! phase #{:action :test} :execute!)
        (assert-declared-sql! allowed-sql :execute sql)
        (sqlite/mutate! (.get ^ThreadLocal connection) sql []))
       ([sql params]
        (ensure-phase! phase #{:action :test} :execute!)
        (assert-declared-sql! allowed-sql :execute sql)
        (sqlite/mutate! (.get ^ThreadLocal connection) sql (vec params))))

     'public-http!
     (fn [request]
       (ensure-phase! phase :action :public-http!)
       (public-http-fn request))

     'connector-http!
     (fn [alias request]
       (ensure-phase! phase :action :connector-http!)
       (connector-http-fn alias request))}))

(defn- test-api
  [{:keys [actions phase]}]
  {'invoke!
   (fn [action-id request]
     (ensure-phase! phase :test :runtime.test/invoke!)
     (let [action (get @actions action-id)]
       (when-not action
         (throw (ex-info "Generated test referenced an unknown action"
                         {:code :runtime/test-action-not-found
                          :action-id action-id})))
       (action request)))})

(def ^:private clojure-test-symbols
  '[deftest is testing
    do-report test-var try-expr assert-expr function?
    *testing-contexts* *testing-vars*])

(defn- clojure-test-api
  [reports]
  (assoc
   (select-keys
    (sci/copy-ns clojure.test (sci/create-ns 'clojure.test))
    clojure-test-symbols)
   'do-report
   (fn [report]
     (swap! reports conj (select-keys report [:type]))
     nil)))

(declare enter-scope! leave-scope!)

(defn capability-inventory
  []
  {:runtime.api
   (vec (sort (map name (get-in policy/capability-catalog
                                [:server :namespaces 'runtime.api]))))
   :clojure.string
   (vec (sort (map name (keys (string-api)))))
   :runtime.test
   (vec (sort (map name (get-in policy/capability-catalog
                                [:server :namespaces 'runtime.test]))))
   :clojure.test
   (vec (sort (map name clojure-test-symbols)))
   :denied
   (vec (sort (map str policy/server-denied-symbols)))})

(defn- assert-capability-catalog!
  [api string-namespace test-namespace clojure-test-namespace]
  (let [expected-api (set (get-in policy/capability-catalog
                                  [:server :namespaces 'runtime.api]))
        expected-string (set (get-in policy/capability-catalog
                                     [:server :namespaces 'clojure.string]))
        expected-test (set (get-in policy/capability-catalog
                                   [:server :namespaces 'runtime.test]))
        expected-clojure-test (set (get-in policy/capability-catalog
                                           [:server :namespaces 'clojure.test]))]
    (when-not (and (= expected-api (set (keys api)))
                   (= expected-string (set (keys string-namespace)))
                   (= expected-test (set (keys test-namespace)))
                   (= expected-clojure-test
                      (set (keys clojure-test-namespace))))
      (throw (ex-info "Server capability implementation drifted from its catalog"
                      {:code :kernel/capability-drift}))))
  true)

(defn- source-namespace
  [path source]
  (let [namespace-form (first (filter #(and (seq? %) (= 'ns (first %)))
                                      (read-source-forms path source)))
        namespace-symbol (second namespace-form)]
    (when-not (symbol? namespace-symbol)
      (throw (ex-info "Generated test must declare a namespace"
                      {:code :runtime/generated-test-namespace
                       :path path})))
    namespace-symbol))

(defn- test-vars
  [context namespace-symbol]
  (->> (sci/eval-form context `(vals (ns-publics '~namespace-symbol)))
       (filter #(fn? (:test (meta %))))
       (sort-by #(str (:name (meta %))))))

(defn- run-test-var
  [reports test-var]
  (reset! reports [])
  (try
    ((:test (meta test-var)))
    (catch Throwable _cause
      (swap! reports conj {:type :error})))
  (let [counts (frequencies (map :type @reports))]
    {:test (str (some-> test-var meta :ns sci/ns-name)
                "/" (some-> test-var meta :name))
     :assertions (+ (get counts :pass 0)
                    (get counts :fail 0)
                    (get counts :error 0))
     :fail (get counts :fail 0)
     :error (get counts :error 0)}))

(defn- run-generated-tests!
  [{:keys [context test-sources database deadline phase connection timeout-ms
           test-reports]}]
  (let [namespaces (mapv (fn [[path source]] (source-namespace path source))
                         test-sources)
        vars (mapcat #(test-vars context %) namespaces)]
    (when (empty? vars)
      (throw (ex-info "Generated runtime did not define executable domain tests"
                      {:code :runtime/generated-tests-missing})))
    (with-open [^Connection database-connection (jdbc/get-connection database)]
      (sqlite/configure! database-connection)
      (jdbc/with-transaction [transaction database-connection {:rollback-only true}]
        (let [deadline-value (+ (System/nanoTime)
                                (* (long timeout-ms) 1000000))]
          (enter-scope! phase deadline connection :test deadline-value transaction)
          (try
            (let [results
                  (sqlite/with-progress-deadline
                    transaction deadline-value
                    #(mapv (partial run-test-var test-reports) vars))
                  failed (filterv #(or (pos? (:fail %)) (pos? (:error %)))
                                  results)
                  assertion-count (reduce + 0 (map :assertions results))]
              (when (zero? assertion-count)
                (throw (ex-info "Generated domain tests contained no assertions"
                                {:code :runtime/generated-tests-missing})))
              (when (seq failed)
                (throw (ex-info "Generated domain or business-rule tests failed"
                                {:code :runtime/generated-tests-failed
                                 :test-count (count results)
                                 :assertion-count assertion-count
                                 :failed-tests failed})))
              {:test-count (count results)
               :assertion-count assertion-count})
            (finally
              (leave-scope! phase deadline connection))))))))

(defn- enter-scope!
  [^ThreadLocal phase ^ThreadLocal deadline ^ThreadLocal connection-value
   phase-value deadline-value database-connection]
  (.set phase phase-value)
  (.set deadline deadline-value)
  (when database-connection
    (.set connection-value database-connection)))

(defn- leave-scope!
  [& thread-locals]
  (doseq [^ThreadLocal thread-local thread-locals]
    (.remove thread-local)))

(defn- exception-code
  [cause]
  (loop [current cause
         seen #{}]
    (when (and current (not (contains? seen current)))
      (or (:code (ex-data current))
          (recur (.getCause ^Throwable current) (conj seen current))))))

(defn- stage-database
  [{:keys [^Path source-database-path ^Path database-path migrations timeout-ms]}]
  (when-not database-path
    (throw (ex-info "A stage database path is required"
                    {:code :runtime/database-path-required})))
  (if source-database-path
    (do
      (when (= (.normalize (.toAbsolutePath source-database-path))
               (.normalize (.toAbsolutePath database-path)))
        (throw (ex-info "Staging cannot use the live database path"
                        {:code :runtime/live-database-stage})))
      (sqlite/stage-database! source-database-path database-path migrations))
    (do
      (sqlite/init! database-path)
      (when (seq migrations)
        (sqlite/apply-migrations! (sqlite/datasource database-path)
                                  migrations {:timeout-ms timeout-ms}))
      database-path)))

(defn stage!
  [{:keys [source-map database-path version timeout-ms response-limit
           public-http-fn connector-http-fn run-tests?]
    :or {timeout-ms 2000 response-limit (* 1024 1024) run-tests? true}
    :as options}]
  (let [missing (remove #(contains? source-map %) required-source-paths)]
    (when (seq missing)
      (throw (ex-info "Required server runtime source is missing"
                      {:code :runtime/missing-entrypoint
                       :paths (vec (sort missing))}))))
  (when-not (nat-int? version)
    (throw (ex-info "Runtime version must be a natural integer"
                    {:code :runtime/invalid-version :version version})))
  (stage-database (assoc options :timeout-ms timeout-ms))
  (let [actions (atom {})
        deadline (ThreadLocal.)
        phase (ThreadLocal.)
        connection (ThreadLocal.)
        database (sqlite/init! database-path)
        selected (->> source-map
                      (filter (fn [[path _]]
                                (or (str/starts-with? path "src/shared/")
                                    (str/starts-with? path "src/server/"))))
                      (sort-by (fn [[path _]]
                                 [(if (str/starts-with? path "src/shared/") 0 1) path])))
        test-sources (->> source-map
                          (filter (fn [[path _]]
                                    (and (str/starts-with? path "test/")
                                         (or (str/ends-with? path ".clj")
                                             (str/ends-with? path ".cljc")))))
                          (sort-by key)
                          vec)
        test-reports (atom [])
        allowed-sql (declared-action-sql selected)
        api (runtime-api {:actions actions
                          :phase phase
                          :connection connection
                          :allowed-sql allowed-sql
                          :public-http-fn public-http-fn
                          :connector-http-fn connector-http-fn})
        test-namespace (test-api {:actions actions :phase phase})
        clojure-test-namespace (clojure-test-api test-reports)
        string-namespace (string-api)
        _ (assert-capability-catalog! api string-namespace test-namespace
                                      clojure-test-namespace)
        context
        (sci/init
         {:namespaces {'runtime.api api
                       'runtime.test test-namespace
                       'clojure.string string-namespace
                       'clojure.test clojure-test-namespace}
          :classes closed-classes
          :deny policy/server-denied-symbols
          :interrupt-fn
          (fn []
            (when-let [limit (.get deadline)]
              (when (> (System/nanoTime) limit)
                (throw (ex-info "Generated runtime exceeded its time limit"
                                {:code :runtime/timeout})))))})]
    (enter-scope! phase deadline connection :staging
                  (+ (System/nanoTime) (* (long timeout-ms) 1000000)) nil)
    (try
      (doseq [[path source] selected]
        (try
          (sci/eval-string+ context source {:file path})
          (catch Exception cause
            (throw (ex-info "Generated server code failed staging"
                            {:code :runtime/server-stage-failed
                             :path path
                             :cause-code (exception-code cause)}
                            cause)))))
      (when run-tests?
        (doseq [[path source] test-sources]
          (try
            (sci/eval-string+ context source {:file path})
            (catch Exception cause
              (throw (ex-info "Generated runtime test source failed staging"
                              {:code :runtime/server-stage-failed
                               :path path
                               :cause-code (exception-code cause)}
                              cause)))))
        (run-generated-tests! {:context context
                               :test-sources test-sources
                               :database database
                               :deadline deadline
                               :phase phase
                               :connection connection
                               :test-reports test-reports
                               :timeout-ms timeout-ms}))
      (->ServerRuntime version context @actions database database-path
                       deadline phase connection timeout-ms response-limit)
      (finally
        (leave-scope! phase deadline connection)))))

(defn activate!
  [^Registry registry session-id runtime]
  (when-not (instance? ServerRuntime runtime)
    (throw (ex-info "Only a staged runtime can be activated"
                    {:code :runtime/not-staged})))
  (swap! (:active registry) assoc session-id runtime)
  runtime)

(defn runtime-for
  [^Registry registry session-id]
  (get @(:active registry) session-id))

(defn rebind-database
  [runtime ^Path database-path]
  (when-not (instance? ServerRuntime runtime)
    (throw (ex-info "Only a staged server runtime can be rebound"
                    {:code :runtime/not-staged})))
  (assoc runtime
         :database-path database-path
         :database (sqlite/init! database-path)))

(defn reuse-for-client-stage!
  "Reuse an already validated generated server context for a client-only
  change. The host still clones SQLite so source, data, and checkpoints retain
  one atomic version, but no generated server source or domain test is
  evaluated again."
  [runtime ^Path source-database-path ^Path stage-database-path version]
  (when-not (instance? ServerRuntime runtime)
    (throw (ex-info "A client-only stage requires an active server runtime"
                    {:code :runtime/not-active})))
  (when-not (nat-int? version)
    (throw (ex-info "Runtime version must be a natural integer"
                    {:code :runtime/invalid-version :version version})))
  (stage-database {:source-database-path source-database-path
                   :database-path stage-database-path
                   :migrations []
                   :timeout-ms (:timeout-ms runtime)})
  (assoc (rebind-database runtime stage-database-path)
         :version version))

(defn action-ids
  [runtime]
  (vec (sort (keys (:actions runtime)))))

(defn- validate-action-result!
  [result limit]
  (try
    (let [output (ByteArrayOutputStream.)
          writer (transit/writer output :json)]
      (transit/write writer result)
      (when (> (.size output) limit)
        (throw (ex-info "Generated action response is too large"
                        {:code :runtime/response-too-large :limit limit}))))
    (catch clojure.lang.ExceptionInfo cause
      (throw cause))
    (catch Exception cause
      (throw (ex-info "Generated action returned an unsupported value"
                      {:code :runtime/response-invalid}
                      cause))))
  result)

(defn invoke!
  [^Registry registry session-id action-id request]
  (let [runtime (runtime-for registry session-id)]
    (when-not runtime
      (throw (ex-info "Session runtime is not active"
                      {:code :runtime/not-active})))
    (let [action (get (:actions runtime) action-id)]
      (when-not action
        (throw (ex-info "Generated action not found"
                        {:code :runtime/action-not-found :action-id action-id})))
      (with-open [^Connection database-connection
                  (jdbc/get-connection (:database runtime))]
        (sqlite/configure! database-connection)
        (jdbc/with-transaction [transaction database-connection]
          (let [deadline-value (+ (System/nanoTime)
                                  (* (long (:timeout-ms runtime)) 1000000))]
            (enter-scope! (:phase runtime) (:deadline runtime) (:connection runtime)
                          :action deadline-value transaction)
            (try
              (let [result
                    (sqlite/with-progress-deadline
                      transaction deadline-value
                      #(action request))]
                {:runtime-version (:version runtime)
                 :result (validate-action-result! result (:response-limit runtime))})
              (catch SQLException cause
                (if (> (System/nanoTime) deadline-value)
                  (throw (ex-info "Generated action exceeded its time limit"
                                  {:code :runtime/timeout}
                                  cause))
                  (throw (ex-info "Generated action database operation failed"
                                  {:code :runtime/action-database-failed}
                                  cause))))
              (catch Exception cause
                (throw (ex-info "Generated action failed"
                                {:code (or (exception-code cause)
                                           :runtime/action-failed)}
                                cause)))
              (finally
                (leave-scope! (:phase runtime)
                              (:deadline runtime)
                              (:connection runtime))))))))))
