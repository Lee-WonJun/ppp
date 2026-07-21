# PPP-034 Public Live Recording Evidence

Date: 2026-07-21
Ticket: PPP-034
Public origin: `https://ppp.openai.slopbook.org`

## Capture boundary

- `/healthz` and `/readyz` returned HTTP 200 before capture.
- Readiness reported the real `codex` provider with `chatgpt-oauth` ready.
- The browser used production shared-password login and created a fresh
  `Arcade evolution` project on the public server.
- The password was copied from the masked deployment control, passed through
  one ephemeral process environment, and never printed or written.
- Raw Playwright video, trace, observations, session identifiers, and transient
  screenshots are under the ignored `artifacts/public-demo-capture/` tree.

## Public-server outcomes

| Scenario | Version | Duration | Browser evidence | Repairs |
|---|---:|---:|---|---:|
| Timer/keyboard Snake | 0 to 1 | 94.127 s | timer advanced, arrow input changed direction, score visible | 0 |
| Server score boost | 1 to 2 | 95.380 s | live action returned score plus 100 and survived reload | 0 |
| Server rule replacement | 2 to 3 | 45.645 s | same action returned a triple result and Snake remained | 0 |
| Game library | 3 to 4 | 48.232 s | Snake listed and playable with server feature preserved | 0 |
| Timer/keyboard Tetris | 4 to 5 | 108.078 s | falling piece and arrow input worked; Snake server feature remained | 0 |

The clear outcome showcase re-opened that completed public session without a
provider call and visibly recorded Game library, automatic Tetris movement,
keyboard movement, Snake, and the triple-score server response.

## Film contract

- Output: `artifacts/submission-video/final/ppp-build-week-demo.mp4`
- Subtitle sidecar: `artifacts/submission-video/final/ppp-build-week-demo.en.srt`
- Duration: 173.417 seconds
- Video: H.264, 1440x900, 30 fps
- Audio: AAC, English narration
- Subtitles: burned English captions plus embedded English `mov_text`
- Generated-product source: only the new public capture and same-session
  showcase; no PPP-025 or fake-provider footage
- Wait treatment: visually continuous with explicit acceleration badges

## Safety

- `artifacts/public-demo-capture/` is ignored and has zero tracked files.
- The public shared password, OAuth material, cookies, prompt/source payloads,
  session identifier, raw trace, and private capture paths do not appear in the
  tracked evidence or film.
- Publication, upload, and Devpost submission remain owner-controlled.

## Release verification

`bb verify` completed successfully after the capture and documentation update:

- JVM: 196 tests, 1,420 assertions, zero failures
- ClojureScript: 31 tests, 145 assertions, zero failures
- Browser: 25 passed, one intentional skip
- Production shared-access restart checks: two passes
- Docker: linux/amd64, non-root, read-only-root, persistent-volume smoke passed
- Secret scan: 240 project files inspected, clean

The final MP4 also passed a complete FFmpeg decode, 24-cue monotonic subtitle
validation, stream probing, and forbidden metadata/text scanning.
