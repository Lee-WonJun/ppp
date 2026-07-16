# Security Model

Status: release-blocking source of truth
Last updated: 2026-07-15

## 1. Security objective

PPP intentionally evaluates AI-generated source. Server code remains capability-limited. Browser code instead receives normal frontend power inside an opaque-origin sandbox, while the authenticated parent, credentials, recovery controls, and server authority remain outside that sandbox.

PPP never exposes a public Clojure REPL, nREPL, shell, filesystem, JavaScript evaluator, or JVM evaluator endpoint.

## 2. Deployment classification

The hackathon release is:

- one trusted self-hosted JVM application;
- protected by an unguessable access code;
- intended for the owner and judges;
- backed by one local workspace and isolated session directories;
- authenticated to Codex with the owner's ChatGPT OAuth session.

It is not a public multi-tenant SaaS. Before public signup, identity, tenancy, provider credentials, workload isolation, abuse prevention, and data lifecycle require a separate security review.

## 3. Protected assets

| Asset | Impact if compromised |
|---|---|
| Codex `auth.json` and refresh state | Account takeover or usage theft. |
| Access and cookie signing secrets | Unauthorized workspace access. |
| Connector secrets | Access to developer-owned external systems. |
| Session source and prompts | Private product strategy disclosure. |
| SQLite data and checkpoints | User data disclosure or corruption. |
| Fixed kernel and capability catalog | Escape from bounded generated runtime. |
| History and journal | Loss of auditability and recovery. |

## 4. Actors

- Owner: controls deployment, access code, OAuth, connector configuration, and backups.
- Authorized user or judge: may submit arbitrary natural language and use generated actions.
- AI provider: receives bounded prompt, source, and transcript context.
- Generated program: untrusted code even when produced by the configured provider.
- Public HTTP origin: untrusted network peer.
- Attacker: may obtain the public URL, submit malicious prompts after gaining access, or control a public DNS/HTTP target.

## 5. Trust boundaries

```text
public browser
    | signed cookie + CSRF + protocol checks
    v
immutable JVM kernel
    | validated capability calls only
    +------> server SCI ------> per-session SQLite
    |
    +------> Codex process in skill-only read-only workdir
    |
    +------> SSRF-checked HTTP / named connector

authenticated immutable browser parent
    | versioned, bounded postMessage bridge
    v
opaque-origin sandbox frame -> browser SCI + generated DOM
```

Generated source remains untrusted on both sides of the network.

## 6. Threat analysis

| Threat | Primary controls | Required evidence |
|---|---|---|
| Prompt asks AI to read credentials or host files | No provider tools, skill-only workdir, cleared environment, stdin-only context. | Live refusal scenario and process-vector unit test. |
| Generated server code invokes JVM or shell | SCI class map empty, namespace allowlist, static forbidden-symbol validation. | Unit fixtures and evaluator escape tests. |
| Generated client code tries to reach parent auth or recovery UI | Opaque sandbox origin, no `allow-same-origin`, no parent object passed, validated source-window/channel bridge. | Parent isolation Playwright tests. |
| Path traversal crosses sessions | UUID parsing, normalized descendant check, no symlinks. | 1,000-sequence path property test. |
| SQL reaches kernel tables or filesystem | Statement allowlist, `_ppp_` denylist, no ATTACH/PRAGMA/extensions/file functions. | Migration and action PBT. |
| SSRF reaches loopback, private network, metadata, or DNS-rebinding target | HTTPS only, custom resolver, public-IP pinning, redirect revalidation. | URL/DNS/redirect property tests. |
| Connector exposes secret to model | Model sees alias and contract only; host injects header after policy. | Configuration redaction test. |
| Stale or malicious browser commits wrong code | Request-tab identity plus transaction/base/target version ACK. | Multi-tab E2E. |
| Browser rejects after server stage | Commit has not started; discard staging. | Coordinator integration test. |
| Crash splits source and SQLite | Prepared journal, before backup, metadata comparison, idempotent startup recovery. | Crash property test. |
| Stored prompt/source leaks through logs | Structured allowlisted log fields only. | Captured-log secret test. |
| Storage exhaustion deletes recovery state | No automatic pruning; reject new AI changes only. | Quota integration test. |

## 7. Access gate

The shared link format is:

```text
https://host.example/#access=<code>
```

The fragment is not sent in the HTTP request. Browser flow:

1. Read `access` from `location.hash`.
2. `history.replaceState` removes the fragment immediately.
3. POST the code to `/api/access` over HTTPS.
4. Server compares a keyed hash in constant time.
5. Server sets a signed, expiring, HttpOnly, Secure, SameSite=Strict cookie.
6. Browser discards the code and bootstraps.

Production refuses startup without `PPP_ACCESS_CODE` and a cookie secret of at least 32 random characters. Access code and cookie values never enter logs.

The access gate is sufficient only for the trusted hackathon release. It is not a substitute for hosted identity and tenancy.

## 8. CSRF and WebSocket

- Bootstrap returns a session-bound CSRF token.
- Every state-changing HTTP request requires the token in a custom header.
- Origin must match the configured public base URL.
- WebSocket upgrade requires the signed cookie and allowed Origin.
- First client message must subscribe with protocol version, session ID, and tab ID.
- Unknown message types and oversized frames close the connection.
- A socket can acknowledge only a stage sent to that exact tab and session.

## 9. Codex OAuth exception

Codex CLI reuses ChatGPT OAuth stored under `CODEX_HOME`. OpenAI documentation says the authentication file must be treated like a password and recommends API keys as the automation default. PPP therefore permits OAuth only when all are true:

- the deployment is owner-controlled;
- access is code-gated;
- generated code never shares the Codex process environment;
- `CODEX_HOME` is a separate persistent volume;
- credential files are mode 0600;
- the volume is absent from the image, repository, logs, backups shared with judges, and session downloads.

Before a public SaaS release, implement a Responses API or other service-account provider with explicit metering, rotation, tenant authorization, and budget controls.

## 10. Codex process boundary

- Build an argument vector with `ProcessBuilder`; never invoke a shell.
- Run in a kernel-owned directory containing only the packaged provider Skill and bounded job outputs.
- Use `--sandbox read-only` and disable shell, agents, hooks, apps, browser, computer, image, memories, and remote plugins.
- Ignore user config and rule files.
- Disable web search.
- Clear inherited environment.
- Provide source and capability context through stdin only.
- Bound stdin, stdout, stderr, final message, time, and concurrency.
- Do not return Codex reasoning or diagnostics to the browser.
- Validate the final JSON twice.

The packaged provider Skill is instruction-only. It is copied into the child Codex CWD so that `codex exec` discovers it as a repository-scoped Skill, and the stdin prompt invokes it explicitly. It cannot access session files, run validation, connect to a raw REPL, or grant generated code a new capability. Host policy, temporary SQLite, server SCI, and browser SCI remain authoritative.

Even if a provider ignores instructions, its result receives no authority until host validation and SCI staging succeed.

## 11. SCI boundary

### Server

Allowed:

- pure Clojure data and functions;
- explicitly copied `clojure.string` functions;
- action registration;
- parameterized query and mutation wrappers;
- restricted HTTP wrappers.

Denied:

- Java classes and reflection;
- class loading, process APIs, threads, futures, agents, and blocking primitives;
- namespaces for IO, shell, readers that load tagged code, dynamic evaluation, and dependency loading;
- host Vars or registry atoms except through wrappers;
- unbounded output or evaluation time.

### Browser

Allowed:

- ordinary in-frame DOM, timers, events, Canvas, animation, audio, and browser JavaScript interop;
- Hiccup data and transformations;
- registration of pages and sidebar;
- frame-owned Reagent state with bounded serializable handoff;
- authenticated actions and conversation controls through the parent bridge.

Denied:

- parent DOM and parent JavaScript objects;
- parent cookie, local storage, CSRF value, and same-origin authenticated fetch;
- changing or covering the immutable recovery handle;
- shell, filesystem, Node/process globals, or JVM authority.

The iframe sandbox and bridge validation are authoritative. SCI still bounds source evaluation and provides the registration/action API, but it is not used to remove normal frontend programming features.

## 12. Source and filesystem

- Generated paths are forward-slash normalized relative paths.
- Reject absolute paths, empty components, `..`, backslashes after normalization, control characters, and disallowed extensions.
- Resolve beneath a known root and verify the normalized absolute candidate starts with that root.
- Use UUID parsing before constructing session paths.
- Do not follow symlinks during scans, copies, quota calculations, or deletion.
- Atomic writes use a same-directory temporary file and atomic rename where supported.
- Session source and prompts never enter Codex workdir as files.

## 13. SQLite boundary

Kernel tables begin with `_ppp_`. Generated source cannot reference that prefix in any casing.

Migration parser rejects:

- attached or detached databases;
- PRAGMA of any kind;
- extensions and file IO functions;
- temporary objects;
- triggers and views in capability version 1;
- schema deletion or table renaming;
- multiple statements inside action SQL;
- edits to a migration name already recorded with a different hash.

Parameters are always separate from SQL for runtime action input. Table and column identifiers are not accepted from end-user payloads.

Database size is checked before and after staging. A stage exceeding 25 MiB is rejected before commit.

## 14. Outbound HTTP and SSRF

Public requests:

- HTTPS only.
- Explicit host, no userinfo, no fragment.
- Ports restricted to 443 unless developer configuration allows another TLS port.
- Resolve with a host resolver owned by the kernel.
- Every resolved address must be public unicast.
- Pin the connection to an approved address while retaining original TLS host validation.
- Re-resolve and revalidate every redirect.
- Maximum five redirects.
- Default connect and request timeout: five seconds.
- Response limit: 1 MiB after decompression.
- Request and response headers use an allowlist.
- Arbitrary `Authorization`, `Cookie`, proxy, forwarding, host, and hop-by-hop headers are denied.

Blocked address families include:

- loopback and unspecified;
- RFC1918 and carrier-grade NAT;
- IPv4 and IPv6 link-local;
- IPv6 unique-local;
- multicast and broadcast;
- documentation, benchmarking, and reserved ranges;
- cloud metadata endpoints, including link-local aliases.

DNS results containing any blocked address fail closed. Redirect targets repeat the complete validation.

## 15. Named connectors

Developer-owned `connectors.edn` contains:

```clojure
{:connectors
 {:issues
  {:description "Read an issue by identifier"
   :base-url "https://api.example.com"
   :allow {:methods #{:get}
           :path-prefixes ["/v1/issues"]}
   :secret-headers
   {"Authorization" {:env "EXAMPLE_TOKEN"}}
   :timeout-ms 5000
   :response-limit-bytes 1048576}}}
```

The model receives only alias, description, method, path, query, and body contract. It never receives secret values or environment variable names. The host rejects connector paths that escape the configured prefix after URL normalization.

User-managed connector setup is outside the MVP.

## 16. Data isolation and quotas

- Workspace is fixed to `local`, but each session owns a distinct normalized UUID directory and SQLite file.
- Generated actions receive only their session datasource.
- A session cannot name a database path.
- Quota checks include staging, journal backup, history, and checkpoints.
- At instance quota, new AI changes are rejected globally while read, actions, history, and restore remain available when they do not increase storage materially.
- No quota code deletes history or checkpoints automatically.

## 17. Container hardening

Release container:

- Linux amd64;
- non-root UID/GID;
- read-only root filesystem;
- `/tmp` tmpfs;
- only `ppp-data` and `codex-home` writable volumes;
- no Docker socket;
- no host filesystem mount;
- no source repository mount in production;
- one exposed application port;
- process and memory limits documented for the deployment platform.

The image contains the Codex binary but no OAuth state, access code, session data, `.env`, or connector secrets.

## 18. Logging and diagnostics

Application logs use an allowlist of metadata fields. Exceptions are converted to stable codes before logging. Stack traces may appear only in local development and must still exclude prompt, source, SQL, request bodies, cookies, headers, and process environment.

Provider stderr is drained through a bounded in-memory buffer and discarded; it is never returned
to the user or written to application logs. Production logs record only provider error code, exit
status, and duration.

The Codex launcher and, when required, its Node interpreter are resolved to absolute paths before
the child environment is cleared. This preserves the environment allowlist without inheriting a
host `PATH` that could redirect executable lookup.

## 19. Backup and recovery

- Checkpoint SQLite snapshots use the live backup facility and are verified before gzip.
- Deployment backup captures persistent volumes while the app is quiesced or through a documented consistent procedure.
- Restore verifies manifest hashes, gzip integrity, database integrity, and logical content hash before staging.
- Journal recovery completes before readiness.
- A corrupt checkpoint is rejected without modifying current state.
- Image rollback does not automatically downgrade session format.

## 20. Security verification gate

Release requires:

- source/path traversal property tests with at least 1,000 generated sequences;
- SQL policy property tests;
- SSRF URL, address, DNS, and redirect property tests;
- SCI server and browser escape fixtures;
- access/cookie/CSRF integration tests;
- stale-tab and stage-forgery E2E tests;
- log redaction test;
- repository and image secret scan;
- container non-root/read-only smoke;
- live Codex refusal scenarios for filesystem/shell and OAuth/secret exfiltration.

Any critical trust boundary without both validation and failure handling blocks release.

## 21. Known residual risks

- SCI bugs could weaken interpreter isolation. Keep capability surface small and version pinned.
- A logically harmful action can remain within allowed SQL. Generated domain
  tests run before relevant server/shared/SQL commits against staged SQLite in a
  rollback-only transaction; outbound HTTP/connectors are disabled during that
  phase. Committed runtime startup does not replay them against mutable live
  user data. These tests and owner review reduce but do not eliminate semantic
  risk.
- OAuth account rate limits and policy can change independently of PPP.
- Access-code links can be forwarded. The MVP does not provide per-person revocation.
- Single-process deployment has an availability limit.
- Outbound content can contain malicious data that influences later model turns. External responses should not automatically become privileged provider instructions.

These risks are acceptable only for the stated gated hackathon and trusted self-host scope.
