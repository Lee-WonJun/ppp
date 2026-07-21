# PPP-032 Final Submission Film Evidence

Superseded on 2026-07-21 by PPP-033. This record describes the prior render;
the current ignored MP4/SRT paths now contain the continuous-wait edit recorded
in `artifacts/evidence/ppp-033-continuous-wait-edit.md`.

Date: 2026-07-21
Status: passed locally; public upload not performed

## Source integrity

- Every generated-product shot comes from the PPP-025 real OAuth Codex capture
  under `artifacts/demo-capture/20260720-english-final/edit-segments/`.
- The deterministic fake-provider Gallery fixture is absent.
- Model waits are shortened only behind a visible `Generation time compressed`
  card.
- The Tetris result uses a real 0.5-second timer and keyboard frame sequence
  from source segment `017`, played at 8x slower speed with a persistent
  disclosure overlay. The following preserved-Snake shot proves that the prior
  game, account, and ranking remain.

## Narrative coverage

The eight-scene film covers:

1. the installation, Git, folder, build, and authentication barrier;
2. nREPL-driven live JVM Var redefinition, the separate persistent browser
   runtime, and reconciliation into source, tests, SQLite, history, and
   checkpoints;
3. timer- and keyboard-driven Snake;
4. product signup/sign-in, visible validation repair, and authenticated reload;
5. an authenticated generated server action and persistent SQLite ranking;
6. Game library platformization, timer-driven Tetris, and preserved Snake;
7. Codex and GPT-5.6 contributions across product decisions, Clojure and
   ClojureScript implementation, runtime repair, browser/SQLite/Docker checks,
   judge deployment operations, and production of this film;
8. the hosted judge URL and product tagline.

## Media verification

Final local artifacts:

- `artifacts/submission-video/final/ppp-build-week-demo.mp4`
- `artifacts/submission-video/final/ppp-build-week-demo.en.srt`

Verified contract:

| Property | Result |
|---|---|
| Duration | 168.751 seconds |
| Dimensions | 1440x900 |
| Frame rate | 30/1 |
| Video | H.264 |
| Audio | AAC, English narration |
| Subtitles | burned English captions plus embedded English `mov_text` and SRT |
| Subtitle timeline | 24 monotonic blocks, last end 167.365 seconds |
| Audio level | mean -21.8 dB, peak -3.4 dB |
| Decode | complete FFmpeg null-output pass |
| Size | 6,300,852 bytes |

The contact sheet and nine full-resolution representative frames were reviewed
manually. Headings, captions, product controls, nREPL diagram, account result,
ranking, Tetris movement, Codex contribution, and final URL are legible without
caption overlap that obscures the product outcome.

The complete `bb verify` release gate also passed after the film and documents
were finalized: lint and formatting; 196 JVM tests with 1,420 assertions; 31
CLJS tests with 145 assertions; 25 normal Chromium paths with one intentional
skip; two production restart browser checks; Linux amd64 non-root/read-only
Docker smoke with backup/restore; and a 233-file secret scan.

## Security and publication boundary

Narration, deck, sidecar subtitles, and rendered output were checked for local
paths, access fragments, credentials, OAuth files, passwords, private provider
output, and session identifiers. None are visible. The MP4 and SRT remain under
the ignored `artifacts/submission-video/` tree.

The public judge host returned HTTP 200 for `/`, `/healthz`, and `/readyz` on
2026-07-21. Readiness reported storage, ChatGPT OAuth Codex, provider capacity,
and outbound policy ready. This does not imply that unpublished local code is
deployed: local `master` was three nREPL commits ahead of `origin/master` during
this verification. No push, redeploy, upload, or Devpost submission occurred
under PPP-032.
