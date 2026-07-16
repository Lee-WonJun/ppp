# PPP-021 Complete Resource Plane Evidence

Date: 2026-07-17

## Outcome

PPP's generated-product sandbox now has a complete ordinary session-owned
resource plane. Generated products can compose durable binary objects,
post-commit multi-tab events, background and scheduled jobs, bounded public
ingress, and Unicode full-text or caller-supplied vector search while retaining
the existing browser, SQLite, account, outbound HTTP, connector, checkpoint,
and restore capabilities.

These capabilities do not expose a host path, filesystem, shell, process,
thread, listener, socket, credential, Kernel table, another session, or the
authenticated parent window. Every durable resource belongs to one session
SQLite database and follows the same quota, stage, commit, checkpoint,
restore, and failure-observability boundary as generated business data.

## Implementation evidence

- `src/ppp/runtime/resources.clj` owns bounded blob metadata/content, product
  events, durable jobs and leases, ingress registration, and Unicode
  full-text/vector search in reserved session tables.
- `src/ppp/scheduler.clj` claims due work, invokes only the current registered
  handler, commits database mutation and effects together, applies bounded
  retry/backoff and idempotency, recovers expired leases, and records terminal
  failures.
- `src/ppp/runtime/server.clj` exposes the typed resource capabilities inside
  server SCI without exposing SQLite connections, scheduler ownership, or host
  resources.
- `src/ppp/http.clj` provides the public ingress adapter with exact
  session/route containment, method/body/rate policy, optional developer-owned
  verifier aliases, and bounded JSON results without PPP or product cookies.
- `src/ppp/websocket.clj`, `src/ppp/client/frame_host.cljs`, and
  `src/ppp/client/runtime.cljs` forward accepted post-commit product events only
  to active frames for the exact session and runtime.
- `src/ppp/runtime/policy.cljc` remains the single capability catalog used by
  evaluator exposure and provider guidance. Its pre-release capability version
  remains `1`.
- The fake provider and packaged validation Skill compose all five resource
  types through the same generated source and staging path used by Codex.

## Domain, property, and security evidence

The JVM release suite ran 163 tests with 985 assertions. Named resource-plane
properties each exercised at least 1,000 generated cases or sequences:

- PBT-11 preserves exact blob bytes, size, SHA-256, metadata, and restore state;
  rejected over-limit or path-shaped input leaves the prior resource intact.
- PBT-12 delivers accepted events only after commit to eligible tabs on the
  exact session/runtime and emits nothing for rollback or isolation failures.
- PBT-13 preserves job idempotency, attempt bounds, cancellation, terminality,
  expired-lease recovery, and restore-time non-execution.
- PBT-14 prevents arbitrary method, route, session, body, header, rate, and
  signature inputs from bypassing ingress policy.
- PBT-15 keeps arbitrary Unicode documents and finite vectors bounded,
  deterministic, and isolated to the selected session.

Integration coverage also proves blob/database quota preservation, exact-byte
checkpoint restore, scheduler execution without a user action, public ingress
status and body bounds, HMAC-secret non-disclosure, reserved-table denial,
cross-session rejection, and stage-specific failure categories.

## Isolated release gate

An exact detached candidate passed the complete `bb verify` gate before either
browser bundle was promoted:

- lint and formatting: clean;
- JVM: 163 tests, 985 assertions;
- ClojureScript: 25 tests, 110 assertions;
- Chromium: 23 of 23 semantic tests;
- packaged Docker: Linux amd64 build, non-root/read-only-root smoke,
  Codex-home persistence, and backup/restore passed;
- secret scan: 176 bounded project files, clean.

The Chromium suite includes the complete resource workbench plus three fresh
composer contexts, three fresh product-account contexts, three animation-free
runtime contexts, Korean IME, a delayed cold frame beyond five seconds,
timer-driven Tetris, keyboard input, Canvas, WebAssembly, opaque-parent
isolation, SQLite actions, multi-tab resync, and Safe Mode recovery.

## Live Codex evolution

The explicit OAuth evolution gate used one fresh product and one resumed Codex
thread for all eight ordered outcomes. It evolved dark and floating visuals,
timer/keyboard Tetris, SQLite ranking, a changed server rule, Gomoku, generated
product accounts, and finally the complete resource workbench without removing
earlier unrelated behavior.

All eight scenarios passed. The final resource scenario stored and reloaded a
binary asset, found a Unicode search record, completed durable scheduled work
without another user action, delivered the resulting event to a second tab,
and accepted a bounded public ingress request. A semantic miss was returned to
the same thread through the bounded repair loop; five repair versions remained
in history, each passed staging and report coverage, and the final browser
outcome passed. The sanitized report contains eight passes, zero failures,
continuous thread and event coverage, and two generated product tables:

- `artifacts/evolution-eval/20260716-141959/report.edn`

Raw observations, session identifiers, traces, and provider output remain
ignored local diagnostics.

## Stable owner surface

After the isolated gate, the authenticated host and opaque product-frame
bundles were promoted together while port `8787` was stopped. Development
sessions were reset, then the complete 23-test Chromium suite passed directly
against the owner-facing `8787` process in fresh browser contexts. The suite
included the delayed cold-frame and full resource-plane paths.

The verification sessions were then deleted and the JVM restarted in real
Codex OAuth mode. Final readiness reported a ready provider, zero stored
sessions, zero registered runtimes, zero WebSocket connections, and zero
pending stages. Kernel and OAuth state were preserved. A final documentation
hygiene pass reported zero lint warnings/errors, clean formatting, clean diff
whitespace, and a clean 177-file secret scan.

## Completeness decision

The maintained resource ledger in `TODOS.md` contains no remaining known
ordinary session-owned resource gap. Future product requests should compose
the existing primitives. Host escape, credential access, cross-session access,
arbitrary listener/process ownership, and host dependency mutation remain
permanent authority boundaries rather than deferred product features.
