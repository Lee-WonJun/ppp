(ns ppp.integration.outbound-https-test
  (:require [clojure.test :refer [deftest is]]
            [ppp.outbound.client :as client]
            [ppp.util.fs :as fs])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpsConfigurator HttpsServer)
           (java.io ByteArrayOutputStream)
           (java.net InetAddress InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.security KeyStore SecureRandom)
           (java.util.concurrent Executors ExecutorService)
           (java.util.zip GZIPOutputStream)
           (javax.net.ssl KeyManagerFactory SSLContext TrustManagerFactory)
           (org.apache.hc.client5.http.ssl ClientTlsStrategyBuilder)))

(def password (.toCharArray "changeit"))

(defn- run-keytool!
  [^Path key-store-path]
  (let [executable (str (System/getProperty "java.home") "/bin/keytool")
        command [executable
                 "-genkeypair"
                 "-noprompt"
                 "-alias" "ppp-test"
                 "-keyalg" "RSA"
                 "-keysize" "2048"
                 "-storetype" "PKCS12"
                 "-keystore" (str key-store-path)
                 "-storepass" "changeit"
                 "-keypass" "changeit"
                 "-dname" "CN=localhost"
                 "-ext" "SAN=dns:localhost"
                 "-validity" "2"]
        process (-> (ProcessBuilder. ^java.util.List command)
                    (.redirectErrorStream true)
                    (.start))
        output (String. (.readAllBytes (.getInputStream process))
                        StandardCharsets/UTF_8)
        status (.waitFor process)]
    (when-not (zero? status)
      (throw (ex-info "Test certificate generation failed"
                      {:status status :output output})))
    key-store-path))

(defn- load-key-store
  [^Path path]
  (let [key-store (KeyStore/getInstance "PKCS12")]
    (with-open [input (Files/newInputStream
                       path (make-array java.nio.file.OpenOption 0))]
      (.load key-store input password))
    key-store))

(defn- tls-contexts
  [key-store]
  (let [key-managers (KeyManagerFactory/getInstance
                      (KeyManagerFactory/getDefaultAlgorithm))
        trust-managers (TrustManagerFactory/getInstance
                        (TrustManagerFactory/getDefaultAlgorithm))
        server-context (SSLContext/getInstance "TLS")
        client-context (SSLContext/getInstance "TLS")]
    (.init key-managers key-store password)
    (.init trust-managers key-store)
    (.init server-context (.getKeyManagers key-managers)
           (.getTrustManagers trust-managers) (SecureRandom.))
    (.init client-context nil (.getTrustManagers trust-managers) (SecureRandom.))
    {:server server-context :client client-context}))

(defn- gzip-bytes
  [text]
  (with-open [output (ByteArrayOutputStream.)
              gzip (GZIPOutputStream. output)]
    (.write gzip (.getBytes ^String text StandardCharsets/UTF_8))
    (.finish gzip)
    (.toByteArray output)))

(defn- send!
  [^HttpExchange exchange status headers body]
  (doseq [[name value] headers]
    (.add (.getResponseHeaders exchange) name value))
  (let [bytes (if (= (class body) (Class/forName "[B"))
                body
                (.getBytes (str body) StandardCharsets/UTF_8))]
    (.sendResponseHeaders exchange status (alength ^bytes bytes))
    (with-open [output (.getResponseBody exchange)]
      (.write output ^bytes bytes))))

(defn- handler
  [f]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (f exchange)
        (finally
          (.close ^HttpExchange exchange))))))

(defn- start-server!
  [^SSLContext ssl-context]
  (let [server (HttpsServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        executor (Executors/newCachedThreadPool)]
    (.setHttpsConfigurator server (HttpsConfigurator. ssl-context))
    (.setExecutor server executor)
    (.createContext
     server "/ok"
     (handler #(send! % 200
                      {"Content-Type" "application/json"
                       "Set-Cookie" "must-not-leave-kernel"}
                      "{\"ok\":true}")))
    (.createContext
     server "/redirect"
     (handler
      (fn [exchange]
        (let [port (.getPort (.getAddress server))]
          (send! exchange 302
                 {"Location" (str "https://localhost:" port "/ok")}
                 "")))))
    (.createContext
     server "/redirect-private"
     (handler
      (fn [exchange]
        (let [port (.getPort (.getAddress server))]
          (send! exchange 302
                 {"Location" (str "https://private.example:" port "/ok")}
                 "")))))
    (.createContext
     server "/slow"
     (handler
      (fn [exchange]
        (Thread/sleep 250)
        (send! exchange 200 {} "late"))))
    (.createContext
     server "/gzip-large"
     (handler
      #(send! % 200
              {"Content-Type" "text/plain" "Content-Encoding" "gzip"}
              (gzip-bytes (apply str (repeat (+ (* 1024 1024) 32) "x"))))))
    (.start server)
    {:server server :executor executor}))

(defn- stop-server!
  [{:keys [^HttpsServer server ^ExecutorService executor]}]
  (.stop server 0)
  (.shutdownNow executor))

(defn- exception-code
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo cause
      (:code (ex-data cause)))))

(deftest controlled-https-exercises-real-tls-redirect-timeout-and-decompression
  (let [root (Files/createTempDirectory "ppp-https-test"
                                        (make-array FileAttribute 0))]
    (try
      (let [key-store (-> (.resolve root "test.p12")
                          run-keytool!
                          load-key-store)
            contexts (tls-contexts key-store)
            running (start-server! (:server contexts))]
        (try
          (let [port (.getPort (.getAddress ^HttpsServer (:server running)))
                tls-strategy (-> (ClientTlsStrategyBuilder/create)
                                 (.setSslContext (:client contexts))
                                 (.buildClassic))
                loopback (InetAddress/getByName "127.0.0.1")
                http
                (client/create-client
                 {:resolver (constantly [loopback])
                  ;; This seam exists only for the controlled transport test.
                  ;; Production always uses policy/public-address?.
                  :address-policy (fn [host _]
                                    (contains? #{"localhost" "127.0.0.1"}
                                               host))
                  :allowed-ports #{port}
                  :tls-socket-strategy tls-strategy})
                url #(str "https://localhost:" port %)]
            (is (= {:status 200
                    :headers {"content-type" "application/json"}
                    :body "{\"ok\":true}"
                    :url (url "/ok")
                    :redirects 0}
                   (client/request! http {:url (url "/ok")})))
            (is (= 1 (:redirects
                      (client/request! http {:url (url "/redirect")}))))
            (is (= :outbound/address-forbidden
                   (exception-code
                    #(client/request! http {:url (url "/redirect-private")}))))
            (is (= :outbound/timeout
                   (exception-code
                    #(client/request! http {:url (url "/slow")
                                            :timeout-ms 50}))))
            (is (= :outbound/response-too-large
                   (exception-code
                    #(client/request! http {:url (url "/gzip-large")}))))
            (is (= :outbound/request-failed
                   (exception-code
                    #(client/request!
                      http {:url (str "https://127.0.0.1:" port "/ok")})))))
          (finally
            (stop-server! running))))
      (finally
        (fs/delete-tree! root)))))
