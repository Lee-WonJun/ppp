# PPP Build Week Film

This directory contains the reproducible source for the final local submission
film. The product footage is not recreated here. It comes only from the
verified real-OAuth Codex capture recorded by PPP-025.

## Build

Prerequisites:

- Google Chrome
- `edge-tts` 7.2.7
- static FFmpeg and FFprobe binaries
- the ignored PPP-025 edit segments under
  `artifacts/demo-capture/20260720-english-final/edit-segments/`

The defaults reuse the video tools already installed for the Build Week film
workflow. Override them with `FFMPEG`, `FFPROBE`, `EDGE_TTS`, or
`PPP_VIDEO_SOURCE_DIR` when needed.

```bash
bash docs/submission/video/build.sh
```

Outputs remain ignored until the owner separately authorizes publication:

```text
artifacts/submission-video/final/ppp-build-week-demo.mp4
artifacts/submission-video/final/ppp-build-week-demo.en.srt
```

## Truthfulness contract

- Generated-product shots are cut only from the PPP-025 real Codex recording.
- Each request begins at normal speed, then its remaining real provider-wait
  footage plays at 4x on the same screen and continues directly into the real
  outcome. A small `4x wait` badge discloses the accelerated interval.
- Deck scenes explain the product and architecture; they do not simulate a
  generated product result.
- The public URL may be shown because the judge instance is live, but the film
  does not claim that unpublished local commits are already deployed.
- The final video contains English narration and burned-in English captions;
  the same captions are embedded as a subtitle track and retained as an SRT.
