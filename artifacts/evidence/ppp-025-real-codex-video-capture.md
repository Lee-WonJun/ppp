# PPP-025 Real Codex Video Capture

Date: 2026-07-17
Status: passed locally; publication not authorized

## Capture contract

- Provider: real Codex CLI with ChatGPT OAuth
- Model: `gpt-5.6-terra`
- Browser: compiled product at 1440x900
- Continuity: one project, one resumed provider thread
- Story result: six of six scenarios passed
- Final runtime: 168.74 seconds
- Media: H.264 video, AAC Korean narration, embedded English `mov_text`
  subtitles, and an English SRT sidecar
- Disclosure: every removed model wait is replaced by a visible
  `Generation time compressed / Real Codex output` card

## Observed product outcomes

1. Projects is the first readable frame and a blank project opens.
2. Snake advances from a browser timer, accepts real arrow input, and exposes
   a visible score.
3. Product signup and sign-in are added without replacing Snake.
4. Invalid account input produces a useful visible error; signup, sign-out,
   sign-in, and authenticated reload succeed.
5. A signed-in score is stored through a generated server action and remains
   in the SQLite ranking after reload.
6. The product becomes a Game library without losing Snake, the account, or
   ranking data.
7. Tetris is added as a second timer- and keyboard-driven game while the prior
   product remains intact.

## Verification

- The live report recorded six passed and zero failed scenarios with provider
  thread continuity.
- The media probe reported 168.74 seconds, 1440x900, H.264, AAC, and embedded
  English subtitles.
- Audio analysis reported non-silent narration.
- Manual frame review confirmed Projects at one second, the running products,
  explicit wait-compression cards, and the final title card.
- The capture contains no PPP login, password, access fragment, terminal,
  source tree, diff, SQL, test output, session identifier, or local path.
- No fake-provider frame or response was used.

## Local artifacts

The ignored local capture directory contains `final/ppp-demo.mp4` and
`final/ppp-demo.en.srt`. Raw recordings, observations, generated-product data,
and session identifiers remain ignored and must not be published.

Uploading the final MP4, publishing a URL, deploying the product, and
submitting to Devpost remain separate owner-approved actions.
