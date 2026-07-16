(ns ppp.http
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [org.httpkit.server :as http-kit]
            [ppp.access :as access]
            [ppp.coordinator :as coordinator]
            [ppp.session.store :as store]
            [ppp.shared.protocol :as protocol]
            [ppp.websocket :as ws]
            [reitit.ring :as ring])
  (:import (java.io InputStream)
           (java.net URLDecoder)
           (java.nio.charset StandardCharsets)
           (java.time Instant)
           (java.util Date)))

(defn- json-value
  [value]
  (walk/postwalk
   (fn [item]
     (cond
       (keyword? item) (name item)
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
    (contains? #{:runtime/requester-not-connected
                 :provider/unavailable :provider/oauth-not-ready} code) 503
    (contains? #{:turn/prompt-invalid :protocol/tab-id-invalid
                 :protocol/request-id-invalid :session/invalid-id
                 :checkpoint/version-invalid} code) 400
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
  [config coordinator session-id raw-action-id request]
  (protected-mutation
   config request
   (fn [request]
     (try
       (let [payload (read-json-body request (* 1024 1024))
             action-id (URLDecoder/decode (str raw-action-id)
                                          StandardCharsets/UTF_8)]
         (json-response 200
                        (coordinator/invoke-action! coordinator session-id
                                                    action-id payload)))
       (catch clojure.lang.ExceptionInfo cause
         (let [code (or (:code (ex-data cause)) :runtime/action-failed)]
           (error-response (exception-status code) code
                           (safe-exception-message code)
                           (request-id request))))
       (catch Exception _cause
         (error-response 400 :request/invalid-json
                         "The product action request could not be read."
                         (request-id request)))))))

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
  [{:keys [config session-store coordinator websocket readiness resource-fn]}]
  (let [resource-fn (or resource-fn io/resource)
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
           {:post #(action-handler config coordinator
                                   (get-in % [:path-params :id])
                                   (get-in % [:path-params :action-id]) %)}]
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
