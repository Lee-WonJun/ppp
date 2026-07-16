# PPP-023 On-demand Client Diagnostics Evidence

Date: 2026-07-17

## Outcome

PPP now preserves a small, current failure reason from the exact active
generated-product frame and makes it available to Codex only on the next user
turn. The evidence is optional and progressively disclosed through a temporary
job Skill, so successful and unrelated turns do not carry browser diagnostic
text.

The boundary is deliberately narrower than general browser telemetry. It
captures generated action, runtime, Promise, console warning/error, and direct
network failures inside the disposable opaque-origin frame. It excludes the
authenticated parent, DevTools, extensions, other tabs, request bodies,
headers, cookies, query values, source snapshots, and stack traces.

## Implementation evidence

- `src/ppp/client/frame.cljs` observes bounded active-frame failures and adds
  stable action metadata without changing the generated product API.
- `src/ppp/client/frame_host.cljs` accepts a diagnostic only from the exact
  active frame window and rejects staging, replaced, parent, and foreign
  sources.
- `src/ppp/client/core.cljs` owns a volatile, deduplicated 12-record ring,
  sends its current records only with the next explicit turn, and clears it
  when the project or active runtime changes.
- `src/ppp/shared/protocol.cljc` defines the single strict normalization,
  redaction, truncation, URL minimization, and ring contract used at browser
  and server boundaries.
- `src/ppp/http.clj` and `src/ppp/coordinator.clj` reject malformed diagnostic
  input before provider admission and carry accepted records transiently; no
  history or session persistence path receives the field.
- `src/ppp/provider/codex.clj` writes the records only to the isolated job's
  temporary `.agents/skills/ppp-client-diagnostics/SKILL.md`. It does not put
  them in ordinary provider stdin, and the entire job directory is removed
  after the provider call.
- The packaged validation Skill reads that optional Skill only when the user
  is asking about a broken product. The evidence is labelled untrusted and
  grants no additional browser, server, filesystem, or process authority.

## Domain, property, and security evidence

PBT-18 ran at least 1,000 generated diagnostic shapes and strings with retained
seed `23018`. It proves that every accepted record:

- contains only the allowlisted keys for its kind;
- is one line and remains within every field and ring bound;
- strips URL query and fragment data;
- redacts credential-like text and bearer-shaped values;
- deduplicates without exceeding 12 newest records;
- is rejected as a whole when its structure is malformed.

Provider tests prove a diagnostic sentinel is absent from stdin and exists
only in the temporary Skill when records are present. Coordinator and HTTP
tests prove the field remains transient and malformed or oversized input fails
before queueing. No test or application log prints prompt, generated source,
credential, diagnostic content, or provider process environment.

## Compiled-browser evidence

The release-built Chromium regression exercised the reported product path
directly:

1. generated UI invoked `auth/register` with an invalid identifier;
2. the action returned HTTP 400;
3. the active frame captured action ID `auth/register`, status `400`, its
   stable failure code, and `Use a valid sign-in identifier.`;
4. runtime, unhandled-Promise, console, and direct-network diagnostic paths
   were also exercised;
5. a parent-window sentinel shaped like browser-extension/MetaMask noise never
   appeared in the active-product ring;
6. the next turn carried the bounded records to the provider boundary without
   exposing them in the visible conversation.

The stable `http://localhost:8787/` surface then passed an additional real
Chromium smoke with an intentionally missing generated action. It captured the
active-frame action reason, excluded the parent sentinel, and intercepted the
next provider request without spending a real Codex invocation.

## Isolated release gate

A detached candidate repository passed `bb verify` before promotion:

- lint and formatting: clean;
- JVM: 178 tests, 1,297 assertions;
- ClojureScript: 27 tests, 125 assertions;
- normal Chromium suite: 26 total, 25 passed and one intentionally skipped;
- production hosted-access restart harness: both phases passed across three
  fresh browser contexts;
- packaged Docker: Linux amd64 build, non-root/read-only-root smoke,
  Codex-home persistence, and data-volume backup/restore passed;
- secret scan: 189 candidate files clean.

`bb verify` used the fake provider and made no OAuth or live model call. The
first detached run exposed only that the temporary copy lacked Git ignore
metadata; after initializing the detached copy as a repository, the complete
gate passed unchanged. The root repository secret scan remained clean in both
cases.

## Stable owner surface

The host and opaque-frame browser bundles were compiled and tested together in
the detached copy, then promoted as one pair. The breaking pre-release runtime
change kept capability version `1`. Development sessions were reset, the JVM
was restarted to clear its runtime registry, and `/readyz` reported Codex OAuth
ready with zero sessions, zero runtimes, and no pending stage.

## Completeness decision

PPP-023 satisfies the active-frame source boundary, bounded/redacted record,
volatile next-turn transport, progressive Skill disclosure, exact reported
action failure, parent/extension exclusion, browser promotion, session reset,
and full release-gate acceptance criteria. Persistent analytics, automatic
provider turns, parent-window collection, and a user-visible developer console
remain deliberately out of scope.
