# PPP-035 Public Demo and Submission Evidence

Recorded: 2026-07-22 KST

This is a bounded, sanitized release record. It excludes the shared workspace
password, generated session identifier, raw provider output, browser traces,
cookies, prompts containing private values, and local capture paths.

## Deployed runtime

- Judge origin: <https://ppp.openai.slopbook.org>
- Deployed source revision: `44541ec`
- `/healthz`: HTTP 200, `status=ok`
- `/readyz`: HTTP 200, storage ready, OAuth Codex provider ready

## Fresh real-provider product evolution

One new public-server project used the configured OAuth Codex provider and
passed these cumulative browser outcomes:

1. blank product to dark `Night Shift Arcade` direction;
2. timer-driven, keyboard-controlled Snake without refresh;
3. generated product signup/sign-in backed by the server while preserving Snake;
4. visible invalid-identifier feedback, real signup, logout, login, reload, and
   authenticated state preservation;
5. conversion to a Game library with timer/keyboard Tetris while preserving
   Snake and the signed-in account surface.

Every recorded change passed its client-stage and browser-outcome checks. The
final same-session showcase passed login, library navigation, Snake movement,
Tetris movement, and account preservation.

## Film contract

- Public video: <https://youtu.be/8VcptiW67JU>
- Local release artifact: `artifacts/submission-video/final/ppp-build-week-demo.mp4`
- Duration: 153.144 seconds
- Picture: 1440x900 H.264
- Audio: AAC, English narration
- Captions: burned English captions plus embedded English subtitle track
- SHA-256: `2d13d831dc87a11e5db7936f290ce5909a63530e3490bf16d6a6e4c70fc3e57d`
- Full media decode passed.
- Manual frame review found no workspace password, generated session identifier,
  raw provider trace, terminal, source, or private filesystem path.
- A signed-out browser loaded the public YouTube page and played the film.

## Devpost closure

- Project: <https://devpost.com/software/programmable-programming-page>
- Submission ID: `1083611`
- Status: `Submitted`
- Submitted: 2026-07-21 14:10:23 EDT / 2026-07-22 03:10:23 KST
- Category: Work & Productivity
- Final video URL and public repository are attached.
- The shared password exists only in Devpost's private test-instructions field.
- The feedback task ID is recorded in the private submission form and project
  documentation, not repeated in this public evidence record.

## Harness repairs discovered by the real flow

- Product-auth success may become observable through the generated signed-in UI
  before an incidental terminal message appears; the showcase now accepts the
  semantic authenticated outcome.
- A resumed verification run can encounter an already-created account after a
  prior browser outcome succeeded; the capture resumes from authenticated state
  instead of reporting a false product failure.
- Snake movement is two-dimensional; the showcase compares both row and column
  rather than assuming every valid move changes only the row.

These are harness corrections. They do not replace the public product outcome
checks or weaken generated-product failure reporting.

## Final repository gate

`bb verify` completed successfully after the release records and harness fixes:

- clj-kondo: 0 errors, 0 warnings;
- JVM: 198 tests, 1,430 assertions, 0 failures;
- CLJS: 32 tests, 149 assertions, 0 failures;
- Chromium: 25 passed, 1 intentional skip;
- production shared-access restart checks: 2 passed;
- Linux amd64 Docker smoke: passed, including non-root, read-only root,
  persistent Codex home, and backup/restore rollback;
- secret scan: 242 project files, passed;
- formatting: passed.
