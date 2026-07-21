# PPP-033 Continuous-Wait Edit Evidence

Date: 2026-07-21
Status: passed locally; public upload not performed

## Edit contract

- The standalone `Generation time compressed` slide and its build function are
  removed.
- Six real PPP-025 prompt captures remain at normal speed through the visible
  request and initial progress period.
- The uncut remainder of each same source capture then plays at 4x with a small
  `4x wait` badge before continuing directly into the corresponding real
  outcome footage.
- The first accelerated tail is 1.534 seconds; the other five are 1.767 seconds
  each. No synthetic product output or fake-provider fixture is inserted.
- The separate Tetris proof still labels its real 0.5-second timer/keyboard
  frame sequence as 8x slow playback.

## Continuity review

Frame tiles were inspected for every accelerated segment. A separate
three-frame-per-second tile across the first request transition shows the same
conversation moving continuously through `Generating`, `Validating`,
`Applying`, the first rendered Snake frame, and automatic Snake movement. The
small speed badge remains outside the canvas and conversation content.

## Media verification

| Property | Result |
|---|---|
| Duration | 168.518 seconds |
| Dimensions | 1440x900 |
| Frame rate | 30/1 |
| Video | H.264 |
| Audio | AAC, English narration |
| Subtitles | burned English captions plus embedded English `mov_text` and SRT |
| Subtitle timeline | 24 monotonic blocks, last end 167.132 seconds |
| Audio level | mean -21.8 dB, peak -3.4 dB |
| Decode | complete FFmpeg null-output pass |
| Size | 6,905,319 bytes |

The build source contains no scene 09, compression-slide renderer, or
`Generation time compressed` text. The current MP4 and SRT remain ignored local
artifacts. No push, deployment, upload, or Devpost submission is part of this
ticket.

The complete `bb verify` release gate passed after the edit: lint and
formatting; 196 JVM tests with 1,420 assertions; 31 CLJS tests with 145
assertions; 25 normal Chromium paths with one intentional skip; two production
restart browser checks; Linux amd64 non-root/read-only Docker smoke with
backup/restore; and a 235-file secret scan.
