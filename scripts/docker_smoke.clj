(ns docker-smoke
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def image "programmable-programming-page:smoke")
(def suffix (str (random-uuid)))
(def container-name (str "ppp-smoke-" suffix))
(def data-volume (str "ppp-smoke-data-" suffix))
(def codex-volume (str "ppp-smoke-codex-" suffix))
(def backup-volume (str "ppp-smoke-backup-" suffix))
(def restored-data-volume (str "ppp-smoke-restored-data-" suffix))
(def cookie-file (str (fs/create-temp-file {:prefix "ppp-smoke-cookie-"})))
(def host-port
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))
(def base-url (str "http://127.0.0.1:" host-port))
(def skip-build? (= "true" (System/getenv "PPP_DOCKER_SMOKE_SKIP_BUILD")))

(defn- command!
  [& command]
  (let [result @(process/process (vec command) {:out :string :err :string})]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "Command failed: " (str/join " " command))
                      {:command command
                       :exit (:exit result)
                       :stdout (:out result)
                       :stderr (:err result)})))
    result))

(defn- curl
  [& args]
  (apply command! "curl" "--silent" "--show-error" "--fail"
         "--max-time" "10" args))

(defn- parse-json
  [text]
  (json/parse-string text true))

(defn- wait-ready!
  []
  (loop [attempt 0
         last-error nil]
    (let [result (try
                   (curl (str base-url "/readyz"))
                   {:ready? true}
                   (catch Exception cause
                     {:ready? false :error (ex-data cause)}))]
      (cond
        (:ready? result) true
        (>= attempt 120)
        (let [state (try
                      (str/trim (:out (command! "docker" "inspect" container-name
                                                "--format" "{{json .State}}")))
                      (catch Exception cause (str (ex-data cause))))
              logs (try
                     (:out (command! "docker" "logs" container-name))
                     (catch Exception cause (str (ex-data cause))))]
          (throw (ex-info "Packaged container was not ready within 60 seconds"
                          {:base-url base-url
                           :container-state state
                           :container-logs logs
                           :last-curl-error (or (:error result) last-error)})))
        :else (do
                (Thread/sleep 500)
                (recur (inc attempt) (or (:error result) last-error)))))))

(defn- authenticated-json!
  [method path body csrf-token]
  (let [arguments (cond-> ["--request" method
                           "--cookie" cookie-file
                           "--cookie-jar" cookie-file
                           "--header" (str "Origin: " base-url)
                           "--header" "Content-Type: application/json"]
                    csrf-token (conj "--header" (str "x-ppp-csrf: " csrf-token))
                    body (conj "--data" (json/generate-string body))
                    true (conj (str base-url path)))]
    (parse-json (:out (apply curl arguments)))))

(defn- wait-runtime-version!
  [session-id expected]
  (loop [attempt 0]
    (let [runtime (authenticated-json! "GET"
                                       (str "/api/sessions/" session-id "/runtime")
                                       nil nil)]
      (cond
        (= expected (:runtime-version runtime)) runtime
        (>= attempt 120)
        (throw (ex-info "Packaged fake change did not commit"
                        {:expected expected :actual (:runtime-version runtime)}))
        :else (do (Thread/sleep 250) (recur (inc attempt)))))))

(defn- inspect-image!
  []
  (let [architecture (str/trim (:out (command! "docker" "image" "inspect" image
                                               "--format" "{{.Architecture}}")))
        configured-user (str/trim (:out (command! "docker" "image" "inspect" image
                                                  "--format" "{{.Config.User}}")))
        runtime-uid (str/trim (:out (command! "docker" "run" "--rm"
                                              "--entrypoint" "id" image "-u")))
        forbidden (str/trim
                   (:out
                    (command! "docker" "run" "--rm" "--entrypoint" "sh" image "-c"
                              (str "find /opt/ppp /usr/local /var/lib/ppp /var/lib/codex "
                                   "-type f \\( -name auth.json -o -name .env "
                                   "-o -name '*.jsonl' \\) -print"))))
        history (:out (command! "docker" "image" "history" "--no-trunc" image))]
    (when-not (= "amd64" architecture)
      (throw (ex-info "Packaged image architecture is not amd64"
                      {:architecture architecture})))
    (when (or (str/blank? configured-user) (= "0" runtime-uid))
      (throw (ex-info "Packaged image does not run as a non-root user"
                      {:configured-user configured-user :runtime-uid runtime-uid})))
    (when-not (str/blank? forbidden)
      (throw (ex-info "Credential-like files exist in the image"
                      {:paths (str/split-lines forbidden)})))
    (when (or (str/includes? history "ppp-local")
              (str/includes? history "development-only-cookie-secret"))
      (throw (ex-info "Image history contains development credentials" {})))))

(defn- verify-compose-volume-contract!
  []
  (let [volumes (->> (:out (command! "docker" "compose" "config" "--volumes"))
                     str/split-lines
                     (remove str/blank?)
                     set)]
    (when-not (= #{"ppp-data" "codex-home"} volumes)
      (throw (ex-info "Compose must declare only the data and Codex home volumes"
                      {:volumes volumes})))))

(defn- verify-codex-volume-persistence!
  []
  (let [sentinel "/var/lib/codex/.ppp-volume-persistence"]
    (command! "docker" "run" "--rm" "--read-only"
              "--tmpfs" "/tmp:rw,noexec,nosuid,nodev,size=16m"
              "--cap-drop" "ALL" "--security-opt" "no-new-privileges:true"
              "--volume" (str codex-volume ":/var/lib/codex")
              "--entrypoint" "sh" image "-c"
              (str "umask 077; printf compose-volume-persistence > " sentinel))
    (command! "docker" "run" "--rm" "--read-only"
              "--tmpfs" "/tmp:rw,noexec,nosuid,nodev,size=16m"
              "--cap-drop" "ALL" "--security-opt" "no-new-privileges:true"
              "--volume" (str codex-volume ":/var/lib/codex")
              "--entrypoint" "sh" image "-c"
              (str "test \"$(cat " sentinel ")\" = compose-volume-persistence "
                   "&& test \"$(stat -c %a " sentinel ")\" = 600"))))

(defn- start-app!
  [active-data-volume]
  (command! "docker" "run" "--detach" "--name" container-name
            "--read-only" "--tmpfs" "/tmp:rw,noexec,nosuid,nodev,size=256m"
            "--cap-drop" "ALL" "--security-opt" "no-new-privileges:true"
            "--publish" (str "127.0.0.1:" host-port ":8787")
            "--volume" (str active-data-volume ":/var/lib/ppp")
            "--volume" (str codex-volume ":/var/lib/codex")
            "--env" "PPP_ENV=test"
            "--env" "PPP_ACCESS_CODE=ppp-local"
            "--env" "PPP_COOKIE_SECRET=development-only-cookie-secret-change-before-sharing"
            "--env" "PPP_AI_PROVIDER=fake"
            "--env" "PPP_REQUIRE_CLIENT_ACK=false"
            "--env" (str "PPP_PUBLIC_BASE_URL=" base-url)
            image))

(defn- runtime-has-dark-theme?
  [runtime]
  (some #(and (= "styles/runtime.css" (:path %))
              (str/includes? (:content %) "#1a1c18"))
        (:files runtime)))

(defn- smoke-http!
  []
  (wait-ready!)
  (parse-json (:out (curl (str base-url "/healthz"))))
  (authenticated-json! "POST" "/api/access" {:code "ppp-local"} nil)
  (let [bootstrap (authenticated-json! "GET" "/api/bootstrap" nil nil)
        csrf-token (:csrf-token bootstrap)
        session (authenticated-json! "POST" "/api/sessions" {} csrf-token)
        session-id (:id session)
        tab-id (str (random-uuid))]
    (when-not (= 1 (:protocol-version bootstrap))
      (throw (ex-info "Packaged bootstrap protocol mismatch" {:bootstrap bootstrap})))
    (when-not session-id
      (throw (ex-info "Packaged session creation returned no session id" {:session session})))
    (curl (str base-url "/frame.html"))
    (authenticated-json! "POST" (str "/api/sessions/" session-id "/turns")
                         {:prompt "Apply a dark theme"
                          :requestTabId tab-id
                          :baseVersion 0}
                         csrf-token)
    (let [runtime (wait-runtime-version! session-id 1)]
      (when-not (runtime-has-dark-theme? runtime)
        (throw (ex-info "Packaged runtime did not contain the deterministic change" {})))
      {:session-id session-id
       :runtime-version (:runtime-version runtime)})))

(defn- backup-and-restore!
  [{:keys [session-id runtime-version]}]
  (command! "docker" "stop" "--time" "15" container-name)
  (command! "docker" "volume" "create" backup-volume)
  (command! "docker" "volume" "create" restored-data-volume)
  (command! "docker" "run" "--rm" "--read-only"
            "--tmpfs" "/tmp:rw,noexec,nosuid,nodev,size=16m"
            "--cap-drop" "ALL" "--security-opt" "no-new-privileges:true"
            "--volume" (str data-volume ":/var/lib/ppp:ro")
            "--volume" (str backup-volume ":/var/lib/codex")
            "--entrypoint" "sh" image "-c"
            (str "umask 077; tar -C /var/lib/ppp -czf "
                 "/var/lib/codex/ppp-data.tgz . "
                 "&& test -s /var/lib/codex/ppp-data.tgz "
                 "&& tar -tzf /var/lib/codex/ppp-data.tgz >/dev/null"))
  (command! "docker" "run" "--rm" "--read-only"
            "--tmpfs" "/tmp:rw,noexec,nosuid,nodev,size=16m"
            "--cap-drop" "ALL" "--security-opt" "no-new-privileges:true"
            "--volume" (str restored-data-volume ":/var/lib/ppp")
            "--volume" (str backup-volume ":/var/lib/codex:ro")
            "--entrypoint" "sh" image "-c"
            "tar -xzf /var/lib/codex/ppp-data.tgz -C /var/lib/ppp")
  (command! "docker" "rm" container-name)
  (start-app! restored-data-volume)
  (wait-ready!)
  (spit cookie-file "")
  (authenticated-json! "POST" "/api/access" {:code "ppp-local"} nil)
  (let [runtime (authenticated-json! "GET"
                                     (str "/api/sessions/" session-id "/runtime")
                                     nil nil)
        checkpoints (authenticated-json! "GET"
                                         (str "/api/sessions/" session-id "/checkpoints")
                                         nil nil)]
    (when-not (and (= runtime-version (:runtime-version runtime))
                   (runtime-has-dark-theme? runtime)
                   (<= 2 (count (:checkpoints checkpoints))))
      (throw (ex-info "Restored data volume did not reproduce the packaged session"
                      {:expected-version runtime-version
                       :actual-version (:runtime-version runtime)
                       :checkpoint-count (count (:checkpoints checkpoints))}))))
  true)

(try
  (when-not skip-build?
    (command! "docker" "build" "--platform" "linux/amd64" "--tag" image "."))
  (inspect-image!)
  (verify-compose-volume-contract!)
  (command! "docker" "volume" "create" data-volume)
  (command! "docker" "volume" "create" codex-volume)
  (verify-codex-volume-persistence!)
  (start-app! data-volume)
  (backup-and-restore! (smoke-http!))
  (println (str "Docker smoke passed: " image
                " (linux/amd64, non-root, read-only root, persistent Codex home, "
                "backup/restore rollback)."))
  (finally
    (try (command! "docker" "rm" "--force" container-name) (catch Exception _ nil))
    (try (command! "docker" "volume" "rm" "--force" data-volume) (catch Exception _ nil))
    (try (command! "docker" "volume" "rm" "--force" codex-volume) (catch Exception _ nil))
    (try (command! "docker" "volume" "rm" "--force" backup-volume) (catch Exception _ nil))
    (try (command! "docker" "volume" "rm" "--force" restored-data-volume)
         (catch Exception _ nil))
    (fs/delete-if-exists cookie-file)))
