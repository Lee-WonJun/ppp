# Requirements Traceability

Status: implementation control document
Last updated: 2026-07-16

## Rules

- Every product requirement must map to at least one implementation ticket and one verification target.
- A ticket may become `done` only when all acceptance criteria and named evidence exist.
- A passing narrow unit test cannot prove a broader browser, crash, security, or packaging requirement.
- Test names below are intended stable targets. If a name changes, update this document in the same change.
- Manual evidence is permitted only for visual judgment, video, and Devpost fields. Domain behavior requires executable evidence.

## Product requirement matrix

| Requirement | Ticket | Verification target | Release evidence |
|---|---|---|---|
| PRD-F01 | PPP-002 | `ppp.access-test/fragment-exchange-contract`, Playwright access flow | E-01, E-03 |
| PRD-F02 | PPP-002 | `ppp.access-test/csrf-required` | E-01 |
| PRD-F03 | PPP-002, PPP-006 | Playwright `literal blank first frame` | E-01, E-04 |
| PRD-F04 | PPP-006 | Playwright shortcut and handle-hold Safe Mode | E-01, E-04 |
| PRD-F05 | PPP-006 | Playwright broken-sidebar recovery | E-01, E-04 |
| PRD-F06 | PPP-003 | `ppp.session.store-test/new-session-is-a-complete-version-zero-product` | E-01, E-05 |
| PRD-F07 | PPP-003, PPP-006 | Playwright session switch persistence | E-01, E-03 |
| PRD-F08 | PPP-007, PPP-015 | prompt limit and `202` route integration; CLJS and Chromium Enter-to-send contract | E-01 |
| PRD-F09 | PPP-004, PPP-007 | `ppp.provider.queue-test/provider-queue-is-global-fifo-with-eight-waiting-slots` | E-01 |
| PRD-F10 | PPP-007, PPP-015 | protocol schema plus WebSocket integration; guarded keyboard submission | E-01 |
| PRD-F11 | PPP-006, PPP-007 | hidden render and no-refresh Playwright | E-01, E-03 |
| PRD-F12 | PPP-005 | `ppp.runtime.server-test/initial-runtime-stages-registers-and-invokes` | E-01 |
| PRD-F13 | PPP-005 | `ppp.property.gallery-domain-test/fake-gallery-seeds-once-persists-and-ranks-deterministically` | E-01, E-03 |
| PRD-F14 | PPP-006, PPP-013, PPP-015 | sandbox frame state handoff and replacement Playwright test; composer draft/focus keyboard test | E-01 |
| PRD-F15 | PPP-004, PPP-005, PPP-013 | JVM escape property plus sandbox browser-API Playwright test | E-01, E-02 |
| PRD-F16 | PPP-001, PPP-005, PPP-007 | `ppp.runtime.policy-test/forbidden-sql-token-property` | E-01 |
| PRD-F17 | PPP-005, PPP-007 | `ppp.runtime.sqlite-test/failed-stage-database-never-mutates-live-content` | E-01 |
| PRD-F18 | PPP-006, PPP-007, PPP-013 | hidden opaque-origin frame Playwright stage | E-01 |
| PRD-F19 | PPP-007 | exact request-tab ACK integration | E-01 |
| PRD-F20 | PPP-007 | PBT-08 and stale multi-tab Playwright | E-01 |
| PRD-F21 | PPP-007, PPP-010 | PBT-02 | E-01 |
| PRD-F22 | PPP-003, PPP-007 | `ppp.session.store-test/history-sequences-remain-unique-under-concurrency` | E-01, E-05 |
| PRD-F23 | PPP-003 | `ppp.session.store-test/current-source-must-match-its-manifest` | E-01 |
| PRD-F24 | PPP-008 | snapshot integrity and checkpoint test | E-01 |
| PRD-F25 | PPP-008 | PBT-04 crash-point matrix | E-01 |
| PRD-F26 | PPP-008 | PBT-03 and thread reset test | E-01, E-03 |
| PRD-F27 | PPP-004 | `ppp.provider.codex-test`, `ppp.provider.fake-test` | E-01, E-02 |
| PRD-F28 | PPP-001, PPP-004 | `ppp.shared.protocol-test/provider-kind-boundary-property` | E-01 |
| PRD-F29 | PPP-009 | PBT-06 and redirect integration | E-01 |
| PRD-F30 | PPP-009 | connector secret redaction/injection tests | E-01 |
| PRD-F31 | PPP-013 | parent DOM/cookie/origin isolation Playwright suite | E-01, E-02 |
| PRD-F32 | PPP-014 | impact-classification PBT, client-only registry reuse integration, `bb eval-evolution` | E-01, E-07 |

## Property mapping

| Property | Owner ticket | Test namespace |
|---|---|---|
| PBT-01 monotonic version | PPP-007 | `ppp.property.coordinator-test` |
| PBT-02 rejection preserves state | PPP-007, PPP-010 | `ppp.property.coordinator-test` |
| PBT-03 restore round-trip | PPP-008 | `ppp.property.restore-test` |
| PBT-04 recovery idempotence | PPP-008 | `ppp.property.recovery-test` |
| PBT-05 session path isolation | PPP-003 | `ppp.session.store-test/session-path-containment-property` |
| PBT-06 SSRF boundary | PPP-009 | `ppp.property.http-policy-test` |
| PBT-07 scoring and ties | PPP-005, PPP-010 | `ppp.property.gallery-domain-test/generated-scoring-and-tie-property` |
| PBT-08 stale browser resync | PPP-007 | `ppp.property.coordinator-test` plus Playwright |

## Ticket dependency graph

```text
PPP-001
  ├── PPP-003 ── PPP-004
  ├── PPP-005
  └── PPP-002 ── PPP-006
                   |
                   v
                 PPP-007 -> PPP-008 -> PPP-009
                              |
                              v
                           PPP-013 -> PPP-010 -> PPP-011 -> PPP-012
                              |
                              +---------------------------> PPP-014
                              |
                              +---------------------------> PPP-015
                              |
                              +---------------------------> PPP-016
```

## Completion ledger

| Ticket | Status | Evidence summary |
|---|---|---|
| PPP-001 | done | 30/30 requirements traced; JVM and CLJS schema tests, lint, format, release build, and npm audit pass. |
| PPP-002 | done | 10 JVM tests/47 assertions plus Chromium access, blank-frame, session/reload flow; visual evidence recorded. |
| PPP-003 | done | 21 JVM tests/117 assertions; 2,000 path cases, 100 manifest cases, concurrent append, all quota boundaries, lint/format/CLJS clean. |
| PPP-004 | done | 33 JVM tests/242 assertions; 1,000 provider boundaries; fake-process argv/env/stdin/failure suite; FIFO queue; real OAuth preflight ready. |
| PPP-005 | done | 49 JVM tests/298 assertions; 100 SCI escapes, 1,000 SQL cases, 1,000 scoring/tie cases; online backup and live-hash isolation proven. |
| PPP-006 | done | 50 JVM/307 assertions; 10 CLJS/63 assertions; release build plus two consecutive 2/2 Chromium runs; hidden sidebar, state, browser-network, Safe Mode, mobile, focus, and reduced-motion evidence. |
| PPP-007 | done | Async 202 route; 14 access/WS tests; three 1,000-case coordinator properties; two-tab no-refresh Playwright resync. |
| PPP-008 | done | 1,000 restore and 1,000 recovery cases; corruption/crash/concurrent-backup matrices; old/future checkpoint Playwright restore. |
| PPP-009 | done | HTTPS/DNS/header/connector boundary; four 1,000-case PBTs; controlled TLS integration; 43 focused tests/287 assertions. |
| PPP-010 | done | PBT-01 through PBT-08 each run 1,000 seeded cases; full gate passes 126 JVM/707 assertions, 17 CLJS/73 assertions, eight browser paths, package smoke, and a 690-file secret scan; live OAuth evaluation passes 24/24 with every scenario at 3/3. |
| PPP-011 | done | Clean amd64 multi-stage image, non-root/read-only smoke, exact two-volume Compose contract, cross-container Codex-home persistence, fake full-stack package flow, quiesced backup/fresh-volume restore, secret scan, and CI workflow pass locally. |
| PPP-012 | done | Exact packaged demo passes 3/3 on fresh volumes; 175-second script and English SRT exist; README and Devpost draft are implementation-accurate; internal rubric is 97/100; publication fields remain owner-controlled placeholders. |
| PPP-013 | done | Opaque frame, general JS/browser interop, detailed failures, cold startup, SQLite activation, fake plus real-Codex Tetris, and channel-verified replacement/rejection/restore/resync/session-switch/Safe-Mode disposal pass. |
| PPP-014 | done | Host-derived impact runs 1,000 generated path sets; `bb verify` passes 132 JVM/750 assertions, 17 CLJS/73 assertions, eight browser paths, Docker smoke, and secret scan; the real OAuth six-turn evolution report passes 6/6 in one resumed thread, including repair, SQLite persistence, server-rule replacement, and client-only game replacement; the clean `localhost:8787` access/input/reload smoke also passes before the final zero-session restart. |
| PPP-015 | done | Enter-to-send and Shift+Enter pass 21 CLJS tests/89 assertions; isolated release Chromium passes 11/11 including three fresh composer contexts and a 6.5-second delayed frame; stable `localhost:8787` passes against real OAuth Codex; JVM 132/750, lint, format, and secret scan remain clean before the final zero-session restart. |
| PPP-016 | done | Browser-interop and Enter contracts reconciled; clean candidate-copy `bb verify` passes 132 JVM/750 assertions, 21 CLJS/89 assertions, Chromium 11/11, Docker smoke, and secret scan; Enter-based packaged demo passes 3/3; 155-file repository baseline is clean; stable 8787 is Codex-ready with zero sessions/runtimes; local commit `e83dc0d` records the reproducible source without external publication. |
