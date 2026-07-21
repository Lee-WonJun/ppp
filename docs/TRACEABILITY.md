# Requirements Traceability

Status: implementation control document
Last updated: 2026-07-22

## Rules

- Every product requirement must map to at least one implementation ticket and one verification target.
- A ticket may become `done` only when all acceptance criteria and named evidence exist.
- A passing narrow unit test cannot prove a broader browser, crash, security, or packaging requirement.
- Test names below are intended stable targets. If a name changes, update this document in the same change.
- Manual evidence is permitted only for visual judgment, video, and Devpost fields. Domain behavior requires executable evidence.

## Product requirement matrix

| Requirement | Ticket | Verification target | Release evidence |
|---|---|---|---|
| PRD-F01 | PPP-002, PPP-022, PPP-028, PPP-029, PPP-030 | shared-password/fragment-policy integration, production Playwright login, judge quick-test guidance, product lineage, and vision-versus-POC positioning | E-01, E-03, E-14, E-20, E-21, E-22 |
| PRD-F02 | PPP-002 | `ppp.access-test/csrf-required` | E-01 |
| PRD-F03 | PPP-002, PPP-006, PPP-017, PPP-022, PPP-024, PPP-025, PPP-032, PPP-033, PPP-034 | Playwright Projects entry, named blank project, literal blank canvas, hidden-frame animation suppression, and fresh public-server final narrative film | E-01, E-04, E-14, E-16, E-17, E-24, E-25, E-26 |
| PRD-F04 | PPP-006 | Playwright shortcut and handle-hold Safe Mode | E-01, E-04 |
| PRD-F05 | PPP-006, PPP-017 | Playwright broken-sidebar recovery and render-timeout preservation | E-01, E-04 |
| PRD-F06 | PPP-003, PPP-022 | complete version-zero product plus bounded-title HTTP/browser creation | E-01, E-05, E-14 |
| PRD-F07 | PPP-003, PPP-006, PPP-022, PPP-025 | shared two-browser project listing, session switch persistence, and readable Projects video opening | E-01, E-03, E-14, E-17 |
| PRD-F08 | PPP-007, PPP-015, PPP-018, PPP-019, PPP-031, PPP-035 | prompt limit and `202` route integration; CLJS and Chromium Enter/IME composer, live status, Workspace REPL turn contract, and typed public-film prompt entry | E-01, E-23, E-27 |
| PRD-F09 | PPP-004, PPP-007 | `ppp.provider.queue-test/provider-queue-is-global-fifo-with-eight-waiting-slots` | E-01 |
| PRD-F10 | PPP-007, PPP-015, PPP-018, PPP-019, PPP-035 | protocol schema plus WebSocket integration; guarded keyboard/composition submission, bounded phase presentation, and continuous accelerated wait evidence | E-01, E-27 |
| PRD-F11 | PPP-006, PPP-007, PPP-017, PPP-024, PPP-025, PPP-031, PPP-032, PPP-033, PPP-034, PPP-035 | hidden render, animation-independent DOM commit, no-refresh Playwright, active browser form evaluation, and fresh public dark-theme/Snake/Tetris film evidence | E-01, E-03, E-16, E-17, E-23, E-24, E-25, E-26, E-27 |
| PRD-F12 | PPP-005, PPP-024, PPP-025, PPP-031, PPP-032, PPP-033, PPP-034 | server action integration, live nREPL action redefinition/invocation, and a public film showing `+100` then `×3` through the same browser action | E-01, E-16, E-17, E-23, E-24, E-25, E-26 |
| PRD-F13 | PPP-005 | `ppp.property.gallery-domain-test/fake-gallery-seeds-once-persists-and-ranks-deterministically` | E-01, E-03 |
| PRD-F14 | PPP-006, PPP-013, PPP-015, PPP-018 | sandbox state handoff; composer draft/focus tests; revision-ordered Korean IME regression | E-01 |
| PRD-F15 | PPP-004, PPP-005, PPP-013, PPP-030, PPP-031 | JVM escape property, sandbox browser-API Playwright test, current-versus-target runtime boundary, and development-only loopback nREPL policy | E-01, E-02, E-22, E-23 |
| PRD-F16 | PPP-001, PPP-005, PPP-007 | `ppp.runtime.policy-test/forbidden-sql-token-property` | E-01 |
| PRD-F17 | PPP-005, PPP-007, PPP-024, PPP-025, PPP-031, PPP-032, PPP-033 | failed-stage isolation, captured real-Codex authenticated ranking reload persistence, live migration/action observation, pre-turn SQLite rollback, and continuous final-film persistence evidence | E-01, E-16, E-17, E-23, E-24, E-25 |
| PRD-F18 | PPP-006, PPP-007, PPP-013, PPP-017 | hidden opaque-origin frame Playwright stage with animation scheduling disabled | E-01 |
| PRD-F19 | PPP-007 | exact request-tab ACK integration | E-01 |
| PRD-F20 | PPP-007 | PBT-08 and stale multi-tab Playwright | E-01 |
| PRD-F21 | PPP-007, PPP-010, PPP-017, PPP-031 | PBT-02 plus failed/unsignaled DOM preservation and terminal live-turn reconstruction | E-01, E-23 |
| PRD-F22 | PPP-003, PPP-007, PPP-031 | concurrent append uniqueness plus semantic nREPL operation history | E-01, E-05, E-23 |
| PRD-F23 | PPP-003, PPP-031 | current source/manifest consistency and accepted runtime-to-source reconciliation | E-01, E-23 |
| PRD-F24 | PPP-008, PPP-031 | snapshot integrity, checkpoint tests, and Workspace REPL pre-turn database backup | E-01, E-23 |
| PRD-F25 | PPP-008 | PBT-04 crash-point matrix | E-01 |
| PRD-F26 | PPP-008, PPP-031 | PBT-03, thread reset, and restored Workspace REPL session reconstruction | E-01, E-03, E-23 |
| PRD-F27 | PPP-004, PPP-028, PPP-029, PPP-030, PPP-031 | provider tests, majority-build session guide, nREPL lineage/distinction, and implemented Codex-operated Workspace REPL profile | E-01, E-02, E-20, E-21, E-22, E-23 |
| PRD-F28 | PPP-001, PPP-004 | `ppp.shared.protocol-test/provider-kind-boundary-property` | E-01 |
| PRD-F29 | PPP-009 | PBT-06 and redirect integration | E-01 |
| PRD-F30 | PPP-009 | connector secret redaction/injection tests | E-01 |
| PRD-F31 | PPP-013 | parent DOM/cookie/origin isolation Playwright suite | E-01, E-02 |
| PRD-F32 | PPP-014 | impact-classification PBT, client-only registry reuse integration, `bb eval-evolution` | E-01, E-07 |
| PRD-F33 | PPP-020, PPP-030 | current capability matrix and negative suite plus adopted environment-level target boundary | E-01, E-12, E-22 |
| PRD-F34 | PPP-020, PPP-035 | generated signup/login/current-user/protected-action/logout integration, Playwright, and public-film product-account exercise | E-01, E-12, E-27 |
| PRD-F35 | PPP-020, PPP-035 | credential/token redaction, reserved-table denial, HttpOnly cookie inspection, and public-media secret-safety review | E-01, E-12, E-27 |
| PRD-F36 | PPP-020, PPP-024, PPP-025, PPP-032, PPP-033, PPP-035 | PBT-09/PBT-10 plus captured signup, actionable invalid input, logout, login, authenticated reload, and account preservation | E-01, E-12, E-16, E-17, E-24, E-25, E-27 |
| PRD-F37 | PPP-020 | fake-provider regression plus live owner-prompt evolution scenario | E-07, E-12 |
| PRD-F38 | PPP-020 | maintained resource/effect capability matrix and unsupported-effect ledger | E-12 |
| PRD-F39 | PPP-021 | PBT-11, blob CRUD/restore/quota integration, compiled browser upload/reload | E-01, E-13 |
| PRD-F40 | PPP-021, PPP-024, PPP-025, PPP-032, PPP-033, PPP-034 | PBT-12, action/job/ingress post-commit events, and captured preserved product state across server/client evolution, including the fresh public film | E-01, E-13, E-16, E-17, E-24, E-25, E-26 |
| PRD-F41 | PPP-021 | PBT-13, clocked scheduler/lease/retry/idempotency/restore integration | E-01, E-13 |
| PRD-F42 | PPP-021 | PBT-14, public ingress/HMAC/rate/body/session HTTP integration | E-01, E-13 |
| PRD-F43 | PPP-021 | PBT-15, Unicode FTS/vector determinism/isolation and browser search outcome | E-01, E-13 |
| PRD-F44 | PPP-021 | resource-plane checkpoint/restore property and real Codex composite evolution | E-07, E-13 |
| PRD-F45 | PPP-022 | logout/cookie disposal integration plus login-throttle PBT-17 | E-01, E-14 |
| PRD-F46 | PPP-022 | provider rolling-window/restart/repair accounting PBT-16 | E-01, E-14 |
| PRD-F47 | PPP-022 | exhausted-turn preservation integration and Playwright product-use continuity | E-01, E-14 |
| PRD-F48 | PPP-023, PPP-035 | PBT-18, provider Skill/stdin separation, exact active-frame action failure, parent-noise exclusion, and bounded generated-page failure-state regression | E-01, E-15, E-27 |
| PRD-F49 | PPP-026, PPP-028 | streamed JSONL minimization/early-observation property, compiled-browser semantic progress, and judge-facing explanation | E-01, E-18, E-20 |

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
| PBT-09 product-auth session isolation | PPP-020 | `ppp.runtime.auth-test` plus Playwright fresh contexts |
| PBT-10 product-auth revocation | PPP-020 | `ppp.runtime.auth-test`, restore integration, and Playwright |
| PBT-11 blob round-trip/restore | PPP-021 | `ppp.runtime.resources-test` plus coordinator restore integration |
| PBT-12 product-event commit/isolation | PPP-021 | `ppp.property.resource-plane-test` plus multi-tab Playwright |
| PBT-13 durable-job lifecycle | PPP-021 | `ppp.property.resource-plane-test` plus scheduler integration |
| PBT-14 ingress ownership/policy | PPP-021 | `ppp.property.resource-plane-test` plus public HTTP integration |
| PBT-15 search bounds/determinism | PPP-021 | `ppp.runtime.resources-test` plus browser search outcome |
| PBT-16 provider rolling starts | PPP-022 | `ppp.provider.budget-test` plus coordinator/restart integration |
| PBT-17 shared-password throttle | PPP-022 | `ppp.access-test` generated login sequences plus HTTP integration |
| PBT-18 client diagnostic boundary | PPP-023 | `ppp.shared.protocol-test`, `ppp.provider.codex-test`, coordinator/HTTP integration, and Playwright |
| PBT-19 persistent project nREPL | PPP-031 | `ppp.repl.service-test/workspace-nrepl-retains-state-across-one-thousand-generated-sequences` |

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
                              |
                              +---------------------------> PPP-017
                              |
                              +---------------------------> PPP-018
                              |
                              +---------------------------> PPP-019
                              |
                              +---------------------------> PPP-020
                              |
                              +---------------------------> PPP-021
                              |
                              +---------------------------> PPP-022
                              |
                              +---------------------------> PPP-023
                              |
                              +---------------------------> PPP-024
                              |
                              +---------------------------> PPP-025
                              |
                              +---------------------------> PPP-026
                              |
                              +---------------------------> PPP-027
                              |
                              +---------------------------> PPP-028
                              |
                              +---------------------------> PPP-029
                              |
                              +---------------------------> PPP-030
                              |
                              +---------------------------> PPP-031 -> PPP-032 -> PPP-033 -> PPP-034
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
| PPP-017 | done | Owner timeout reproduced by suppressing sandbox animation frames; immutable DOM-commit sentinel plus microtask state flush remove the animation dependency. Clean `bb verify` passes 132 JVM/750 assertions, 21 CLJS/89 assertions, Chromium 14/14, Docker smoke, and a 156-file secret scan. Stable 8787 passes three additional fresh contexts beyond ten seconds, including a 6.5-second delayed bundle, then restarts Codex-ready with zero sessions/runtimes. |
| PPP-018 | done | Real Chromium IME reproduced stale parent echoes corrupting `간단한`; frame-local drafts plus monotonic bridge revisions prevent rollback. Isolated `bb verify` passes 132 JVM/750 assertions, 21 CLJS/89 assertions, Chromium 17/17, Docker smoke, and a 158-file secret scan. Stable 8787 passes three fresh Korean composition paths including a 6.5-second delayed frame, then restarts Codex-ready with zero sessions/runtimes. |
| PPP-019 | done | Real phases now drive a bounded one-line label/detail with decorative zero-to-three-dot motion, stable accessibility text, reduced-motion fallback, and no reasoning leakage. Clean `bb verify` passes 132 JVM/750 assertions, 23 CLJS/99 assertions, Chromium 19/19, Docker smoke, and a 161-file secret scan; stable 8787 passes 2/2 before the final zero-session restart. |
| PPP-020 | done | Session-owned resource/effect model and generated-product identity are complete. Isolated `bb verify` passes 141 JVM/814 assertions, 24 CLJS/104 assertions, Chromium 22/22, Docker smoke, and a 167-file secret scan. Product accounts pass two 1,000-case properties, HTTP/cookie/restore integration, and three fresh browser contexts including a 6.5-second delayed frame. The real Codex evolution passes 7/7 through dark/floating/Tetris/SQLite ranking/server-rule/Gomoku/account changes; paired bundles were promoted and stable 8787 restarted with zero sessions/runtimes. |
| PPP-021 | done | The complete session resource plane now covers durable blobs, post-commit product events, durable jobs, public ingress, and Unicode text/vector search. Isolated `bb verify` passes 163 JVM/985 assertions, 25 CLJS/110 assertions, Chromium 23/23, Docker smoke, and a clean 176-file secret scan. PBT-11 through PBT-15 run at least 1,000 cases/sequences each; the real eight-step Codex evolution passes 8/8 in one resumed thread with all repair versions retained and covered. Paired bundles were promoted together, stable 8787 passed Chromium 23/23 directly, and the final Codex OAuth restart reports zero sessions/runtimes. |
| PPP-022 | done | Shared-password login/logout, common Projects, and persistent rolling provider-start capacity are complete. Isolated `bb verify` passes 174 JVM/1,262 assertions, 25 CLJS/110 assertions, 25 normal Chromium tests, two production restart phases across three fresh contexts, Docker smoke, and a clean secret scan. PBT-16 and PBT-17 each run 1,000 generated sequences; capacity exhaustion preserves source/SQLite/checkpoints while actions and restore continue. Paired bundles were promoted together and stable 8787 passed real-browser Login, Projects, blank-runtime, and conversation smoke. |
| PPP-023 | done | Bounded active-frame action/runtime/Promise/console/network reasons now flow through a volatile 12-record next-turn ring into an optional isolated provider Skill; parent, extension, foreign-frame, and persisted context are excluded. PBT-18 runs at least 1,000 generated inputs. Isolated `bb verify` passes 178 JVM/1,297 assertions, 27 CLJS/125 assertions, 25 normal Chromium tests with one intentional skip, production restart phases, Docker smoke, and a clean 189-file candidate scan. Paired bundles were promoted together, stable 8787 passed the diagnostic smoke without a real Codex call, and the final restart reports zero sessions/runtimes. |
| PPP-024 | done | The exact final-video path used real OAuth Codex, one project, and one resumed thread to pass Snake, product auth, visible validation repair, authenticated SQLite ranking, platformization, and preserved Tetris addition 6/6. That ticket originally retained an exhausted repairable thread; PPP-031 supersedes the terminal policy after live evidence showed failed provider context could poison the next imperative turn. Isolated `bb verify` passed 181 JVM tests/1,317 assertions, 27 CLJS tests/125 assertions, 25 Chromium paths with one intentional skip, production restart phases, Docker smoke, and a clean 195-file final candidate scan. |
| PPP-025 | done | A real OAuth Codex browser run passes all six final-story scenarios in one project and one resumed thread. The locally verified 168.74-second 1440x900 H.264/AAC submission cut begins on Projects, uses honest generation-compression cards, includes English narration and synchronized English subtitles with explicit Codex/GPT-5.6 use, and contains no fake-provider output, credentials, terminal, source, identifiers, or private paths. Publication remains separately owner-controlled. |
| PPP-026 | done | Codex stdout JSONL is parsed incrementally and only allowlisted lifecycle metadata selects volatile product-language progress for the requesting tab. The real OAuth browser advanced through multiple event-derived details before its final reply with no console/network failure. `bb verify` passes 186 JVM tests/1,336 assertions, 29 CLJS tests/132 assertions, 25 Chromium paths with one intentional skip, two production restart phases, Docker smoke, and a clean 201-file secret scan. |
| PPP-027 | done | The public Coolify judge instance serves valid HTTPS from the recorded public commit without changing Slopbook. Shared access, separate data/Codex volumes, persistent ChatGPT OAuth through rolling restart, HTTP 200 readiness, a real Codex browser change, checkpoint replay, and clean post-login browser diagnostics pass. The English submission cut is 168.74 seconds with English narration/subtitles and explicit Codex/GPT-5.6 use. Final `bb verify` passes 186 JVM/1,336 assertions, 29 CLJS/132 assertions, 25 Chromium paths plus one intentional skip, production restart phases, Docker smoke, and a 205-file secret scan. |
| PPP-028 | done | Live Devpost criteria were compared with the verified product; the public project copy now has problem-first description, technology, repository, and hosted-demo fields; the majority-build Codex task is titled and documented with a ninety-second reading guide; README and Devpost copy distinguish full-stack activation from preview generation. `bb lint`, formatting, reference existence, staged diff, and the 206-file secret scan pass. Video upload, private access delivery, and final submission remain owner-controlled. |
| PPP-029 | done | README, thesis, and Devpost inspiration connect Emacs, Lisp, nREPL, Deep Space 1, and PPP while distinguishing live un-wedging from a permanent flight-source patch, raw nREPL from PPP's bounded change contract, and developer hot reload from atomic full-stack source-and-data activation. Direct sources were checked; Devpost version 5 contains the aligned wording while video and `submitted_at` remain empty; lint, formatting, diff, link, and staged secret checks pass. |
| PPP-030 | done | Vision, current staged SCI REPL implementation, public-POC rationale, current limitations, and the intended Codex-operated Workspace Capsule architecture are separated across PRD, specification, security, thesis, README, TODO, and Devpost documents. Workspace-local shell/dependencies are profile-specific rather than permanent gaps; Control Plane and cross-workspace authority remain permanently denied. Devpost version 6 contains the same five-part positioning with video and `submitted_at` still empty. Lint, formatting, reference, conflict-search, diff, and staged secret checks pass. |
| PPP-031 | done | Standard loopback nREPL, persistent project JVM namespaces and Var-backed action redefinition, live action/migration and exact-tab browser evaluation, turn-wide repair observations, runtime-to-source reconciliation, terminal reconstruction, audited provider-thread reset, Kernel-issued blob IDs, and 1,000-sequence PBT are complete. The real OAuth eight-step Workspace REPL evolution passes 8/8 with browser, source, server-stage, migration, version, surface, and thread-lineage gates. Browser restore rewinds checkpoint 13 to 8 and returns to 13 as new versions with expected source/data behavior and a fresh provider branch. Final `bb verify` passes 196 JVM/1,420 assertions, 31 CLJS/145 assertions, 25 Chromium paths plus one intentional skip, two production restart checks, Docker smoke, and a 218-file secret scan. |
| PPP-032 | done | A reproducible HTML-deck, English neural narration, subtitle merge, and FFmpeg pipeline produces a 168.751-second 1440x900 H.264/AAC submission film with burned and embedded English subtitles. Generated-product footage comes only from the verified real-OAuth PPP-025 capture; long waits and the 8x Tetris slow playback are explicitly disclosed. The narrative distinguishes live nREPL Var redefinition from hot reload and the persistent browser runtime, shows Snake, accounts, visible validation, SQLite ranking, platformization, and Tetris, and states Codex's contribution through verification, judge deployment operations, and the film itself. Decoder, timeline, stream, volume, visual, and secret-safety gates pass; upload remains owner-controlled. |
| PPP-033 | done | The final film now keeps every real request visually continuous through its real provider wait and into the outcome. Six wait tails from the same PPP-025 captures play at 4x with a small disclosure; the standalone compression slide and scene 09 are removed. The rebuilt 168.518-second 1440x900 H.264/AAC film passes full decode, 24-block subtitle monotonicity, audio-level, frame-tile continuity, source-removal, and secret-safety checks. Final `bb verify` passes 196 JVM/1,420 assertions, 31 CLJS/145 assertions, 25 Chromium paths plus one intentional skip, two production restart checks, Docker smoke, and a 235-file secret scan. |
| PPP-034 | done | A fresh project on the public judge server passes five real OAuth Codex changes with zero semantic repairs: timer/keyboard Snake, visible `+100` server response, visible `×3` rule replacement, Game library, and timer/keyboard Tetris with Snake's server feature preserved. The rebuilt 173.417-second 1440x900 H.264/AAC film uses only this new public recording and its same-session showcase, includes English narration plus burned and embedded English subtitles, and keeps credentials, session identifiers, raw traces, and private paths out of tracked files and the final media. |
| PPP-035 | done | Deployed revision `44541ec` passes the fresh public story: dark theme, timer/keyboard Snake, generated product auth, actionable invalid-input state, actual signup/logout/login/reload, and a preserved account plus Snake when the product becomes a Game library with Tetris. The 153.144-second 1440x900 H.264/AAC English film fully decodes, has burned and embedded subtitles, and plays publicly without sign-in. Devpost submission `1083611` is `Submitted`; private judge access contains the shared password, while the public film and repository do not. Final `bb verify` passes 198 JVM tests/1,430 assertions, 32 CLJS tests/149 assertions, 25 Chromium paths plus one intentional skip, two production restart checks, Docker smoke, formatting, and a clean 242-file secret scan. |
