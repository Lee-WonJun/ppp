# Local Release Closure

Status: local gates passed; repository commit pending under PPP-016
Last updated: 2026-07-16

## Definition of locally complete

PPP is locally complete when:

- the approved product requirements and current implementation agree;
- `bb verify` passes from a clean temporary repository copy;
- the exact packaged demo passes three consecutive fresh-volume runs;
- live Codex reports remain valid for provider and multi-turn evolution behavior;
- only sanitized bounded evidence is part of the repository candidate;
- the owner-facing 8787 process is ready with no development sessions;
- an intentional local Git commit contains the reproducible source baseline.

Local completeness does not authorize remote or public state changes.

## Completed live evidence

- 24-case Codex OAuth evaluation: `artifacts/live-eval/20260715-222618/report.edn`.
- Six-turn selective-runtime evolution: `artifacts/evolution-eval/20260715-234942/report.edn`.
- Real Codex stable composer smoke: Enter sends once, Shift+Enter remains a
  newline, and the composer returns to an editable state.
- Clean candidate-copy release gate: 132 JVM tests/750 assertions, 21 CLJS
  tests/89 assertions, 11 Chromium paths, Docker smoke, and secret scan pass.
- Enter-based packaged demo: `artifacts/demo/20260716-004803/report.edn`, three
  of three fresh-volume runs passed.
- Aggregated bounded record: `artifacts/release/20260716-local/report.edn`.

## Owner approval queue

These actions remain deliberately unperformed until the owner approves them:

1. Push the local commit to `origin` and confirm repository visibility.
2. Deploy the verified image to the chosen VPS/Coolify target.
3. Record and upload the narrated sub-three-minute video.
4. Add public repository, video, and hosted-demo URLs to `docs/DEVPOST.md`.
5. Deliver the access code through the event-approved private channel.
6. Select Work & Productivity and submit the Devpost entry.

The public Devpost body must never contain a real access code.
