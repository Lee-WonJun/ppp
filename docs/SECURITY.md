# Security Model

Status: release-blocking source of truth
Last updated: 2026-07-17

## 1. Security objective

PPP intentionally evaluates AI-generated source. Server code remains capability-limited. Browser code instead receives normal frontend power inside an opaque-origin sandbox, while the authenticated parent, credentials, recovery controls, and host authority remain outside that sandbox. Capabilities should make session-owned product behavior broad; they are not a reason to ban ordinary app categories. The security objective is containment of effects outside the session, not artificial weakness inside it.

PPP never exposes a public Clojure REPL, nREPL, shell, filesystem, JavaScript evaluator, or JVM evaluator endpoint.

## 2. Deployment classification

The hackathon release is:

- one trusted self-hosted JVM application;
- protected by one strong owner-configured shared password;
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
| Generated-product password hashes and login sessions | Product-account takeover or cross-user data access. |
| Session blobs, search documents, jobs, and ingress state | Product data disclosure, repeated side effects, or public input abuse. |
| Fixed kernel and capability catalog | Escape from bounded generated runtime. |
| History and journal | Loss of auditability and recovery. |

## 4. Actors

- Owner: controls deployment, shared password, OAuth, connector configuration, and backups.
- Authorized user or judge: may submit arbitrary natural language and use generated actions.
- AI provider: receives bounded prompt, source, and transcript context.
- Generated program: untrusted code even when produced by the configured provider.
- Generated-product user: an identity belonging to one generated product; it
  has no implied PPP workspace or host authority.
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
    |            |                  +--> reserved product credentials
    |            +--> public product-user claims only
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
| Generated code reads passwords, hashes, login tokens, or forges a user | Kernel-owned Argon2id and token service, reserved tables, typed claims/effects only. | Product-auth unit, SCI, HTTP, and browser isolation tests. |
| A product login crosses into another PPP session | UUID-derived cookie name and path, session-keyed token digest, distinct database lookup. | 1,000-sequence cross-session auth property. |
| Checkpoint restore resurrects a revoked login | Restore keeps credential state but deletes every active auth session and attempt before activation. | Restore/auth integration property. |
| Credential guessing exhausts or enumerates accounts | Bounded input, same unknown/wrong hash path and public error, per-identifier throttling. | Hash-path contract and throttle tests. |
| Shared PPP password is guessed or brute-forced | Origin check, constant-time comparison, per-remote rolling failure throttle, generic response, signed cookie, private judge delivery. | Access integration and login-sequence property tests. |
| Judges exhaust the owner's Codex allowance | Persistent global rolling provider-start limit, one-attempt accounting before every real invocation, bounded queue, fake-provider CI. | Restart, boundary, repair-attempt, and exhausted-product-use tests. |
| Path traversal crosses sessions | UUID parsing, normalized descendant check, no symlinks. | 1,000-sequence path property test. |
| SQL reaches kernel tables or filesystem | Statement allowlist, `_ppp_` denylist, no ATTACH/PRAGMA/extensions/file functions. | Migration and action PBT. |
| SSRF reaches loopback, private network, metadata, or DNS-rebinding target | HTTPS only, custom resolver, public-IP pinning, redirect revalidation. | URL/DNS/redirect property tests. |
| Connector exposes secret to model | Model sees alias and contract only; host injects header after policy. | Configuration redaction test. |
| Stale or malicious browser commits wrong code | Request-tab identity plus transaction/base/target version ACK. | Multi-tab E2E. |
| Browser rejects after server stage | Commit has not started; discard staging. | Coordinator integration test. |
| Crash splits source and SQLite | Prepared journal, before backup, metadata comparison, idempotent startup recovery. | Crash property test. |
| Stored prompt/source leaks through logs | Structured allowlisted log fields only. | Captured-log secret test. |
| Storage exhaustion deletes recovery state | No automatic pruning; reject new AI changes only. | Quota integration test. |
| Blob payload escapes into a host path or exceeds storage | Reserved SQLite bytes, base64/size/count validation, no path parameter, database quota. | Blob property and restore tests. |
| Rolled-back work emits a realtime event | Effect accumulator dispatched only after successful transaction, exact session/runtime broadcast. | Action/job/ingress rollback and cross-session event tests. |
| Job duplicates or repeats after crash/restore | Idempotency key, lease, bounded retry, terminal status, restore cancellation. | Clocked job properties and restart/restore integration. |
| Public ingress bypasses access or invokes another session | Parsed UUID/route registry, body/rate limits, optional constant-time HMAC verifier, session runtime lookup. | Public HTTP, verifier, rate-limit, and path properties. |
| Search query reaches another session or pathological compute | Per-database reserved index, escaped FTS terms, document/dimension/candidate/limit bounds. | Text/vector determinism and session isolation properties. |
| Client diagnostics leak secrets, inject provider instructions, or include extension noise | Exact active-frame source check, strict field allowlist and bounds, duplicate redaction/validation, no parent collector, volatile next-turn transport, and untrusted-evidence Skill wrapper. | PBT-18, provider stdin/Skill separation test, and active-frame browser regression. |

## 7. Access gate

The owner puts the hosted URL and shared password only in Devpost's private
judge instructions. Browser flow:

1. An unauthenticated browser renders a semantic password form.
2. POST `/api/login` is accepted only from the configured HTTPS Origin.
3. The server checks the kernel-observed remote-address throttle and compares
   the submitted value in constant time.
4. Success sets a signed, expiring, HttpOnly, Secure, SameSite=Strict cookie.
5. Bootstrap returns the complete shared `local` project list. The cookie has
   no person, owner, judge, or membership identity.
6. Logout expires the cookie and disposes browser authority without deleting
   projects.

`/#access=<code>` and `/api/access` remain disabled by default in production.
They may be explicitly enabled for local development and deterministic browser
tests; the client removes the fragment before exchange. A production deployment
must never publish a password-bearing URL.

Production refuses startup without `PPP_ACCESS_CODE` and a cookie secret of at least 32 random characters. Shared-password and cookie values never enter logs.

The access gate is sufficient only for the trusted hackathon release. Anyone
who knows the password can read and modify every project in `local`; this is an
intentional collaboration model, not a tenant boundary or audit identity.

It is also not the account system of a generated product. A game or internal
tool inside an authorized PPP session may have its own users. Those users are
scoped to the generated product database and receive no access to PPP sessions,
history, provider controls, or recovery UI.

## 8. CSRF and WebSocket

- Bootstrap returns a session-bound CSRF token.
- Every state-changing HTTP request requires the token in a custom header.
- Origin must match the configured public base URL.
- WebSocket upgrade requires the signed cookie and allowed Origin.
- First client message must subscribe with protocol version, session ID, and tab ID.
- Unknown message types and oversized frames close the connection.
- A socket can acknowledge only a stage sent to that exact tab and session.
- Product-auth cookies are accepted only after PPP access, Origin, and CSRF
  checks pass. They never authorize turn, restore, WebSocket, or session APIs.

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
- Do not return Codex reasoning, event text, raw JSONL, or diagnostics to the
  browser. The Kernel may return only a fixed product-language detail selected
  from allowlisted lifecycle metadata (`type`, item `type`, and start/complete
  state). It must discard all provider-authored text and unknown fields.
- Validate the final JSON twice.
- Before every real initial or repair invocation, atomically record one start
  in the Kernel-owned rolling ledger. Refuse starts beyond the configured
  100-per-60-minute default and preserve the refusal across JVM restart.
- Fake-provider work, generated actions, product jobs, and checkpoint restores
  do not touch the ledger. A rejected start receives no provider process.

The packaged provider Skill is instruction-only. It is copied into the child Codex CWD so that `codex exec` discovers it as a repository-scoped Skill, and the stdin prompt invokes it explicitly. It cannot access session files, run validation, connect to a raw REPL, or grant generated code a new capability. Host policy, temporary SQLite, server SCI, and browser SCI remain authoritative.

When bounded active-frame diagnostics exist, the Kernel may also create a
temporary `ppp-client-diagnostics` Skill in that same isolated job directory.
The diagnostics do not enter the normal stdin prompt, source tree, transcript
summary, history, or logs. The Skill labels every record as untrusted evidence,
contains only allowlisted one-line fields, and disappears with the job
directory. Codex choosing to read it grants no browser, network, filesystem, or
runtime authority.

Even if a provider ignores instructions, its result receives no authority until host validation and SCI staging succeed.

## 11. SCI boundary

### Server

Allowed:

- pure Clojure data and functions;
- explicitly copied `clojure.string` functions;
- action registration;
- parameterized query and mutation wrappers;
- restricted HTTP wrappers.
- typed product registration, login, logout, password maintenance, current-user,
  and required-user wrappers.
- typed blob, product-event, durable-job, ingress-registration, and
  full-text/vector-search wrappers.

Denied:

- Java classes and reflection;
- class loading, process APIs, threads, futures, agents, and blocking primitives;
- namespaces for IO, shell, readers that load tagged code, dynamic evaluation, and dependency loading;
- host Vars or registry atoms except through wrappers;
- unbounded output or evaluation time.
- raw credential hashes, login tokens, cookies, response headers, or an
  assignable authenticated-user context.

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

## 12. Product identity and session management

Product authentication is a Kernel capability because secure password storage,
opaque sessions, and response cookies require authority that generated code
must not receive.

```text
generated form
  -> opaque frame action bridge
  -> PPP access + Origin + CSRF
  -> resolve session-scoped product cookie
  -> generated action sees public current-user claims
  -> typed auth effect (optional)
  -> Kernel sets/clears HttpOnly product cookie
```

Controls:

- Passwords use Argon2id with unique `SecureRandom` salts. The production
  baseline follows the [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
  and [RFC 9106](https://www.rfc-editor.org/info/rfc9106/).
- Password input is length-bounded before expensive hashing. Passwords are
  never written to exceptions, logs, history, source, or response bodies.
- Unknown identifiers and wrong passwords execute the same verification class
  and return one public error, following the
  [OWASP Authentication guidance](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html).
- Login attempts are throttled per normalized identifier. Throttle state does
  not reveal whether the identifier exists.
- Login tokens contain 256 random bits. The database stores only an HMAC-SHA256
  digest bound to the PPP session ID.
- Cookies are UUID-scoped, `HttpOnly`, `SameSite=Strict`, path-restricted to the
  matching generated action endpoint, and `Secure` outside development. Path
  limits delivery but is not treated as an isolation boundary; the keyed token
  and matching database are authoritative. See
  [MDN secure cookie configuration](https://developer.mozilla.org/en-US/docs/Web/Security/Practical_implementation_guides/Cookies).
- Registration and credential changes rotate the login session. Password
  change and account deletion revoke every prior token.
- A checkpoint restore revokes every product login before activation, avoiding
  resurrection of captured or previously revoked tokens.
- Generated product roles and profiles live in normal generated tables keyed
  by the public auth user ID. Only the Kernel can assert that ID.

The generated client never receives a bearer token. This follows the
[OWASP session guidance](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)
to prefer HttpOnly cookies over browser storage for authentication material.

## 12.1 Session resource plane

The resource plane expands product behavior without expanding host authority:

- blobs are bytes in reserved SQLite rows; generated values never contain a
  host path and one object is capped at 4 MiB;
- search documents and FTS internals are reserved, session-local, and reachable
  only through bounded typed operations;
- jobs are data plus a named handler. Only the Kernel creates scheduler threads,
  claims leases, applies retry timing, and invokes the current session runtime;
- ingress is a fixed Kernel route that selects an already registered generated
  handler. Generated code cannot bind ports, add arbitrary paths, set cookies,
  redirect, or choose response headers;
- product events are post-commit effects. They contain no authority, are not
  replayed, and are routed only to current tabs of the exact session/runtime.

Blob/search/job tables remain under the `_ppp_` denylist. Resource payloads
must be Transit/JSON representable, bounded before storage or broadcast, and
excluded from application logs. Restore keeps durable blob/search content but
cancels every restored pending/running job. This avoids repeating historical
outbound effects. In-memory ingress rate buckets and product events intentionally
do not restore.

## 13. Source and filesystem

- Generated paths are forward-slash normalized relative paths.
- Reject absolute paths, empty components, `..`, backslashes after normalization, control characters, and disallowed extensions.
- Resolve beneath a known root and verify the normalized absolute candidate starts with that root.
- Use UUID parsing before constructing session paths.
- Do not follow symlinks during scans, copies, quota calculations, or deletion.
- Enumerate sessions from direct UUID-shaped directories only. Recursive
  no-follow scans tolerate only entries that disappear concurrently (for
  example SQLite WAL/SHM companions); every other I/O error remains visible.
- Atomic writes use a same-directory temporary file and atomic rename where supported.
- Session source and prompts never enter Codex workdir as files.

## 14. SQLite boundary

Kernel tables begin with `_ppp_`. Generated source cannot reference that prefix in any casing.

Reserved `_ppp_auth_*` tables may be read and written only by the product-auth
service. Generated SQL cannot enumerate their schema or infer credential data
through an auth capability response.

Reserved `_ppp_blobs`, `_ppp_jobs`, and `_ppp_search_*` tables may be read or
written only by the resource service. FTS5 shadow tables inherit the reserved
prefix. Generated SQL, schema enumeration, migration text, logical user-table
hashes, and action diagnostics do not expose their contents.

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

## 15. Outbound HTTP and SSRF

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

## 16. Named connectors

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

## 17. Data isolation and quotas

- Workspace is fixed to `local`, but each session owns a distinct normalized UUID directory and SQLite file.
- Every holder of the shared PPP cookie is authorized for every session in
  `local`. Directory separation contains generated runtimes; it does not
  provide privacy between judges.
- Generated actions receive only their session datasource.
- A session cannot name a database path.
- Quota checks include staging, journal backup, history, and checkpoints.
- At instance quota, new AI changes are rejected globally while read, actions, history, and restore remain available when they do not increase storage materially.
- No quota code deletes history or checkpoints automatically.
- Product identities are scoped by session database. Product-login cookies and
  keyed token digests include the parsed session UUID and cannot be replayed in
  another session.

## 18. Container hardening

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

The image contains the Codex binary but no OAuth state, shared password, session data, `.env`, or connector secrets.

## 19. Logging and diagnostics

Application logs use an allowlist of metadata fields. Exceptions are converted to stable codes before logging. Stack traces may appear only in local development and must still exclude prompt, source, SQL, request bodies, cookies, headers, and process environment.

Provider stderr is drained through a bounded in-memory buffer and discarded; it is never returned
to the user or written to application logs. Production logs record only provider error code, exit
status, and duration.

The Codex launcher and, when required, its Node interpreter are resolved to absolute paths before
the child environment is cleared. This preserves the environment allowlist without inheriting a
host `PATH` that could redirect executable lookup.

Product-auth logs contain only stable outcome codes and durations. They exclude
identifiers, passwords, tokens, cookie values, hashes, action bodies, and public
user claims.

Active generated-frame diagnostics are volatile user-turn context, not
application telemetry. They remain in the browser ring until the next turn or
runtime/session replacement, pass through strict normalization, and exist only
in the provider's temporary diagnostic Skill for that invocation. The host
never collects parent-window errors, so injected extension failures such as a
missing wallet extension cannot become model context.

Diagnostic messages are untrusted free text. The Kernel removes structured
payloads and applies credential-pattern, email, long-token, line, and size
redaction, but it cannot infer that every ordinary short word was private.
Generated products must not log user data. A public multi-tenant service needs
an explicit consent and data-classification review before enabling this
optional evidence path.

## 20. Backup and recovery

- Checkpoint SQLite snapshots use the live backup facility and are verified before gzip.
- Deployment backup captures persistent volumes while the app is quiesced or through a documented consistent procedure.
- Restore verifies manifest hashes, gzip integrity, database integrity, and logical content hash before staging.
- Journal recovery completes before readiness.
- A corrupt checkpoint is rejected without modifying current state.
- Image rollback does not automatically downgrade session format.
- Product credential records follow the selected database checkpoint, but
  active login-session and throttling rows are cleared before restore
  activation.

## 21. Security verification gate

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
- Argon2id parameter/encoding tests, credential redaction, generic login
  failures, throttle/revocation tests, and 1,000 cross-session token cases;
- three fresh-browser product signup/login/reload/logout paths, including one
  delayed sandbox load and one second-browser isolation path.
- blob size/count/hash/restore and host-path denial properties;
- post-commit event delivery plus rollback, stale-runtime, and cross-session
  non-delivery properties;
- job idempotency, lease recovery, bounded retry, cancellation, and
  post-restore non-execution properties;
- public ingress method/body/rate/session/HMAC verification properties;
- Unicode FTS and numeric-vector bound, determinism, and session-isolation
  properties;
- one compiled-browser resource-plane flow covering upload, search, delayed
  completion, second-tab event, ingress, reload, and restore.

Any critical trust boundary without both validation and failure handling blocks release.

## 22. Known residual risks

- SCI bugs could weaken interpreter isolation. Keep capability surface small and version pinned.
- A logically harmful action can remain within allowed SQL. Generated domain
  tests run before relevant server/shared/SQL commits against staged SQLite in a
  rollback-only transaction; outbound HTTP/connectors are disabled during that
  phase. Committed runtime startup does not replay them against mutable live
  user data. These tests and owner review reduce but do not eliminate semantic
  risk.
- OAuth account rate limits and policy can change independently of PPP.
- The shared judge password can be forwarded and every authenticated action is
  unattributed. Revocation rotates the one deployment secret and invalidates
  cookies operationally; there is no per-person revocation or audit identity.
- The provider-start ledger is single-process and filesystem-backed. The
  hackathon deployment therefore runs exactly one application replica; a
  public multi-replica service requires shared atomic metering.
- Single-process deployment has an availability limit.
- Product identity in this release is identifier/password only. Email
  ownership, MFA, social OAuth, and account recovery require configured
  connectors and their own consent and abuse review; generated code must not
  simulate those proofs.
- Client diagnostics are best-effort volatile evidence. A full reload before
  the next turn discards them; durable browser observability would require a
  separately reviewed encrypted and retention-bounded telemetry design.
- Outbound content can contain malicious data that influences later model turns. External responses should not automatically become privileged provider instructions.
- A public ingress can be abused within its rate/body budget. Products that
  cause valuable external effects must use a configured verifier alias and an
  idempotent generated rule.
- Background work is single-process and best-effort during downtime. Durable
  rows resume after restart, but the hackathon deployment does not promise
  distributed scheduling or exactly-once external delivery.

These risks are acceptable only for the stated gated hackathon and trusted self-host scope.
