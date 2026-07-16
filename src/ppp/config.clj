(ns ppp.config
  (:require [clojure.string :as str])
  (:import (java.nio.file Path Paths)))

(defn- env
  [name default]
  (let [value (System/getenv name)]
    (if (str/blank? value) default value)))

(defn- parse-int
  [value]
  (Integer/parseInt (str value)))

(defn- parse-bool
  [value]
  (contains? #{"1" "true" "yes" "on"} (str/lower-case (str value))))

(defn load-config
  []
  (let [environment (keyword (env "PPP_ENV" "development"))
        development? (contains? #{:development :test} environment)]
    {:environment environment
     :development? development?
     :host (env "PPP_HOST" "0.0.0.0")
     :port (parse-int (env "PPP_PORT" "8787"))
     :data-dir ^Path (Paths/get (env "PPP_DATA_DIR" "data") (make-array String 0))
     :connectors-file ^Path
     (Paths/get (env "PPP_CONNECTORS_FILE" "connectors.edn") (make-array String 0))
     :access-code (env "PPP_ACCESS_CODE" (when development? "ppp-local"))
     :cookie-secret (env "PPP_COOKIE_SECRET"
                         (when development?
                           "development-only-cookie-secret-change-before-sharing"))
     :cookie-secure? (parse-bool (env "PPP_COOKIE_SECURE" (str (not development?))))
     :provider (keyword (env "PPP_AI_PROVIDER" "codex"))
     :codex-bin (env "PPP_CODEX_BIN" "codex")
     :codex-home (env "CODEX_HOME" (str (System/getProperty "user.home") "/.codex"))
     :codex-model (env "PPP_CODEX_MODEL" "gpt-5.6-terra")
     :codex-reasoning (env "PPP_CODEX_REASONING" "medium")
     :provider-timeout-ms (parse-int (env "PPP_PROVIDER_TIMEOUT_MS" "120000"))
     :change-generation-attempts
     (parse-int (env "PPP_CHANGE_GENERATION_ATTEMPTS" "3"))
     :queue-capacity (parse-int (env "PPP_QUEUE_CAPACITY" "8"))
     :require-client-ack? (parse-bool (env "PPP_REQUIRE_CLIENT_ACK" "true"))
     :client-ack-timeout-ms (parse-int (env "PPP_CLIENT_ACK_TIMEOUT_MS" "45000"))
     :public-base-url (env "PPP_PUBLIC_BASE_URL" "http://localhost:8787")
     :prompt-limit 4000
     :provider-output-limit (* 512 1024)
     :source-file-limit 32
     :source-byte-limit (* 256 1024)
     :session-db-limit (* 25 1024 1024)
     :checkpoint-limit (* 256 1024 1024)
     :instance-limit (* 2 1024 1024 1024)}))

(defn validate!
  [{:keys [access-code cookie-secret environment development?] :as config}]
  (when (str/blank? access-code)
    (throw (ex-info "PPP_ACCESS_CODE is required" {:environment environment})))
  (when (and (not development?) (< (count access-code) 16))
    (throw (ex-info "PPP_ACCESS_CODE must contain at least 16 characters outside development"
                    {:environment environment})))
  (when (< (count (or cookie-secret "")) 32)
    (throw (ex-info "PPP_COOKIE_SECRET must contain at least 32 characters"
                    {:environment environment})))
  config)
