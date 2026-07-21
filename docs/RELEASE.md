# Local Release Closure

Status: public judge deployment, final film, and Devpost submission complete
Last updated: 2026-07-22

## Definition of locally complete

PPP is locally complete when:

- the approved product requirements and current implementation agree;
- `bb verify` passes from a clean temporary repository copy;
- the exact packaged demo passes three consecutive fresh-volume runs;
- live Codex reports remain valid for provider and multi-turn evolution behavior;
- only sanitized bounded evidence is part of the repository candidate;
- the owner-facing 8787 process is ready with no development sessions;
- intentional local Git commits contain the reproducible source and closure
  records.

Local completeness does not authorize remote or public state changes.

## Completed live evidence

- 24-case Codex OAuth evaluation: `artifacts/live-eval/20260715-222618/report.edn`.
- Eight-turn complete-product evolution: `artifacts/evolution-eval/20260716-141959/report.edn`.
- Real Codex stable composer smoke: Enter sends once, Shift+Enter remains a
  newline, and the composer returns to an editable state.
- Clean candidate-copy release gate: 186 JVM tests/1,336 assertions, 29 CLJS
  tests/132 assertions, 25 normal Chromium tests plus one intentional skip,
  two production restart phases, Docker smoke, and a clean 205-file secret
  scan.
- Exact public-server final-video story: PPP-034 created a fresh project on
  `https://ppp.openai.slopbook.org` and passed five of five real OAuth Codex
  changes through timer/keyboard Snake, a visible `+100` server action, a
  visible live replacement with a `×3` server rule, Game library conversion,
  and preserved timer/keyboard Tetris. Every browser outcome passed without a
  semantic repair.
- Final public film: PPP-035 replaces the previous edit with a polished
  153.144-second 1440x900 film whose generated-product frames come only from a
  fresh public-server recording and its same-session outcome showcase. Real
  waits remain continuous with visible acceleration labels. English narration,
  burned and embedded synchronized English subtitles, the bounded-public-POC
  versus self-hosted-nREPL distinction, and explicit Codex/GPT-5.6 use are
  retained. The public video is <https://youtu.be/8VcptiW67JU>.
- Streamed real-Codex progress: PPP-026 incrementally parses bounded JSONL and
  delivers only metadata-derived product-language details to the requesting
  tab. Real OAuth browser observation and the complete gate are recorded in
  `artifacts/evidence/ppp-026-streamed-codex-progress.md`.
- Shared judge workspace evidence:
  `artifacts/evidence/ppp-022-shared-judge-workspace.md`.
- On-demand active-product diagnostic evidence:
  `artifacts/evidence/ppp-023-on-demand-client-diagnostics.md`.
- Public judge deployment: `https://ppp.openai.slopbook.org` passes TLS,
  shared-password access, persistent ChatGPT OAuth through restart, readiness,
  one real Codex change, persisted checkpoint replay, and a clean post-login
  browser canary. The bounded record is
  `artifacts/evidence/ppp-027-coolify-judge-deployment.md`.
- Deployment separation check on 2026-07-21: the public host remains healthy
  and Codex-ready. The new capture and film-source work remains local until the
  owner separately authorizes a push; the film does not claim those local files
  are deployed.
- Judge-readiness review: the internal release score is separated from the
  conservative external judging assessment; the majority-build Codex task has
  a high-signal reading guide in `docs/CODEX_SESSION.md`.
- Enter-based packaged demo: `artifacts/demo/20260716-004803/report.edn`, three
  of three fresh-volume runs passed.
- Aggregated bounded record: `artifacts/release/20260716-local/report.edn`.
- Repository baseline: PPP-024 implementation and closure are recorded in
  intentional commits and published to `origin/master` on 2026-07-17.
- Stable owner surface: `http://localhost:8787/` opens shared-password sign-in
  and Projects. Codex OAuth readiness, zero development sessions, zero
  in-memory runtimes, and no pending stages are required after the final reset.

## Public submission closure

- Public judge origin: <https://ppp.openai.slopbook.org>
- Public film: <https://youtu.be/8VcptiW67JU>
- Devpost project: <https://devpost.com/software/programmable-programming-page>
- Devpost submission: `1083611`, submitted 2026-07-22 03:10:23 KST
- Category: Work & Productivity
- Shared judge password: delivered only through the private test-instructions
  field; it is not present in the repository, public copy, or film.
