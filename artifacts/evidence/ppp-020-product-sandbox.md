# PPP-020 Product Sandbox Evidence

Date: 2026-07-16

## Outcome

PPP now treats the generated product as a session-owned full-stack sandbox,
not as a list of pre-approved application categories. Generated code can use
normal browser behavior in the opaque frame and can compose typed server
resources for SQLite data, product identity, public HTTP, and named
connectors. PPP access, Kernel data, credentials, host resources, processes,
and other sessions remain outside generated authority.

The first new server resource is generated-product identity. A generated
product can register, sign in, sign out, recover its signed-in state after a
reload, change a password, delete an account, and protect ordinary generated
business actions. The generated source receives only bounded public user
claims. It never receives the login token, cookie value, credential hash,
reserved identity tables, or raw response control.

## Implementation evidence

- `src/ppp/runtime/auth.clj` owns identifier normalization, bounded Argon2id
  credentials, opaque session tokens, keyed token digests, throttling,
  rotation, revocation, and session-specific cookie metadata.
- `src/ppp/runtime/server.clj` exposes typed product-account functions and
  request-scoped public user claims to generated server actions.
- `src/ppp/http.clj` resolves and applies product login effects after the PPP
  access, origin, and CSRF boundaries. Effects are removed from JSON bodies.
- `src/ppp/coordinator.clj` revokes live product logins after checkpoint
  restore while restoring account records with the selected SQLite snapshot.
- `src/ppp/runtime/policy.cljc` remains the single capability catalog. Its
  capability version remains `1` during pre-release development.
- `src/ppp/client/runtime.cljs` adds declarative initial product state and
  activation-safe interval registration. A timer registered at source
  evaluation cannot mutate staging state, begins after activation, and is
  disposed with its frame.
- The Codex provider instructions and packaged validation Skill distinguish
  PPP workspace access from accounts belonging to a generated product.
- The deterministic fake provider contains a complete account product with a
  generated profile table and protected points action.

## Automated verification

An isolated candidate copy passed the complete `bb verify` release gate before
the host and frame bundles were promoted together:

- lint and formatting: clean;
- JVM: 141 tests, 814 assertions;
- ClojureScript: 24 tests, 104 assertions;
- Chromium: 22 of 22 tests;
- packaged Docker: amd64 build, non-root/read-only smoke, persistence and
  backup checks passed;
- secret scan: 167 files, clean.

Focused account coverage includes:

- two 1,000-case properties for cookie/session isolation and deterministic
  Unicode identifier normalization;
- registration uniqueness, common credential failure, login throttling,
  password rotation, cross-session rejection, and restore revocation;
- real coordinator and HTTP action transport proving HttpOnly,
  `SameSite=Strict`, session-scoped cookies and JSON redaction;
- three fresh Chromium contexts proving signup, protected product data,
  reload, logout, wrong-password recovery, correct re-login, and
  cross-context isolation. One fresh frame bundle was delayed by 6.5 seconds;
- opaque-frame code could not read the parent-managed product cookie.

After promotion, the owner-facing `localhost:8787` surface passed the original
cold-session render path in three additional fresh browser contexts, including
one 6.5-second delayed frame. The four sessions created by the check were then
deleted, the JVM was restarted, and readiness reported zero stored sessions,
zero runtime registrations, and a ready Codex OAuth provider.

## Live provider evolution

The explicit live evolution evaluator used one resumed Codex thread and the
real generated source/staging/action path. All seven ordered outcomes passed:

1. dark theme;
2. floating product conversation;
3. timer-driven, keyboard-controlled Tetris;
4. SQLite-backed ranking that survives reload;
5. replacement of a server scoring rule;
6. replacement of Tetris with Gomoku while ranking remains available;
7. the owner request to implement login and signup for the game, followed by
   signup, authenticated reload, protected score persistence, logout, and
   preserved Gomoku behavior.

The sanitized report records 7 passed, 0 failed, one continuous provider
thread, ordered scenarios, and two generated product tables. The fourth
scenario exercised the provider repair loop and succeeded on its third
generation attempt. The bounded local report is
`artifacts/evolution-eval/20260716-101621/report.edn`; raw observations remain
ignored.

## Defects found by outcome testing

- A source-level `api/start-interval!` call previously returned without
  registering during evaluation, so generated Tetris rendered but never fell.
  Registration is now permitted before activation, callbacks are activation
  gated, and replacement/rejection clears the timer. The browser conformance
  test uses this public convenience instead of bypassing it with a native
  timer.
- The first account evolution was rejected by the evaluator only because it
  looked for an exact unrequested heading despite the form, buttons, server
  actions, and behavior being present. The evaluator now checks semantic
  controls and actual account behavior rather than incidental prose.

## Remaining ordinary sandbox resources

Durable binary blobs, product pub/sub, durable jobs/schedules, inbound
webhooks, and richer search remain explicit resource gaps in `TODOS.md`. They
are not described to users or the provider as intrinsically impossible. Each
must be added as a session-owned, quota-bounded resource with checkpoint,
failure, security, fake-provider, and real-browser evidence. Host escape,
credential access, and cross-session authority are permanent prohibitions,
not product gaps.
