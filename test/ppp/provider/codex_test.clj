(ns ppp.provider.codex-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ppp.provider.codex :as codex]
            [ppp.provider.core :as provider]
            [ppp.util.fs :as fs])
  (:import (java.nio.file Files Path Paths)
           (java.nio.file.attribute FileAttribute)))

(def thread-id "11111111-1111-4111-8111-111111111111")

(deftest static-output-schema-uses-the-supported-codex-subset
  (let [schema (json/read-str
                (slurp (io/resource "provider-result.schema.json"))
                :key-fn keyword)]
    (is (false? (:additionalProperties schema)))
    (is (= #{"kind" "assistant-message" "clarification-question"
             "restore-version" "change"}
           (set (:required schema))))
    (is (not-any? #(contains? schema %)
                  [:allOf :oneOf :if :then :else]))))

(defn- shell-quote
  [value]
  (str "'" (str/replace (str value) "'" "'\"'\"'") "'"))

(defn- fake-codex-script
  [capture-root]
  (str
   "#!/bin/sh\n"
   "capture=" (shell-quote capture-root) "\n"
   "printf '%s\\n' \"$@\" > \"$capture/argv.txt\"\n"
   "pwd > \"$capture/cwd.txt\"\n"
   "if [ -f \"$PWD/.agents/skills/ppp-validate-and-apply/SKILL.md\" ]; then\n"
   "  /bin/cat \"$PWD/.agents/skills/ppp-validate-and-apply/SKILL.md\" > \"$capture/runtime-skill.md\"\n"
   "fi\n"
   "if [ -f \"$PWD/.agents/skills/ppp-client-diagnostics/SKILL.md\" ]; then\n"
   "  /bin/cat \"$PWD/.agents/skills/ppp-client-diagnostics/SKILL.md\" > \"$capture/client-diagnostics-skill.md\"\n"
   "fi\n"
   "/usr/bin/env | /usr/bin/sort > \"$capture/env.txt\"\n"
   "if [ \"$1\" = \"login\" ]; then\n"
   "  if [ -f \"$capture/login-fail\" ]; then exit 1; fi\n"
   "  exit 0\n"
   "fi\n"
   "prompt=''\n"
   ": > \"$capture/stdin.txt\"\n"
   "while IFS= read -r line || [ -n \"$line\" ]; do\n"
   "  printf '%s\\n' \"$line\" >> \"$capture/stdin.txt\"\n"
   "  prompt=\"${prompt}${line}\n\"\n"
   "done\n"
   "output=''\n"
   "previous=''\n"
   "for argument in \"$@\"; do\n"
   "  if [ \"$previous\" = \"--output-last-message\" ]; then output=\"$argument\"; fi\n"
   "  previous=\"$argument\"\n"
   "done\n"
   "write_valid() {\n"
   "  printf '%s' '{\"kind\":\"reply\",\"assistant-message\":\"Fixture reply\",\"clarification-question\":null,\"restore-version\":null,\"change\":null}' > \"$output\"\n"
   "}\n"
   "write_thread() {\n"
   "  printf '%s\\n' '{\"type\":\"thread.started\",\"thread_id\":\"" thread-id "\"}'\n"
   "}\n"
   "case \"$prompt\" in\n"
   "  *MODE_TIMEOUT*) /bin/sleep 2 ;;\n"
   "  *MODE_NONZERO*) printf '%s\\n' 'private fixture diagnostic' >&2; exit 7 ;;\n"
   "  *MODE_MISSING*) write_thread; exit 0 ;;\n"
   "  *MODE_INVALID_EVENTS*) write_valid; printf '%s\\n' 'not-json'; exit 0 ;;\n"
   "  *MODE_NO_THREAD*) write_valid; exit 0 ;;\n"
   "  *MODE_SCHEMA_INVALID*) printf '%s' '{\"kind\":\"clarify\",\"assistant-message\":\"bad\"}' > \"$output\"; write_thread; exit 0 ;;\n"
   "  *MODE_JSON_INVALID*) printf '%s' '{' > \"$output\"; write_thread; exit 0 ;;\n"
   "  *MODE_OVERSIZE_FINAL*) i=0; while [ \"$i\" -lt 2048 ]; do printf x; i=$((i + 1)); done > \"$output\"; write_thread; exit 0 ;;\n"
   "  *MODE_OVERSIZE_STDOUT*) write_valid; i=0; while [ \"$i\" -lt 2048 ]; do printf x; i=$((i + 1)); done; printf '\\n'; exit 0 ;;\n"
   "  *) write_valid; write_thread ;;\n"
   "esac\n"))

(defn- test-context
  ([]
   (test-context {}))
  ([overrides]
   (let [root (Files/createTempDirectory
               "ppp-codex-test"
               (make-array FileAttribute 0))
         capture-root (.resolve root "capture")
         codex-home (.resolve root "codex-home")
         executable (.resolve root "fake-codex")
         config (merge {:data-dir root
                        :codex-bin (str executable)
                        :codex-home (str codex-home)
                        :codex-model "gpt-5.6-terra"
                        :codex-reasoning "medium"
                        :provider-timeout-ms 1000
                        :provider-output-limit 4096}
                       overrides)]
     (fs/ensure-dir! capture-root)
     (fs/ensure-dir! codex-home)
     (fs/atomic-write-string! executable (fake-codex-script capture-root))
     (is (.setExecutable (.toFile executable) true true))
     {:root root
      :capture-root capture-root
      :config config
      :provider (codex/create-provider config)})))

(defn- request
  [prompt]
  {:session-id #uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
   :runtime-version 4
   :prompt prompt
   :source {"src/shared/runtime/domain.cljc"
            "(ns runtime.domain)\n(def source-sentinel \"TOP_SECRET_SOURCE\")\n"}
   :transcript-summary "Earlier product decisions."
   :connector-catalog [{:alias :issues
                        :description "Issue lookup"
                        :methods [:get]
                        :path-prefixes ["/v1/issues"]}]
   :ingress-verifier-catalog [{:alias :judge-hook
                               :description "Judge result signature"
                               :algorithm :hmac-sha256
                               :header "x-judge-signature"
                               :prefix "sha256="}]
   :thread-id nil})

(deftest repair-feedback-is-separated-from-the-original-user-turn
  (let [rendered (codex/request-prompt
                  (assoc (request "Build the gallery")
                         :repair-feedback {:attempt 1
                                           :code :source/validation-failed
                                           :path "src/client/runtime/client.cljs"}))]
    (is (str/includes? rendered "PREVIOUS ATTEMPT REJECTED BEFORE COMMIT"))
    (is (str/includes? rendered ":source/validation-failed"))
    (is (str/includes? rendered "Correct the proposed change"))
    (is (str/ends-with? rendered "USER TURN\nBuild the gallery\n"))))

(defn- read-lines
  [^Path path]
  (str/split-lines (fs/read-text path)))

(defn- value-after
  [values flag]
  (some (fn [[left right]] (when (= flag left) right))
        (partition 2 1 values)))

(defn- environment-map
  [lines]
  (into {}
        (map (fn [line]
               (let [[key value] (str/split line #"=" 2)]
                 [key value])))
        lines))

(defn- exception
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo cause
      cause)))

(deftest codex-process-vector-is-bounded-and-stdin-only
  (let [{:keys [root capture-root config provider]} (test-context)]
    (try
      (is (= {:ready? true :provider :codex :auth :chatgpt-oauth}
             (provider/ready? provider)))
      (let [generation (provider/generate! provider (request "Discuss this product"))
            argv (read-lines (.resolve capture-root "argv.txt"))
            cwd (Paths/get (str/trim (fs/read-text (.resolve capture-root "cwd.txt")))
                           (make-array String 0))
            environment (environment-map (read-lines (.resolve capture-root "env.txt")))
            stdin (fs/read-text (.resolve capture-root "stdin.txt"))
            runtime-skill (fs/read-text (.resolve capture-root "runtime-skill.md"))
            schema-path (Paths/get (value-after argv "--output-schema")
                                   (make-array String 0))]
        (is (= :reply (get-in generation [:result :kind])))
        (is (= thread-id (:thread-id generation)))
        (is (= "exec" (first argv)))
        (is (= "-" (last argv)))
        (doseq [feature codex/disabled-features]
          (is (some #(= ["--disable" feature] %)
                    (partition 2 1 argv)) feature))
        (doseq [control ["model_reasoning_effort=\"medium\""
                         "web_search=\"disabled\""
                         "shell_environment_policy.inherit=\"none\""]]
          (is (some #(= ["-c" control] %)
                    (partition 2 1 argv)) control))
        (doseq [flag ["--json" "--ignore-user-config" "--ignore-rules"
                      "--skip-git-repo-check" "--strict-config"]]
          (is (= 1 (count (filter #{flag} argv))) flag))
        (is (some #(= ["--model" "gpt-5.6-terra"] %)
                  (partition 2 1 argv)))
        (is (some #(= ["--sandbox" "read-only"] %)
                  (partition 2 1 argv)))
        (is (= (str cwd) (value-after argv "-C")))
        (is (.startsWith cwd (.resolve root "kernel/codex-jobs")))
        (is (not (fs/exists? cwd)))
        (is (.startsWith schema-path (.resolve root "kernel/codex-assets")))
        (is (= "https://json-schema.org/draft/2020-12/schema"
               (get (json/read-str (fs/read-text schema-path)) "$schema")))
        (is (not (str/includes? (str/join " " argv) "TOP_SECRET_SOURCE")))
        (is (not (some #(str/includes? % "/workspaces/local/sessions/") argv)))
        (is (str/includes? stdin "TOP_SECRET_SOURCE"))
        (is (str/includes? stdin "Discuss this product"))
        (is (str/includes? stdin "Use $ppp-validate-and-apply"))
        (is (str/includes? stdin "smallest affected runtime surface"))
        (is (str/includes? stdin
                           "replacing one game must not erase a separate ranking feature"))
        (is (str/includes? stdin ":issues"))
        (is (str/includes? stdin ":judge-hook"))
        (is (str/includes? stdin "x-judge-signature"))
        (is (str/includes? stdin "A registered action handler receives the submitted payload map directly"))
        (is (str/includes? stdin "create-projects"))
        (is (str/includes? stdin "schedule-job! returns a public job map"))
        (is (str/includes? stdin "pass (:id scheduled-job), never the whole map"))
        (is (not (str/includes? stdin "returned string id")))
        (is (str/includes? runtime-skill "Repair a rejected attempt"))
        (is (str/includes? runtime-skill "smallest affected runtime surface"))
        (is (str/includes? runtime-skill "server SCI staging"))
        (is (str/includes? runtime-skill "pass that string—not the whole map"))
        (is (not (fs/exists?
                  (.resolve capture-root "client-diagnostics-skill.md"))))
        (is (not (str/includes? stdin "EXAMPLE_TOKEN")))
        (is (not (str/includes? stdin "JUDGE_WEBHOOK_SECRET")))
        (is (= (str cwd) (get environment "HOME")))
        (is (= (:codex-home config) (get environment "CODEX_HOME")))
        (is (= "C.UTF-8" (get environment "LANG")))
        (is (set/subset? (set (keys environment))
                         #{"CODEX_HOME" "HOME" "LANG" "PWD"}))
        (is (empty? (set/intersection
                     #{"OPENAI_API_KEY" "PPP_ACCESS_CODE" "PPP_COOKIE_SECRET"
                       "AWS_SECRET_ACCESS_KEY"}
                     (set (keys environment))))))
      (finally
        (fs/delete-tree! root)))))

(deftest relative-data-directory-is-fixed-before-the-child-cwd-changes
  (let [current (.normalize (.toAbsolutePath
                             (Paths/get "" (make-array String 0))))
        data-root (Files/createTempDirectory
                   "ppp-codex-relative-data"
                   (make-array FileAttribute 0))
        relative (.relativize current data-root)
        {fixture-root :root
         :keys [capture-root provider]} (test-context {:data-dir relative})]
    (try
      (provider/generate! provider (request "Relative path check"))
      (let [argv (read-lines (.resolve capture-root "argv.txt"))
            cwd (Paths/get (str/trim (fs/read-text (.resolve capture-root "cwd.txt")))
                           (make-array String 0))
            schema (Paths/get (value-after argv "--output-schema")
                              (make-array String 0))]
        (is (.isAbsolute ^Path (:jobs-root provider)))
        (is (.isAbsolute ^Path (:schema-path provider)))
        (is (.isAbsolute cwd))
        (is (.isAbsolute schema))
        (is (= (str cwd) (value-after argv "-C"))))
      (finally
        (fs/delete-tree! fixture-root)
        (fs/delete-tree! data-root)))))

(deftest client-diagnostics-use-a-temporary-progressively-disclosed-skill
  (let [{:keys [root capture-root provider]} (test-context)
        sentinel "CLIENT_DIAGNOSTIC_SENTINEL"
        diagnostic {:kind :action
                    :action-id "auth/register"
                    :code "auth/identifier-invalid"
                    :status 400
                    :message sentinel}]
    (try
      (provider/generate! provider
                          (assoc (request "Please fix the failed signup")
                                 :client-diagnostics [diagnostic]))
      (let [stdin (fs/read-text (.resolve capture-root "stdin.txt"))
            skill (fs/read-text
                   (.resolve capture-root "client-diagnostics-skill.md"))]
        (is (not (str/includes? stdin sentinel)))
        (is (not (str/includes? stdin "auth/identifier-invalid")))
        (is (str/includes? skill "ppp-client-diagnostics"))
        (is (str/includes? skill "untrusted observations"))
        (is (str/includes? skill sentinel))
        (is (str/includes? skill "auth/register"))
        (is (empty? (fs/list-children (:jobs-root provider)))))
      (finally
        (fs/delete-tree! root)))))

(deftest resume-targets-the-exact-thread-and-reset-removes-it
  (let [{:keys [root capture-root provider]} (test-context)]
    (try
      (let [continued (assoc (request "Continue") :thread-id thread-id)
            generation (provider/generate! provider continued)
            argv (read-lines (.resolve capture-root "argv.txt"))]
        (is (= thread-id (:thread-id generation)))
        (is (= ["resume" thread-id "-"] (vec (take-last 3 argv))))
        (let [job-dir (.resolve (:jobs-root provider) "dry-run")
              output (.resolve job-dir "result.json")
              fresh-command (codex/command-vector
                             provider job-dir output
                             (:thread-id (provider/reset-thread-request continued)))]
          (is (= "-" (last fresh-command)))
          (is (not (some #{"resume"} fresh-command)))))
      (finally
        (fs/delete-tree! root)))))

(deftest codex-failures-have-stable-non-secret-codes
  (let [{:keys [root provider]} (test-context {:provider-timeout-ms 100
                                               :provider-output-limit 256})]
    (try
      (doseq [[marker expected]
              [["MODE_JSON_INVALID" :provider/json-invalid]
               ["MODE_SCHEMA_INVALID" :provider/schema-invalid]
               ["MODE_OVERSIZE_FINAL" :provider/output-too-large]
               ["MODE_OVERSIZE_STDOUT" :provider/output-too-large]
               ["MODE_NONZERO" :provider/failed]
               ["MODE_TIMEOUT" :provider/timeout]
               ["MODE_INVALID_EVENTS" :provider/events-invalid]
               ["MODE_MISSING" :provider/output-missing]
               ["MODE_NO_THREAD" :provider/thread-missing]]]
        (let [cause (exception #(provider/generate! provider (request marker)))]
          (is (= expected (:code (ex-data cause))) marker)
          (is (not (contains? (ex-data cause) :diagnostic)) marker)
          (is (not (str/includes? (pr-str (ex-data cause)) "TOP_SECRET_SOURCE")) marker)))
      (finally
        (fs/delete-tree! root)))))

(deftest oauth-readiness-is-actionable-without-token-material
  (let [{:keys [root capture-root provider]} (test-context)]
    (try
      (fs/atomic-write-string! (.resolve capture-root "login-fail") "1")
      (let [readiness (provider/ready? provider)]
        (is (false? (:ready? readiness)))
        (is (= :provider/oauth-not-ready (:code readiness)))
        (is (str/includes? (:message readiness) "codex login --device-auth"))
        (is (not (re-find #"(?i)(token|bearer)\s+[a-z0-9._-]+" (:message readiness)))))
      (finally
        (fs/delete-tree! root)))))
