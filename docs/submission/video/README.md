# PPP Build Week Film

This directory contains the reproducible source for the final local submission
film. The generated-product footage comes only from the new public-judge
recording and outcome showcase verified by PPP-034.

## Build

Prerequisites:

- Google Chrome
- `edge-tts` 7.2.7
- static FFmpeg and FFprobe binaries
- an ignored public capture created by `bb demo-public-capture` and
  `bb demo-public-showcase`

The defaults reuse the video tools already installed for the Build Week film
workflow. Override them with `FFMPEG`, `FFPROBE`, `EDGE_TTS`, or
`PPP_PUBLIC_CAPTURE_ROOT` when needed.

```bash
bash docs/submission/video/build.sh
```

Outputs remain ignored until the owner separately authorizes publication:

```text
artifacts/submission-video/final/ppp-build-week-demo.mp4
artifacts/submission-video/final/ppp-build-week-demo.en.srt
```

## Truthfulness contract

- Generated-product shots are cut only from the new public-server real Codex
  recording and its same-session outcome showcase.
- Each request begins at normal speed, then its remaining real provider-wait
  footage plays at the disclosed speed on the same screen and continues
  directly into the real outcome.
- Deck scenes explain the product and architecture; they do not simulate a
  generated product result.
- The generated product shown is the actual public judge instance, not a local
  fixture or a previous capture.
- The final video contains English narration and burned-in English captions;
  the same captions are embedded as a subtitle track and retained as an SRT.
