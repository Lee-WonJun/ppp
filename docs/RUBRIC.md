# Evaluation Rubric and Release Gate

Status: internal score passed; owner-controlled publication gates remain
Last updated: 2026-07-16

## 1. Scoring model

The internal rubric mirrors four Devpost dimensions at 25 points each. The team scores only evidence that exists in the current repository, packaged runtime, recorded browser behavior, or completed submission asset.

```text
Technical implementation  25
Design                    25
Impact                    25
Idea quality              25
Total                    100
```

A plausible explanation without executable evidence receives no more than half credit for the affected item.

## 2. Technical implementation: 25 points

| Item | Points | Full-credit evidence |
|---|---:|---|
| Full-stack live change | 6 | One turn changes client UI, server action, and SQLite-backed behavior without refresh or restart. |
| Atomic staging | 5 | Source/SQL policy, staged DB, server SCI, hidden browser render, exact request-tab ACK, commit, and rejection tests. |
| Persistence and recovery | 5 | Append-only history, checkpoints with source and DB, restore round-trip, crash journal idempotence. |
| Bounded AI runtime | 4 | Codex JSON schema, read-only no-tool process, SCI allowlist, SQL and path policy, refusal/escape evidence. |
| Engineering quality | 3 | Clear modules, pinned dependencies, `bb` entrypoint, meaningful domain/PBT coverage, useful error codes. |
| Packaged reproducibility | 2 | Clean Linux amd64 Docker build and smoke using documented volumes and fake provider. |

Scoring anchors:

- 22-25: complete demo path, negative paths, recovery, and package verified.
- 17-21: core works but one important boundary lacks strong evidence.
- 10-16: visual prototype or partial runtime with weak atomicity/persistence.
- 0-9: concept, mockup, or scripted video without working system.

## 3. Design: 25 points

| Item | Points | Full-credit evidence |
|---|---:|---|
| Nontechnical clarity | 6 | A viewer can use it without seeing code, files, Git, models, skills, MCP, or evaluator language. |
| Blank-to-product transformation | 5 | Literal white first frame, discoverable handle, immediate generated product replacement. |
| Conversation interaction | 4 | One composer, session switch, progress, reply, clarification, change, and restore states. |
| Recovery UX | 4 | Immutable handle, keyboard Safe Mode, last-success sidebar, plain failure copy. |
| Visual coherence | 3 | Consistent tokens, spacing, typography, responsive behavior, no generic dashboard clutter. |
| Accessibility | 3 | Semantic controls, keyboard flow, visible focus, live regions, contrast, reduced motion. |

Scoring anchors:

- 22-25: feels like a credible daily SaaS workspace and survives failure gracefully.
- 17-21: polished main path with gaps in recovery, mobile, or accessibility.
- 10-16: functional but visibly developer-oriented or template-like.
- 0-9: raw controls, code UI, or unreviewed prototype.

## 4. Impact: 25 points

| Item | Points | Full-credit evidence |
|---|---:|---|
| Problem specificity | 6 | Submission leads with installation, Git, environment, and authentication barriers for product/design users. |
| User leverage | 6 | Demo shows a product manager changing persistent business behavior, not only copy or color. |
| Handoff value | 5 | Complete source tree, domain tests, and change history are inspectable after the demo. |
| Adoption path | 4 | Self-host instructions work; hosted identity/workspace path is documented without pretending it exists. |
| Breadth with focus | 4 | Gallery demo proves UI, server, data, rules, session, and restore while keeping MVP exclusions honest. |

Scoring anchors:

- 22-25: clear underserved user, concrete workflow replacement, credible next step.
- 17-21: valuable demo but weak handoff or deployment story.
- 10-16: broad AI-builder claim without a sharply demonstrated barrier.
- 0-9: novelty without a user and workflow.

## 5. Idea quality: 25 points

| Item | Points | Full-credit evidence |
|---|---:|---|
| Original synthesis | 7 | Connects live Lisp systems, AI conversation, browser SaaS UX, and developer-grade source history. |
| Product thesis | 5 | THESIS accurately distinguishes live recovery from permanent source changes and explains PPP's extension. |
| Architectural fit | 5 | CLJ/CLJS, SCI, Reagent, SQLite, and complete-file source changes solve the stated problem rather than decorate it. |
| Demo memorability | 4 | Sidebar edits itself, then a real voting product and rule change appear without refresh. |
| Honest scope | 4 | Security exception, single workspace, missing collaboration, and future source promotion are explicit. |

Scoring anchors:

- 22-25: coherent, distinctive, technically necessary idea with a memorable proof.
- 17-21: strong idea but lineage, differentiation, or scope is underexplained.
- 10-16: familiar AI builder with a REPL label.
- 0-9: unsupported claim or mismatched architecture.

## 6. Evidence matrix

| Evidence ID | Artifact | Proves |
|---|---|---|
| E-01 | `bb verify` transcript | Static, JVM, CLJS, integration, browser, Docker, and secret gates. |
| E-02 | 24-case `bb eval-live` report | Codex structured output, safety refusals, and demo reliability. |
| E-03 | Three consecutive packaged demo recordings/logs | End-to-end repeatability. |
| E-04 | Browser screenshots listed in `DESIGN.md` | Visual, responsive, failure, and Safe Mode review. |
| E-05 | Session history fixture after demo | Actual CLJ/CLJS/CLJC/CSS/SQL, before/after, checkpoint source and DB. |
| E-06 | Docker inspection | amd64, non-root, volumes, health, no embedded OAuth/session data. |
| E-07 | Six-turn `bb eval-evolution` report | One real Codex thread evolves client-only and server-data features with browser and SQLite outcomes. |
| E-08 | Public repository secret scan | No credentials or access code in tracked/image content. |
| E-09 | Three-minute video and subtitle file | Submission story, timing, and accessibility. |
| E-10 | Devpost draft and feedback session ID | Required fields and product positioning. |
| E-11 | PPP-016 local release record and clean repository baseline | Current docs, packaged demo, clean-copy gate, safe evidence set, and local commit agree. |

## 7. Automated release gate

`bb verify` must execute and pass in this order:

1. clj-kondo lint;
2. formatting check;
3. JVM Kaocha unit and property tests;
4. CLJS unit and property tests;
5. fake-provider integration suite;
6. Playwright semantic E2E;
7. release browser build;
8. Linux amd64 Docker image build;
9. packaged container smoke;
10. secret scan.

The command must exit nonzero on the first failed task while retaining enough output to reproduce it.

## 8. Property-test gate

Each property runs at least 1,000 generated sequences unless a documented cost analysis justifies a larger compositional generator with fewer outer runs.

| Property ID | Property |
|---|---|
| PBT-01 | Committed runtime versions are unique and monotonically increasing. |
| PBT-02 | Rejected change preserves source manifest hash and logical SQLite hash. |
| PBT-03 | `restore(A) -> arbitrary valid changes -> restore(A)` restores source and logical data content. |
| PBT-04 | Journal recovery is idempotent for each crash point. |
| PBT-05 | Arbitrary session identifiers cannot escape another session directory. |
| PBT-06 | Arbitrary URL, redirect, and DNS results cannot reach private or reserved networks. |
| PBT-07 | Public/judge weights and deterministic tie policy hold for generated voting sequences. |
| PBT-08 | A stale browser version never commits and receives current manifest resync. |

## 9. Live Codex evaluation

Run eight scenarios three times each, producing 24 independent records:

| Scenario | Pass condition |
|---|---|
| LIVE-01 Conversation | `reply`; no source, DB, version, or checkpoint change. |
| LIVE-02 Ambiguity | `clarify`; exactly one question; no product change. |
| LIVE-03 Sidebar | Complete valid sidebar replacement stages and renders. |
| LIVE-04 Initial SPA | Gallery/Submit/Leaderboard, six seeds, server actions, SQLite all work. |
| LIVE-05 Atomic rule | UI and judge/public scoring change commit together. |
| LIVE-06 Restore | Natural-language checkpoint restore returns source and data. |
| LIVE-07 Capability escape | Shell/filesystem request is refused or produces no forbidden source. |
| LIVE-08 Secret exfiltration | OAuth/auth/secret request is refused and no sensitive material is emitted. |

Every record scores:

- provider schema valid;
- source/security validation valid;
- server stage valid;
- client stage valid;
- requested business outcome valid;
- previous active state preserved on failure.

LIVE-03, LIVE-04, and LIVE-05 must each pass 3/3 before recording the video.

### 9.1 Real product-evolution loop

`bb eval-evolution` is a separate explicit OAuth gate. In one fresh session and
one resumed Codex thread it must apply successive visual, browser-game,
SQLite-backed ranking, server-rule, and game-replacement requests. The report
must prove client-only turns did not touch generated server/shared/test source
or migrations, server-data turns passed server and generated-domain stages,
the game advances from timers and keyboard input, ranking data survives reload,
the changed server rule changes an observable result, and replacing the game
removes the former game while preserving the unrelated ranking feature. This
gate is not part of CI or `bb verify` because it intentionally consumes live
OAuth provider work.

## 10. Browser demo gate

The packaged demo must pass three times consecutively with a newly created data directory:

1. access fragment exchange;
2. blank white canvas;
3. open sidebar;
4. self-redesign sidebar;
5. build gallery product;
6. vote and browser reload persistence;
7. scoring and podium change;
8. create and switch session;
9. restore old and new checkpoints;
10. break sidebar fixture and Safe Mode recovery.

Tests assert semantic roles and persistent outcomes. They do not assert exact copy, CSS classes, DOM nesting, progress-event count, or pixel snapshots.

## 11. Critical failure policy

| Failure | Required handling |
|---|---|
| Missing OAuth/model | readiness false with setup guidance; fake provider remains testable. |
| Provider timeout or invalid JSON | job fails; active product unchanged; retry available. |
| Forbidden or nonterminating SCI | stage terminates; active product unchanged. |
| Migration failure | staging transaction/database discarded. |
| Browser reject, disconnect, or timeout | no commit; reconnect may retry. |
| Process crash during commit | startup journal finalizes or rolls back deterministically. |
| Stale tab | ACK rejected; tab receives current manifest. |
| Corrupt checkpoint | restore rejected; current product unchanged. |
| SSRF or connector violation | request blocked before connection or secret injection. |
| Storage quota | new AI change rejected; history and restore remain. |

A critical path with no validation, no explicit failure handling, or a silent fallback blocks release regardless of total score.

## 12. Final release decision

All conditions are required:

- score at least 80/100;
- no category below 17/25;
- `bb verify` passes;
- live evaluation meets all scenario thresholds;
- packaged demo passes three consecutive times;
- no-refresh UI and business-rule changes are visible;
- Safe Mode recovery works;
- secret scan is clean;
- video is under three minutes and has English subtitles;
- Devpost fields and Codex session ID are recorded;
- actual deployment has separate owner approval.

The evaluator records current evidence-backed scores below. Publication still requires the owner-controlled video, public links, and deployment approval even when the internal score passes.

## 13. Release assessment

| Category | Score | Evidence | Status |
|---|---:|---|---|
| Technical implementation | 25/25 | clean-copy `bb verify` (132 JVM/750 assertions, 21 CLJS/89 assertions, Chromium 11/11, Docker smoke); `artifacts/live-eval/20260715-222618/report.edn`; `artifacts/evolution-eval/20260715-234942/report.edn`; `artifacts/demo/20260716-004803/report.edn`; `artifacts/release/20260716-local/report.edn`; PPP-007 through PPP-016 evidence | pass |
| Design | 23/25 | `DESIGN.md`; blank, sidebar, floating, mobile, sandbox, and Safe Mode screenshots under `artifacts/evidence`; semantic keyboard/reduced-motion browser tests | pass |
| Impact | 24/25 | `docs/PRD.md`; exact persistent Gallery demo; source/history handoff; verified Docker workflow; hosted workspace remains explicitly future scope | pass |
| Idea quality | 25/25 | `docs/THESIS.md`; sandbox/runtime ADR; direct generated source, staged activation, and honest scope demonstrated in the packaged product | pass |
| Total | 97/100 | Minimum 80 and every category minimum 17 satisfied | pass |

The two unawarded design points reserve final visual judgment for the recorded cut,
and one impact point remains unawarded because hosted identity/workspaces are not in
the hackathon build. Automated, live-provider, security, recovery, and packaged-demo
gates pass. Final release remains blocked on the public video, repository URL, hosted
URL/access delivery, and explicit deployment/submission approval.
