# PPP-004 Provider Evidence

Captured: 2026-07-15 KST

## Sanitized process vector

The fake-executable integration test captured the child arguments below. Absolute executable,
schema, output, and job paths are replaced with typed placeholders; the test separately asserts
that schema/job paths are kernel-owned temporary paths and not repository or session paths.

```text
<absolute-codex-runtime> <absolute-codex-entry> exec
--json
--output-schema <kernel-codex-assets/provider-result.schema.json>
--output-last-message <kernel-codex-jobs/job-id/result.json>
--model gpt-5.6-terra
--sandbox read-only
--ignore-user-config
--ignore-rules
--skip-git-repo-check
--strict-config
--disable shell_tool
--disable multi_agent
--disable hooks
--disable apps
--disable browser_use
--disable computer_use
--disable image_generation
--disable memories
--disable remote_plugin
-c model_reasoning_effort="medium"
-c web_search="disabled"
-c shell_environment_policy.inherit="none"
-C <kernel-codex-jobs/job-id>
-
```

Resume replaces the final `-` with:

```text
resume <exact-thread-uuid> -
```

The captured child environment contains only `CODEX_HOME`, per-job `HOME`, `LANG`, and the shell
fixture's derived `PWD`. Prompt, source, and capability context appear only in captured stdin.

## Local OAuth preflight

The real installed Codex 0.144.0 launcher was resolved to absolute Node and Codex paths and run
with the cleared child environment. No model generation was performed.

```edn
{:ready? true, :provider :codex, :auth :chatgpt-oauth}
```

No credential or token material is recorded in this artifact.
