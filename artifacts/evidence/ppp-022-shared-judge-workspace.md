# PPP-022 Shared Judge Workspace Evidence

Date: 2026-07-17

## Outcome

PPP now has a production-shaped shared judge entry flow. One owner-configured
password opens one common `local` workspace, Projects lists the same named
products for every authorized browser, and judges can create or reopen a
project without receiving fabricated accounts, ownership, or private copies.

The owner's real Codex usage is protected by a persistent global rolling start
budget. Initial generations and repair attempts consume the same bounded
capacity immediately before process start. Product actions, SQLite data,
history, checkpoints, restores, Safe Mode, project navigation, and logout keep
working while new AI changes are unavailable.

## Implementation evidence

- `src/ppp/access.clj` provides constant-time shared-password verification,
  origin-bound login, signed-cookie logout, and per-kernel-observed-address
  rolling failure throttling without retaining submitted passwords.
- `src/ppp/client/core.cljs` renders immutable Login and Projects surfaces,
  bounded named-project creation, common project navigation, deep-link
  recovery, logout disposal, and nontechnical change-capacity state.
- `src/ppp/provider/budget.clj` owns the atomic no-symlink rolling ledger under
  `data/kernel`, exact boundary pruning, restart-safe admission, fail-closed
  parsing, and read-only owner inspection.
- `src/ppp/coordinator.clj` checks capacity before admission and records one
  start immediately before every real initial or repair provider invocation.
- `src/ppp/http.clj` exposes production login/logout, disables fragment access
  unless explicitly configured, returns bounded `Retry-After` on synchronous
  exhaustion, and preserves the existing project/action/restore contracts.
- `bb provider-capacity` and `bb reset-provider-capacity` operate only on the
  configured data directory and print bounded ledger metadata.

## Domain, property, and security evidence

The final JVM suite ran 174 tests with 1,262 assertions.

- PBT-16 ran 1,000 generated rolling-window sequences. It proves admission
  never exceeds the configured limit, the oldest start expires exactly at the
  boundary, and a restarted budget makes the same decision from persisted
  state.
- PBT-17 ran 1,000 generated login sequences. It proves failure buckets remain
  isolated by the kernel-observed remote address, public failures do not reveal
  password-match detail, and admission recovers after the rolling window.
- Corrupt, oversized, or symlink-shaped provider ledger state fails closed.
- Fake-provider work creates no ledger and consumes no real-provider capacity.
- Repair attempts consume capacity, while a capacity rejection preserves the
  source manifest, logical SQLite content, runtime version, and checkpoints.
- A generated SQLite-backed action and checkpoint restore both succeed while
  new provider starts are exhausted.
- Project title tests enforce trimmed, nonblank, control-free Unicode titles of
  at most 80 code points without weakening the complete version-zero product.

## Production-configured browser evidence

`bb hosted-access-e2e` boots PPP with production configuration, fragment access
disabled, a Secure/HttpOnly/SameSite=Strict cookie, and one persistent data root.
It passes these fresh-browser outcomes:

1. A fragment does not authenticate and is removed from the visible URL.
2. The first browser signs in, creates a named project, and opens its literal
   blank product canvas.
3. A second independent browser context signs in, sees the same project, opens
   the same session, and receives the same blank runtime.
4. The JVM stops and restarts against the same data root.
5. A third fresh context signs in, sees the persisted project, opens it, and
   loads the conversation surface.

The normal semantic Chromium suite also passed 25 tests, covering Login,
Projects, logout, capacity presentation, existing runtime evolution, Korean
IME, delayed cold-frame loading, timer-driven browser behavior, Canvas,
WebAssembly, SQLite actions, restore, multi-tab resync, and Safe Mode.

## Isolated release gate

A detached candidate repository passed `bb verify` before closure:

- lint and formatting: clean;
- JVM: 174 tests, 1,262 assertions;
- ClojureScript: 25 tests, 110 assertions;
- normal Chromium suite: 25 semantic tests passed;
- production hosted-access restart harness: both phases passed across three
  fresh browser contexts;
- packaged Docker: Linux amd64 build, non-root/read-only-root smoke,
  Codex-home persistence, and data-volume backup/restore passed;
- secret scan: clean.

`bb verify` used the fake provider and made no OAuth or live model call.

## Stable owner surface

The authenticated host and opaque product-frame bundles were built together in
a temporary repository copy, passed focused browser gates, and were promoted as
one pair. The owner-facing `http://localhost:8787/` surface then passed a real
browser sign-in, Projects creation, blank runtime, conversation, and zero-console
error smoke. The reviewed Projects screenshot is:

- `artifacts/evidence/ppp-022-stable-projects.png`

Development sessions are reset after this evidence is captured so the final
owner surface starts from an empty shared Projects list while preserving Kernel
and OAuth state.

## Completeness decision

PPP-022 satisfies its shared-password, common Projects, persistent provider
budget, exhausted-state continuity, production restart, browser, package, and
documentation acceptance criteria. External push, image publication,
deployment, password delivery, video upload, and Devpost submission still
require explicit owner approval.
