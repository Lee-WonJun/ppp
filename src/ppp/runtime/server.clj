(ns ppp.runtime.server
  (:require [cognitect.transit :as transit]
            [clojure.string :as str]
            [clojure.test]
            [next.jdbc :as jdbc]
            [ppp.runtime.auth :as auth]
            [ppp.runtime.policy :as policy]
            [ppp.runtime.resources :as resources]
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

(defrecord ServerRuntime [version context actions jobs ingresses database database-path
                          deadline phase connection request-context effects
                          session-id auth-service resource-service timeout-ms
                          response-limit database-size-limit])
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

(defn- assert-registered-job!
  [jobs handler]
  (when-not (and (keyword? handler) (contains? @jobs handler))
    (throw (ex-info "Background task handler is not registered in this product version"
                    {:code :job/handler-not-found :handler handler})))
  handler)

(defn- runtime-api
  [{:keys [actions jobs ingresses phase connection request-context effects
           session-id auth-service resource-service allowed-sql public-http-fn
           connector-http-fn]}]
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

     'register-job!
     (fn [handler-id handler]
       (ensure-phase! phase :staging :register-job!)
       (when-not (and (keyword? handler-id)
                      (<= (count (str handler-id)) 96)
                      (ifn? handler))
         (throw (ex-info "Invalid generated background task registration"
                         {:code :runtime/invalid-job})))
       (when (contains? @jobs handler-id)
         (throw (ex-info "Generated background task is registered more than once"
                         {:code :runtime/duplicate-job :handler-id handler-id})))
       (when (>= (count @jobs) 32)
         (throw (ex-info "Generated runtime registered too many background tasks"
                         {:code :runtime/too-many-jobs})))
       (swap! jobs assoc handler-id handler)
       handler-id)

     'register-ingress!
     (fn [route options handler]
       (ensure-phase! phase :staging :register-ingress!)
       (when-not (and (keyword? route)
                      (<= (count (str route)) 96)
                      (map? options)
                      (every? #{:verification} (keys options))
                      (or (nil? (:verification options))
                          (keyword? (:verification options)))
                      (ifn? handler))
         (throw (ex-info "Invalid generated public route registration"
                         {:code :runtime/invalid-ingress})))
       (when (contains? @ingresses route)
         (throw (ex-info "Generated public route is registered more than once"
                         {:code :runtime/duplicate-ingress :route route})))
       (when (>= (count @ingresses) 16)
         (throw (ex-info "Generated runtime registered too many public routes"
                         {:code :runtime/too-many-ingresses})))
       (swap! ingresses assoc route {:options options :handler handler})
       route)

     'query!
     (fn
       ([sql]
        (ensure-phase! phase #{:action :test :job :ingress} :query!)
        (assert-declared-sql! allowed-sql :query sql)
        (sqlite/query! (.get ^ThreadLocal connection) sql []))
       ([sql params]
        (ensure-phase! phase #{:action :test :job :ingress} :query!)
        (assert-declared-sql! allowed-sql :query sql)
        (sqlite/query! (.get ^ThreadLocal connection) sql (vec params))))

     'execute!
     (fn
       ([sql]
        (ensure-phase! phase #{:action :test :job :ingress} :execute!)
        (assert-declared-sql! allowed-sql :execute sql)
        (sqlite/mutate! (.get ^ThreadLocal connection) sql []))
       ([sql params]
        (ensure-phase! phase #{:action :test :job :ingress} :execute!)
        (assert-declared-sql! allowed-sql :execute sql)
        (sqlite/mutate! (.get ^ThreadLocal connection) sql (vec params))))

     'public-http!
     (fn [request]
       (ensure-phase! phase #{:action :job :ingress} :public-http!)
       (public-http-fn request))

     'connector-http!
     (fn [alias request]
       (ensure-phase! phase #{:action :job :ingress} :connector-http!)
       (connector-http-fn alias request))

     'blob-put!
     (fn [object]
       (ensure-phase! phase #{:action :test :job :ingress} :blob-put!)
       (resources/blob-put! resource-service
                            (.get ^ThreadLocal connection)
                            (:user (.get ^ThreadLocal request-context))
                            object))

     'blob-get
     (fn [id]
       (ensure-phase! phase #{:action :test :job :ingress} :blob-get)
       (resources/blob-get resource-service (.get ^ThreadLocal connection) id))

     'blob-list
     (fn []
       (ensure-phase! phase #{:action :test :job :ingress} :blob-list)
       (resources/blob-list resource-service (.get ^ThreadLocal connection)))

     'blob-delete!
     (fn [id]
       (ensure-phase! phase #{:action :test :job :ingress} :blob-delete!)
       (resources/blob-delete! resource-service (.get ^ThreadLocal connection) id))

     'publish!
     (fn [topic payload]
       (ensure-phase! phase #{:action :test :job :ingress} :publish!)
       (swap! (.get ^ThreadLocal effects) conj
              (resources/event-effect resource-service topic payload))
       nil)

     'schedule-job!
     (fn
       ([handler payload]
        (ensure-phase! phase #{:action :test :job :ingress} :schedule-job!)
        (assert-registered-job! jobs handler)
        (resources/schedule-job! resource-service
                                 (.get ^ThreadLocal connection)
                                 handler payload {}))
       ([handler payload options]
        (ensure-phase! phase #{:action :test :job :ingress} :schedule-job!)
        (assert-registered-job! jobs handler)
        (resources/schedule-job! resource-service
                                 (.get ^ThreadLocal connection)
                                 handler payload options)))

     'job-status
     (fn [id]
       (ensure-phase! phase #{:action :test :job :ingress} :job-status)
       (resources/job-status resource-service (.get ^ThreadLocal connection) id))

     'cancel-job!
     (fn [id]
       (ensure-phase! phase #{:action :test :job :ingress} :cancel-job!)
       (resources/cancel-job! resource-service (.get ^ThreadLocal connection) id))

     'search-upsert!
     (fn [collection document-id document]
       (ensure-phase! phase #{:action :test :job :ingress} :search-upsert!)
       (resources/search-upsert! resource-service (.get ^ThreadLocal connection)
                                 collection document-id document))

     'search-delete!
     (fn [collection document-id]
       (ensure-phase! phase #{:action :test :job :ingress} :search-delete!)
       (resources/search-delete! resource-service (.get ^ThreadLocal connection)
                                 collection document-id))

     'search-query
     (fn
       ([collection query]
        (ensure-phase! phase #{:action :test :job :ingress} :search-query)
        (resources/search-query resource-service (.get ^ThreadLocal connection)
                                collection query {}))
       ([collection query options]
        (ensure-phase! phase #{:action :test :job :ingress} :search-query)
        (resources/search-query resource-service (.get ^ThreadLocal connection)
                                collection query options)))

     'auth-register!
     (fn [credentials]
       (ensure-phase! phase #{:action :test} :auth-register!)
       (when-not auth-service
         (throw (ex-info "Product accounts are unavailable in this runtime"
                         {:code :runtime/auth-unavailable})))
       (let [{:keys [user effect]}
             (auth/register! auth-service (.get ^ThreadLocal connection)
                             session-id credentials)]
         (swap! (.get ^ThreadLocal effects) conj effect)
         user))

     'auth-login!
     (fn [credentials]
       (ensure-phase! phase #{:action :test} :auth-login!)
       (when-not auth-service
         (throw (ex-info "Product accounts are unavailable in this runtime"
                         {:code :runtime/auth-unavailable})))
       (let [{:keys [user effect]}
             (auth/login! auth-service (.get ^ThreadLocal connection)
                          session-id credentials)]
         (swap! (.get ^ThreadLocal effects) conj effect)
         user))

     'auth-logout!
     (fn []
       (ensure-phase! phase #{:action :test} :auth-logout!)
       (when-not auth-service
         (throw (ex-info "Product accounts are unavailable in this runtime"
                         {:code :runtime/auth-unavailable})))
       (let [{:keys [effect]}
             (auth/logout! auth-service (.get ^ThreadLocal connection)
                           (.get ^ThreadLocal request-context))]
         (swap! (.get ^ThreadLocal effects) conj effect)
         nil))

     'auth-current-user
     (fn []
       (ensure-phase! phase #{:action :test} :auth-current-user)
       (:user (.get ^ThreadLocal request-context)))

     'auth-require-user!
     (fn []
       (ensure-phase! phase #{:action :test} :auth-require-user!)
       (or (:user (.get ^ThreadLocal request-context))
           (throw (ex-info "Sign in before continuing."
                           {:code :auth/required}))))

     'auth-change-password!
     (fn [credentials]
       (ensure-phase! phase #{:action :test} :auth-change-password!)
       (when-not auth-service
         (throw (ex-info "Product accounts are unavailable in this runtime"
                         {:code :runtime/auth-unavailable})))
       (let [context (.get ^ThreadLocal request-context)
             {:keys [user effect]}
             (auth/change-password!
              auth-service (.get ^ThreadLocal connection) session-id context
              (:token-hash context) credentials)]
         (swap! (.get ^ThreadLocal effects) conj effect)
         (.set ^ThreadLocal request-context
               (assoc context :user user :token-hash nil :invalid-token? false))
         user))

     'auth-delete-account!
     (fn [credentials]
       (ensure-phase! phase #{:action :test} :auth-delete-account!)
       (when-not auth-service
         (throw (ex-info "Product accounts are unavailable in this runtime"
                         {:code :runtime/auth-unavailable})))
       (let [{:keys [effect]}
             (auth/delete-account! auth-service (.get ^ThreadLocal connection)
                                   (.get ^ThreadLocal request-context) credentials)]
         (swap! (.get ^ThreadLocal effects) conj effect)
         (.set ^ThreadLocal request-context
               {:user nil :token-hash nil :invalid-token? false})
         nil))}))

(defn- test-api
  [{:keys [actions jobs ingresses phase request-context auth-service connection]}]
  (letfn [(action-for [action-id]
            (or (get @actions action-id)
                (throw (ex-info "Generated test referenced an unknown action"
                                {:code :runtime/test-action-not-found
                                 :action-id action-id}))))]
    {'invoke!
     (fn [action-id request]
       (ensure-phase! phase :test :runtime.test/invoke!)
       ((action-for action-id) request))

     'invoke-as!
     (fn [user-id action-id request]
       (ensure-phase! phase :test :runtime.test/invoke-as!)
       (when-not auth-service
         (throw (ex-info "Product accounts are unavailable in this runtime"
                         {:code :runtime/auth-unavailable})))
       (let [previous (.get ^ThreadLocal request-context)]
         (try
           (.set ^ThreadLocal request-context
                 (auth/test-context! auth-service
                                     (.get ^ThreadLocal connection) user-id))
           ((action-for action-id) request)
           (finally
             (.set ^ThreadLocal request-context previous)))))

     'invoke-job!
     (fn [handler-id payload]
       (ensure-phase! phase :test :runtime.test/invoke-job!)
       (if-let [handler (get @jobs handler-id)]
         (handler payload)
         (throw (ex-info "Generated test referenced an unknown background task"
                         {:code :runtime/test-job-not-found
                          :handler-id handler-id}))))

     'invoke-ingress!
     (fn [route request]
       (ensure-phase! phase :test :runtime.test/invoke-ingress!)
       (if-let [handler (get-in @ingresses [route :handler])]
         (handler request)
         (throw (ex-info "Generated test referenced an unknown public route"
                         {:code :runtime/test-ingress-not-found
                          :route route}))))}))

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
           request-context effects test-reports]}]
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
          (enter-scope! phase deadline connection request-context effects
                        :test deadline-value transaction
                        {:user nil :token-hash nil :invalid-token? false} (atom []))
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
              (leave-scope! phase deadline connection request-context effects))))))))

(defn- enter-scope!
  [^ThreadLocal phase ^ThreadLocal deadline ^ThreadLocal connection-value
   ^ThreadLocal request-context ^ThreadLocal effects
   phase-value deadline-value database-connection auth-context effect-state]
  (.set phase phase-value)
  (.set deadline deadline-value)
  (when database-connection
    (.set connection-value database-connection))
  (when auth-context
    (.set request-context auth-context))
  (when effect-state
    (.set effects effect-state)))

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

(defn- exception-data-value
  [cause key]
  (loop [current cause
         seen #{}]
    (when (and current (not (contains? seen current)))
      (or (get (ex-data current) key)
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
           public-http-fn connector-http-fn run-tests? session-id auth-service
           resource-service clear-auth-sessions? clear-operational-jobs?
           database-size-limit]
    :or {timeout-ms 2000 response-limit (* 1024 1024)
         database-size-limit (* 25 1024 1024) run-tests? true}
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
        jobs (atom {})
        ingresses (atom {})
        deadline (ThreadLocal.)
        phase (ThreadLocal.)
        connection (ThreadLocal.)
        request-context (ThreadLocal.)
        effects (ThreadLocal.)
        database (sqlite/init! database-path)
        resource-service (or resource-service (resources/create-service {}))
        _ (resources/ensure-schema! resource-service database)
        _ (when auth-service
            (auth/ensure-schema! auth-service database))
        _ (when (and auth-service clear-auth-sessions?)
            (auth/clear-operational-state! database))
        _ (when clear-operational-jobs?
            (resources/cancel-operational-jobs! resource-service database))
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
                          :jobs jobs
                          :ingresses ingresses
                          :phase phase
                          :connection connection
                          :request-context request-context
                          :effects effects
                          :session-id session-id
                          :auth-service auth-service
                          :resource-service resource-service
                          :allowed-sql allowed-sql
                          :public-http-fn public-http-fn
                          :connector-http-fn connector-http-fn})
        test-namespace (test-api {:actions actions
                                  :jobs jobs
                                  :ingresses ingresses
                                  :phase phase
                                  :request-context request-context
                                  :auth-service auth-service
                                  :connection connection})
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
    (enter-scope! phase deadline connection request-context effects
                  :staging
                  (+ (System/nanoTime) (* (long timeout-ms) 1000000)) nil nil nil)
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
                               :request-context request-context
                               :effects effects
                               :test-reports test-reports
                               :timeout-ms timeout-ms}))
      (->ServerRuntime version context @actions @jobs @ingresses database database-path
                       deadline phase connection request-context effects
                       session-id auth-service resource-service timeout-ms
                       response-limit database-size-limit)
      (finally
        (leave-scope! phase deadline connection request-context effects)))))

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

(defn job-ids
  [runtime]
  (vec (sort (keys (:jobs runtime)))))

(defn ingress-routes
  [runtime]
  (vec (sort (keys (:ingresses runtime)))))

(defn ingress-options
  [^Registry registry session-id route]
  (let [runtime (or (runtime-for registry session-id)
                    (throw (ex-info "Session runtime is not active"
                                    {:code :runtime/not-active})))
        registration (get (:ingresses runtime) route)]
    (when-not registration
      (throw (ex-info "Generated public route not found"
                      {:code :runtime/ingress-not-found :route route})))
    (:options registration)))

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

(defn- active-runtime!
  [^Registry registry session-id]
  (or (runtime-for registry session-id)
      (throw (ex-info "Session runtime is not active"
                      {:code :runtime/not-active}))))

(defn- invoke-handler!
  [runtime phase-value handler request {:keys [auth-token after-result]}]
  (try
    (with-open [^Connection database-connection
                (jdbc/get-connection (:database runtime))]
      (sqlite/configure! database-connection)
      (jdbc/with-transaction [transaction database-connection]
        (let [deadline-value (+ (System/nanoTime)
                                (* (long (:timeout-ms runtime)) 1000000))
              auth-context
              (if (and (= :action phase-value) (:auth-service runtime))
                (auth/resolve-context! (:auth-service runtime) transaction
                                       (:session-id runtime) auth-token)
                {:user nil :token-hash nil :invalid-token? false})
              effect-state (atom (cond-> []
                                   (:invalid-token? auth-context)
                                   (conj {:op :clear})))]
          (enter-scope! (:phase runtime) (:deadline runtime)
                        (:connection runtime) (:request-context runtime)
                        (:effects runtime) phase-value deadline-value transaction
                        auth-context effect-state)
          (try
            (let [result
                  (sqlite/with-progress-deadline
                    transaction deadline-value
                    #(handler request))
                  result (validate-action-result! result (:response-limit runtime))]
              (let [page-count (:page_count
                                (sqlite/execute-one! transaction
                                                     ["PRAGMA page_count"]))
                    page-size (:page_size
                               (sqlite/execute-one! transaction
                                                    ["PRAGMA page_size"]))]
                (when (> (* (long page-count) (long page-size))
                         (long (:database-size-limit runtime)))
                  (throw (ex-info "Session database exceeds its storage limit"
                                  {:code :storage/quota-exceeded
                                   :limit (:database-size-limit runtime)}))))
              (when after-result
                (after-result transaction result))
              (cond-> {:runtime-version (:version runtime)
                       :result result}
                (seq @effect-state) (assoc :effects @effect-state)))
            (catch SQLException cause
              (if (> (System/nanoTime) deadline-value)
                (throw (ex-info "Generated runtime work exceeded its time limit"
                                {:code :runtime/timeout :phase phase-value}
                                cause))
                (throw (ex-info "Generated runtime database operation failed"
                                {:code (keyword "runtime"
                                                (str (name phase-value)
                                                     "-database-failed"))
                                 :phase phase-value}
                                cause))))
            (catch Exception cause
              (throw (ex-info
                      "Generated runtime work failed"
                      (cond-> {:code (or (exception-code cause)
                                         (keyword "runtime"
                                                  (str (name phase-value) "-failed")))
                               :phase phase-value}
                        (exception-data-value cause :auth/login-attempt)
                        (assoc :auth/login-attempt
                               (exception-data-value cause :auth/login-attempt)))
                      cause)))
            (finally
              (leave-scope! (:phase runtime)
                            (:deadline runtime)
                            (:connection runtime)
                            (:request-context runtime)
                            (:effects runtime)))))))
    (catch clojure.lang.ExceptionInfo cause
      (when-let [attempt (:auth/login-attempt (ex-data cause))]
        (when-let [service (:auth-service runtime)]
          (try
            (auth/record-login-failure! service (:database runtime) attempt)
            (catch Exception _
              nil))))
      (throw cause))))

(defn invoke!
  ([^Registry registry session-id action-id request]
   (invoke! registry session-id action-id request {}))
  ([^Registry registry session-id action-id request {:keys [auth-token]}]
   (let [runtime (active-runtime! registry session-id)
         action (get (:actions runtime) action-id)]
     (when-not action
       (throw (ex-info "Generated action not found"
                       {:code :runtime/action-not-found :action-id action-id})))
     (invoke-handler! runtime :action action request {:auth-token auth-token}))))

(defn invoke-ingress!
  [^Registry registry session-id route request]
  (let [runtime (active-runtime! registry session-id)
        {:keys [options handler]} (get (:ingresses runtime) route)]
    (when-not handler
      (throw (ex-info "Generated public route not found"
                      {:code :runtime/ingress-not-found :route route})))
    (assoc (invoke-handler! runtime :ingress handler request {})
           :route-options options)))

(defn claim-job!
  [^Registry registry session-id]
  (let [runtime (active-runtime! registry session-id)]
    (with-open [^Connection connection (jdbc/get-connection (:database runtime))]
      (sqlite/configure! connection)
      (jdbc/with-transaction [transaction connection]
        ;; Claim old jobs too. If a later product version removed the named
        ;; handler, run-job! records a bounded retry/terminal failure instead
        ;; of leaving the durable task pending forever.
        (resources/claim-next-job! (:resource-service runtime) transaction nil)))))

(defn run-job!
  [^Registry registry session-id {:keys [id handler payload] :as claimed-job}]
  (let [runtime (active-runtime! registry session-id)
        job-handler (get (:jobs runtime) handler)]
    (when-not job-handler
      (resources/fail-job! (:resource-service runtime) (:database runtime) id
                           :job/handler-not-found)
      (throw (ex-info "Generated background task handler is unavailable"
                      {:code :job/handler-not-found :job-id id})))
    (try
      (assoc
       (invoke-handler!
        runtime :job job-handler payload
        {:after-result
         (fn [transaction result]
           (resources/complete-job! (:resource-service runtime) transaction id result))})
       :job claimed-job)
      (catch Exception cause
        (let [code (or (exception-code cause) :job/failed)
              job (resources/fail-job! (:resource-service runtime)
                                       (:database runtime) id code)]
          (throw (ex-info "Generated background task failed"
                          {:code code :job job}
                          cause)))))))
