# PPP-024 Real Codex Game-platform Demo Evidence

Date: 2026-07-17

## Outcome

The exact final-video product story passed through the real OAuth Codex
provider in one fresh project and one resumed provider thread. The compiled
browser evolved one running product from Snake into product accounts, a
visible account-error repair, an authenticated SQLite ranking, a Game library,
and a second playable Tetris game.

This was not the fake-provider Gallery fixture. The deterministic provider
remains a CI and packaged host/recovery regression only. The final-video path
is the explicit `bb demo-live` command and is never called by `bb verify` or
CI.

## Exact browser outcomes

The six real-provider scenarios all passed:

1. Snake advanced from a browser timer, accepted an arrow key, showed its
   score, and left exactly one active product frame.
2. Product signup/sign-in appeared without removing playable Snake. The change
   included generated server source, a profile migration, and rollback-only
   domain tests.
3. The redesigned account panel visibly reported the Kernel error for a
   one-character sign-in identifier. The browser then created Player One,
   signed out, signed back in, reloaded, remained authenticated, and retained
   Snake.
4. The authenticated user saved the current Snake score through a generated
   server action. Player One appeared in the deterministic SQLite ranking and
   remained present with the signed-in account after reload.
5. The product became a Game library. Snake remained listed and playable, and
   the existing account and ranking remained visible.
6. Tetris appeared as the second game, advanced from a browser timer, accepted
   an arrow key, and returned to the preserved Snake account and ranking.

## Runtime and thread evidence

The sanitized evaluator report is
`artifacts/demo-live/20260717-060709/report.edn`.

- provider: real Codex OAuth;
- scenarios: 6 passed, 0 failed;
- session count: 1;
- provider thread continuity: passed;
- scenario order and committed event coverage: passed;
- runtime versions: 0 through 15, with every accepted repair retained;
- browser outcome, source/client stage, runtime impact, changed-surface, and
  migration-policy gates: passed for every scenario;
- final user-table count: 2;
- generated server-data turns: account/profile and authenticated ranking;
- generated client-only turns: Snake, account presentation/error handling,
  Game library, and Tetris.

The accepted scenarios required nine bounded semantic repair changes in total.
Those versions are evidence rather than hidden retries. Raw prompts, generated
source, account credentials, browser traces, observations, and the session
identifier are not publication artifacts.

## Thread-continuity defect found and fixed

The first live rehearsal exposed a Kernel defect before completion. When a
repairable browser-stage change exhausted its in-turn correction attempts, the
rejected source remained inactive as intended, but the session also cleared
the provider thread. The next explicit correction therefore started a new
conversation and could not satisfy the one-thread product-evolution contract.

`ppp.coordinator/process-turn!` now retains the last valid provider thread only
for an exhausted repairable generated change. It still records one rejected
event and never activates rejected source. Successful restore and
non-repairable provider failures retain the existing thread-reset policy.

The focused regression
`ppp.coordinator-test/exhausted-repairable-change-keeps-thread-for-the-next-explicit-repair`
passed 8 assertions. The second real-Codex rehearsal then exercised the fixed
behavior and completed all six scenarios with thread continuity marked
`passed`.

## Complete release gate

A clean temporary repository copy passed `bb verify` after the live story:

- clj-kondo: zero errors and zero warnings;
- JVM: 181 tests and 1,317 assertions;
- ClojureScript: 27 tests and 125 assertions;
- compiled Chromium: 25 passed with one intentional skip;
- production shared-access restart: both phases passed;
- Docker: linux/amd64, non-root, read-only root, persistent Codex home, and
  backup/restore rollback smoke passed;
- secret scan: 198 project files inspected;
- final post-test-artifact candidate scan: 195 project files inspected;
- formatting: clean.

## Video contract

`docs/DEMO.md` and `artifacts/demo/ppp-demo.en.srt` now begin on Projects with
workspace access already established. They contain no PPP login shot and no
generated-source, file-tree, diff, SQL, test, or history handoff shot. The
edited story shows server behavior through account lifecycle and ranking
persistence, then ends on the two-game platform.

Recording, editing, uploading, deployment, and Devpost submission remain
owner-controlled external actions.
