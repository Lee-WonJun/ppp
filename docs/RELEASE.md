# Local Release Closure

Status: locally complete through PPP-024; publication actions remain owner-controlled
Last updated: 2026-07-17

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
- Clean candidate-copy release gate: 181 JVM tests/1,317 assertions, 27 CLJS
  tests/125 assertions, 25 normal Chromium tests plus one intentional skip,
  two production restart phases across three fresh browser contexts, Docker
  smoke, a 198-file isolated-gate scan, and a clean 195-file final candidate
  scan after test artifacts were removed.
- Exact final-video real Codex story:
  `artifacts/demo-live/20260717-060709/report.edn`, six of six scenarios passed
  in one project and one resumed provider thread through Snake, product auth,
  visible error repair, authenticated SQLite ranking, Game library, and
  preserved Tetris addition.
- Shared judge workspace evidence:
  `artifacts/evidence/ppp-022-shared-judge-workspace.md`.
- On-demand active-product diagnostic evidence:
  `artifacts/evidence/ppp-023-on-demand-client-diagnostics.md`.
- Enter-based packaged demo: `artifacts/demo/20260716-004803/report.edn`, three
  of three fresh-volume runs passed.
- Aggregated bounded record: `artifacts/release/20260716-local/report.edn`.
- Repository baseline: PPP-023 was first published on 2026-07-17; PPP-024
  closure is ready for the same `origin/master` after the documented gate.
- Stable owner surface: `http://localhost:8787/` opens shared-password sign-in
  and Projects. Codex OAuth readiness, zero development sessions, zero
  in-memory runtimes, and no pending stages are required after the final reset.

## Owner approval queue

These actions remain deliberately unperformed until the owner approves them:

1. Deploy the verified image to the chosen VPS/Coolify target.
2. Record and upload the narrated sub-three-minute video.
3. Add the public video and hosted-demo URLs to `docs/DEVPOST.md`.
4. Deliver the shared password through the event-approved private channel.
5. Select Work & Productivity and submit the Devpost entry.

The public Devpost body must never contain the shared password.
