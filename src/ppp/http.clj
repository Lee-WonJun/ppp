(ns ppp.http
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [org.httpkit.server :as http-kit]
            [ppp.access :as access]
            [ppp.coordinator :as coordinator]
            [ppp.runtime.auth :as product-auth]
            [ppp.session.store :as store]
            [ppp.shared.protocol :as protocol]
            [ppp.websocket :as ws]
            [reitit.ring :as ring])
  (:import (java.io InputStream)
           (java.net URLDecoder)
           (java.nio.charset StandardCharsets)
           (java.time Instant)
           (java.util Base64 Date)))

(defn- json-value
  [value]
  (walk/postwalk
   (fn [item]
     (cond
       (keyword? item) (if-let [keyword-namespace (namespace item)]
                         (str keyword-namespace "/" (name item))
                         (name item))
       (uuid? item) (str item)
       (instance? Instant item) (str item)
       (instance? Date item) (str (.toInstant ^Date item))
       :else item))
   value))

(defn json-response
  ([status body] (json-response status body {}))
  ([status body headers]
   {:status status
    :headers (merge {"content-type" "application/json; charset=utf-8"
                     "cache-control" "no-store"}
                    headers)
    :body (json/write-str (json-value body))}))

(defn error-response
  [status code message request-id]
  (json-response status
                 {:error {:code code
                          :message message
                          :request-id request-id}}))

(defn- request-id
  [request]
  (or (:ppp/request-id request) (random-uuid)))

(defn- read-json-body
  [request limit]
  (let [body ^InputStream (:body request)
        bytes (if body (.readNBytes body (inc limit)) (byte-array 0))]
    (when (> (alength bytes) limit)
      (throw (ex-info "Request body too large" {:code :request/body-too-large})))
    (if (zero? (alength bytes))
      {}
      (json/read-str (String. bytes StandardCharsets/UTF_8) :key-fn keyword))))

(defn- read-bounded-bytes
  [request limit]
  (let [body ^InputStream (:body request)
        bytes (if body (.readNBytes body (inc limit)) (byte-array 0))]
    (when (> (alength bytes) limit)
      (throw (ex-info "Request body too large" {:code :request/body-too-large})))
    bytes))

(defn- normalized-headers
  [request]
  (into {}
        (keep (fn [[header value]]
                (when (and (string? header) (string? value)
                           (<= (count header) 128) (<= (count value) 8192))
                  [(str/lower-case header) value])))
        (:headers request)))

(defn- public-headers
  [headers]
  (into {}
        (filter (fn [[header _]]
                  (and (not (contains? #{"authorization" "cookie"
                                         "proxy-authorization"}
                                       header))
                       (or (contains? #{"content-type" "user-agent"
                                        "x-request-id" "x-event-type"}
                                      header)
                           (str/starts-with? header "x-webhook-")))))
        (take 32 headers)))

(defn- decode-query-part
  [value]
  (URLDecoder/decode (str/replace (str value) "+" "%20")
                     StandardCharsets/UTF_8))

(defn- public-query
  [query-string]
  (let [pairs (if (str/blank? query-string)
                []
                (str/split query-string #"&" -1))]
    (when (> (count pairs) 64)
      (throw (ex-info "Public request has too many query values"
                      {:code :ingress/query-invalid})))
    (reduce
     (fn [result pair]
       (let [[raw-name raw-value] (str/split pair #"=" 2)
             name (decode-query-part raw-name)
             value (decode-query-part (or raw-value ""))]
         (when (or (str/blank? name) (> (count name) 128) (> (count value) 4096))
           (throw (ex-info "Public request query is invalid"
                           {:code :ingress/query-invalid})))
         (update result name (fn [current]
                               (cond
                                 (nil? current) value
                                 (vector? current) (conj current value)
                                 :else [current value])))))
     {}
     pairs)))

(defn- public-body
  [headers ^bytes bytes]
  (let [content-type (some-> (get headers "content-type")
                             (str/split #";" 2) first str/lower-case)]
    (cond
      (zero? (alength bytes)) nil

      (= "application/json" content-type)
      (try
        (json/read-str (String. bytes StandardCharsets/UTF_8) :key-fn keyword)
        (catch Exception cause
          (throw (ex-info "Public JSON body is invalid"
                          {:code :ingress/json-invalid}
                          cause))))

      (or (str/starts-with? (or content-type "") "text/")
          (= "application/x-www-form-urlencoded" content-type))
      (String. bytes StandardCharsets/UTF_8)

      :else
      {:content-base64 (.encodeToString (Base64/getEncoder) bytes)})))

(defn- ingress-rate-allowed!
  [limiter key]
  (locking limiter
    (let [window (quot (System/currentTimeMillis) 60000)
          entry (get @limiter key)
          request-count (if (= window (:window entry)) (:count entry 0) 0)
          allowed? (< request-count 60)]
      (when (> (count @limiter) 10000)
        (swap! limiter #(into {} (filter (fn [[_ value]]
                                           (= window (:window value))) %))))
      (swap! limiter assoc key {:window window
                                :count (if allowed?
                                         (inc request-count)
                                         request-count)})
      allowed?)))

(defn- require-origin
  [config request handler]
  (if (access/allowed-origin? config request)
    (handler request)
    (error-response 403 :access/origin-denied
                    "This request did not come from the configured workspace."
                    (request-id request))))

(defn- require-access
  [config request handler]
  (if-let [session (access/authorized-session config request)]
    (handler (assoc request :ppp/access-session session))
    (error-response 401 :access/required
                    "Open the shared access link to continue."
                    (request-id request))))

(defn- require-csrf
  [config request handler]
  (let [session (:ppp/access-session request)]
    (if (access/valid-csrf? config request session)
      (handler request)
      (error-response 403 :access/csrf-invalid
                      "The workspace session could not verify this action. Refresh and try again."
                      (request-id request)))))

(defn- access-handler
  [config request]
  (require-origin
   config request
   (fn [request]
     (try
       (let [{:keys [code]} (read-json-body request 8192)]
         (if (access/valid-access-code? config code)
           (let [token (access/issue-token config)]
             (json-response 200 {:ok true}
                            {"set-cookie" (access/cookie-header config token)}))
           (error-response 401 :access/invalid
                           "The access link is not valid."
                           (request-id request))))
       (catch Exception _
         (error-response 400 :request/invalid-json
                         "The access request could not be read."
                         (request-id request)))))))

(defn- bootstrap-handler
  [config session-store readiness request]
  (require-access
   config request
   (fn [request]
     (let [access-session (:ppp/access-session request)]
       (json-response
        200
        {:protocol-version 1
         :workspace-id "local"
         :csrf-token (access/csrf-token config access-session)
         :sessions (store/list-sessions session-store)
         :readiness (when readiness (readiness))})))))

(defn- create-session-handler
  [config session-store coordinator request]
  (require-origin
   config request
   (fn [request]
     (require-access
      config request
      (fn [request]
        (require-csrf
         config request
         (fn [_]
           (json-response 201 (if coordinator
                                (coordinator/create-session! coordinator)
                                (store/create-session! session-store))))))))))

(defn- get-session-handler
  [config session-store request]
  (require-access
   config request
   (fn [request]
     (try
       (json-response
        200
        (store/get-session session-store (get-in request [:path-params :id])))
       (catch clojure.lang.ExceptionInfo cause
         (let [code (:code (ex-data cause))]
           (error-response (if (= :session/not-found code) 404 400)
                           code
                           (if (= :session/not-found code)
                             "That session does not exist."
                             "That session identifier is not valid.")
                           (request-id request))))))))

(defn- get-client-runtime-handler
  [config session-store request]
  (require-access
   config request
   (fn [request]
     (try
       (let [session-id (get-in request [:path-params :id])
             manifest (store/current-manifest session-store session-id)
             source (store/current-source-map session-store session-id)]
         (json-response
          200
          {:runtime-version (:runtime-version manifest)
           :capability-version (:capability-version manifest)
           :manifest manifest
           :files (protocol/client-runtime-files source)}))
       (catch clojure.lang.ExceptionInfo cause
         (let [code (:code (ex-data cause))]
           (error-response (if (= :session/not-found code) 404 400)
                           code
                           (if (= :session/not-found code)
                             "That session does not exist."
                             "The saved product view could not be read.")
                           (request-id request))))))))

(defn- protected-mutation
  [config request handler]
  (require-origin
   config request
   #(require-access
     config %
     (fn [authorized]
       (require-csrf config authorized handler)))))

(defn- exception-status
  [code]
  (cond
    (contains? #{:session/not-found :runtime/action-not-found
                 :checkpoint/not-found :turn/job-not-found} code) 404
    (contains? #{:runtime/stale-browser-version
                 :runtime/base-version-conflict} code) 409
    (= :provider/queue-full code) 429
    (= :auth/temporarily-locked code) 429
    (contains? #{:auth/invalid-credentials :auth/required} code) 401
    (= :auth/identifier-taken code) 409
    (contains? #{:runtime/requester-not-connected
                 :provider/unavailable :provider/oauth-not-ready} code) 503
    (contains? #{:turn/prompt-invalid :protocol/tab-id-invalid
                 :protocol/request-id-invalid :session/invalid-id
                 :checkpoint/version-invalid :auth/identifier-invalid
                 :auth/password-invalid} code) 400
    :else 422))

(defn- safe-exception-message
  [code]
  (cond
    (= :runtime/stale-browser-version code)
    "This tab is behind the saved product. Refresh to the current version and try again."

    (= :provider/queue-full code)
    "The product assistant is busy. Try again shortly."

    (= :runtime/requester-not-connected code)
    "The browser live connection is not ready. Reconnect and try again."

    (= :runtime/action-not-found code)
    "That product action is not available in the current version."

    (= :checkpoint/not-found code)
    "That checkpoint does not exist."

    (= :auth/identifier-taken code)
    "That sign-in identifier is already in use."

    (= :auth/invalid-credentials code)
    "Those sign-in details did not match."

    (= :auth/required code)
    "Sign in before continuing."

    (= :auth/temporarily-locked code)
    "Sign in is temporarily unavailable. Try again shortly."

    (= :auth/identifier-invalid code)
    "Use a valid sign-in identifier."

    (= :auth/password-invalid code)
    "Use a password with at least 8 characters."

    (= "blob" (namespace code))
    (case code
      :blob/not-found "That stored object no longer exists."
      :blob/too-large "That object is larger than this workspace allows."
      :blob/count-limit "This product has reached its stored object limit."
      "That object could not be stored safely.")

    (= "job" (namespace code))
    (case code
      :job/not-found "That background task no longer exists."
      :job/count-limit "This product has reached its background task limit."
      "That background task could not be completed safely.")

    (= "search" (namespace code))
    "That search request is outside this product's supported limits."

    (= "event" (namespace code))
    "That live update is outside this product's supported limits."

    (= :storage/quota-exceeded code)
    "This workspace has reached its storage limit. Existing product data is unchanged."

    (= :runtime/response-too-large code)
    "The product returned more information than this workspace can safely display."

    (and (keyword? code) (= "checkpoint" (namespace code)))
    "That checkpoint could not be safely restored."

    :else
    "The request could not be safely completed."))

(defn- turn-handler
  [config coordinator session-id request]
  (protected-mutation
   config request
   (fn [request]
     (try
       (let [body (read-json-body request 16384)
             result (coordinator/submit-turn!
                     coordinator session-id
                     {:prompt (:prompt body)
                      :request-tab-id (or (:requestTabId body)
                                          (:request-tab-id body))
                      :base-version (or (:baseVersion body)
                                        (:base-version body))
                      :request-id (request-id request)})]
         (json-response 202 result))
       (catch clojure.lang.ExceptionInfo cause
         (let [code (or (:code (ex-data cause)) :turn/rejected)]
           (error-response (exception-status code) code
                           (safe-exception-message code)
                           (request-id request))))
       (catch Exception _cause
         (error-response 400 :request/invalid-json
                         "The turn request could not be read."
                         (request-id request)))))))

(defn- action-handler
  [config coordinator product-auth-service session-id raw-action-id request]
  (protected-mutation
   config request
   (fn [request]
     (try
       (let [payload (read-json-body request
                                     (get config :runtime-request-limit
                                          (* 7 1024 1024)))
             action-id (URLDecoder/decode (str raw-action-id)
                                          StandardCharsets/UTF_8)
             auth-token (when product-auth-service
                          (product-auth/request-token request session-id))
             result (coordinator/invoke-action! coordinator session-id
                                                action-id payload
                                                {:auth-token auth-token})
             effect (last (filter #(contains? #{:set :clear} (:op %))
                                  (:effects result)))
             headers (if (and product-auth-service effect)
                       (product-auth/effect-headers product-auth-service
                                                    session-id effect)
                       {})]
         (json-response 200 (dissoc result :effects) headers))
       (catch clojure.lang.ExceptionInfo cause
         (let [code (or (:code (ex-data cause)) :runtime/action-failed)]
           (error-response (exception-status code) code
                           (safe-exception-message code)
                           (request-id request))))
       (catch Exception _cause
         (error-response 400 :request/invalid-json
                         "The product action request could not be read."
                         (request-id request)))))))

(defn- public-ingress-handler
  [coordinator limiter session-id raw-route request]
  (try
    (let [method (:request-method request)
          _ (when-not (contains? #{:get :post :put :patch :delete} method)
              (throw (ex-info "Public request method is not supported"
                              {:code :ingress/method-not-allowed})))
          route (URLDecoder/decode (str raw-route) StandardCharsets/UTF_8)
          remote (or (:remote-addr request) "unknown")
          _ (when-not (ingress-rate-allowed! limiter [session-id route remote])
              (throw (ex-info "Public route is receiving too many requests"
                              {:code :ingress/rate-limited})))
          bytes (read-bounded-bytes request (* 1024 1024))
          headers (normalized-headers request)
          public-request {:method method
                          :query (public-query (:query-string request))
                          :headers (public-headers headers)
                          :body (public-body headers bytes)}
          result (coordinator/invoke-ingress!
                  coordinator session-id route public-request
                  {:headers headers :raw-body bytes})
          response (:result result)
          status (:status response)
          body (:body response)]
      (when-not (and (map? response)
                     (every? #{:status :body} (keys response))
                     (int? status)
                     (or (<= 200 status 299) (<= 400 status 599)))
        (throw (ex-info "Generated public response is invalid"
                        {:code :ingress/response-invalid})))
      (json-response status body))
    (catch clojure.lang.ExceptionInfo cause
      (let [code (or (:code (ex-data cause)) :ingress/failed)
            status (cond
                     (= :runtime/ingress-not-found code) 404
                     (= :request/body-too-large code) 413
                     (= :ingress/rate-limited code) 429
                     (= :ingress/method-not-allowed code) 405
                     (= :ingress/signature-invalid code) 401
                     (= :ingress/verifier-unavailable code) 503
                     :else 422)
            message (cond
                      (= status 404) "That public product route does not exist."
                      (= status 413) "The public request is too large."
                      (= status 429) "This public product route is busy. Try again shortly."
                      (= status 405) "That request method is not supported."
                      (= status 401) "The public request could not be verified."
                      (= status 503) "This public product route is not ready."
                      :else "The public product request could not be completed.")]
        (error-response status code message (request-id request))))
    (catch Exception _cause
      (error-response 400 :ingress/request-invalid
                      "The public product request could not be read."
                      (request-id request)))))

(defn- checkpoints-handler
  [config session-store session-id request]
  (require-access
   config request
   (fn [request]
     (try
       (json-response 200 {:checkpoints (store/list-checkpoints session-store
                                                                session-id)})
       (catch clojure.lang.ExceptionInfo cause
         (let [code (or (:code (ex-data cause)) :checkpoint/read-failed)]
           (error-response (exception-status code) code
                           "The saved checkpoints could not be read."
                           (request-id request))))))))

(defn- restore-handler
  [config coordinator session-id request]
  (protected-mutation
   config request
   (fn [request]
     (try
       (let [body (read-json-body request 16384)
             result
             (coordinator/submit-restore!
              coordinator session-id
              {:checkpoint-version (or (:checkpointVersion body)
                                       (:checkpoint-version body))
               :request-tab-id (or (:requestTabId body)
                                   (:request-tab-id body))
               :base-version (or (:baseVersion body)
                                 (:base-version body))
               :request-id (request-id request)})]
         (json-response 202 result))
       (catch clojure.lang.ExceptionInfo cause
         (let [code (or (:code (ex-data cause)) :restore/rejected)]
           (error-response (exception-status code) code
                           (safe-exception-message code)
                           (request-id request))))
       (catch Exception _cause
         (error-response 400 :request/invalid-json
                         "The restore request could not be read."
                         (request-id request)))))))

(defn- websocket-handler
  [config websocket request]
  (require-origin
   config request
   #(require-access config % (fn [authorized]
                               (ws/handler websocket authorized)))))

(def ^:private content-types
  {".html" "text/html; charset=utf-8"
   ".css" "text/css; charset=utf-8"
   ".js" "text/javascript; charset=utf-8"
   ".map" "application/json; charset=utf-8"
   ".svg" "image/svg+xml"})

(defn- resource-response
  [resource-fn resource-name]
  (when-let [resource (resource-fn resource-name)]
    (let [extension (some #(when (str/ends-with? resource-name %) %) (keys content-types))]
      {:status 200
       :headers {"content-type" (get content-types extension "application/octet-stream")
                 ;; Static filenames are stable until the release build emits
                 ;; content hashes. Caching app.js under a stable URL can keep
                 ;; an already-fixed host-shell bug alive for an hour.
                 "cache-control" "no-store"}
       :body (io/input-stream resource)})))

(defn- static-handler
  [resource-fn request]
  (let [uri (:uri request)
        resource-name (if (= "/" uri)
                        "public/index.html"
                        (str "public" uri))]
    (when (and (= :get (:request-method request))
               (not (str/includes? resource-name "..")))
      (resource-response resource-fn resource-name))))

(defn create-handler
  [{:keys [config session-store coordinator websocket product-auth readiness
           resource-fn]}]
  (let [resource-fn (or resource-fn io/resource)
        ingress-limiter (atom {})
        router
        (ring/router
         [["/api/access" {:post #(access-handler config %)}]
          ["/api/bootstrap"
           {:get #(bootstrap-handler config session-store readiness %)}]
          ["/api/sessions"
           {:post #(create-session-handler config session-store coordinator %)}]
          ["/api/sessions/:id" {:get #(get-session-handler config session-store %)}]
          ["/api/sessions/:id/runtime"
           {:get #(get-client-runtime-handler config session-store %)}]
          ["/api/sessions/:id/checkpoints"
           {:get #(checkpoints-handler config session-store
                                       (get-in % [:path-params :id]) %)}]
          ["/api/sessions/:id/restores"
           {:post #(restore-handler config coordinator
                                    (get-in % [:path-params :id]) %)}]
          ["/api/sessions/:id/turns"
           {:post #(turn-handler config coordinator
                                 (get-in % [:path-params :id]) %)}]
          ["/api/sessions/:id/actions/:action-id"
           {:post #(action-handler config coordinator product-auth
                                   (get-in % [:path-params :id])
                                   (get-in % [:path-params :action-id]) %)}]
          ["/public/sessions/:id/:route"
           {:get #(public-ingress-handler coordinator ingress-limiter
                                          (get-in % [:path-params :id])
                                          (get-in % [:path-params :route]) %)
            :post #(public-ingress-handler coordinator ingress-limiter
                                           (get-in % [:path-params :id])
                                           (get-in % [:path-params :route]) %)
            :put #(public-ingress-handler coordinator ingress-limiter
                                          (get-in % [:path-params :id])
                                          (get-in % [:path-params :route]) %)
            :patch #(public-ingress-handler coordinator ingress-limiter
                                            (get-in % [:path-params :id])
                                            (get-in % [:path-params :route]) %)
            :delete #(public-ingress-handler coordinator ingress-limiter
                                             (get-in % [:path-params :id])
                                             (get-in % [:path-params :route]) %)}]
          ["/ws" {:get #(websocket-handler config websocket %)}]
          ["/healthz" {:get (fn [_] (json-response 200 {:status "ok"}))}]
          ["/readyz"
           {:get (fn [_]
                   (let [result (if readiness (readiness) {:ready? true})]
                     (json-response (if (:ready? result) 200 503) result)))}]])]
    (fn [request]
      (let [request (assoc request :ppp/request-id (random-uuid))]
        (or ((ring/ring-handler router) request)
            (static-handler resource-fn request)
            (error-response 404 :http/not-found "Not found." (request-id request)))))))

(defn start!
  [handler {:keys [host port]}]
  (http-kit/run-server handler {:ip host :port port}))
