# Three-Minute Demo

Status: rebuilt and verified from the current public judge server; public upload
pending
Actual runtime: 173.417 seconds
Voice: English
Subtitles: burned English captions plus embedded English track

## 1. What the film proves

> A product conversation can create a running browser product, cross the server
> boundary, change live business behavior, and keep evolving without a source
> edit or page reload.

The film does not show a terminal, source tree, diff, SQL, tests, credentials,
or internal session identifiers. The viewer sees the server boundary through a
button that calls a generated server action, first returns `score + 100`, then
returns `score × 3` after another conversation.

## 2. Recording source

The final generated-product footage was recorded from:

```text
https://ppp.openai.slopbook.org
```

The run created a fresh `Arcade evolution` project on the shared-password judge
workspace and used its configured real Codex OAuth provider. The password was
passed only through an ephemeral process environment and never written to the
capture, repository, logs, or film. Raw Playwright video, trace, observations,
and session data remain gitignored.

Reproduce the live capture and the clear outcome showcase with:

```bash
PPP_DEMO_PUBLIC_PASSWORD=... bb demo-public-capture
PPP_DEMO_PUBLIC_PASSWORD=... \
PPP_PUBLIC_CAPTURE_ROOT=artifacts/public-demo-capture/<timestamp> \
bb demo-public-showcase
```

Build the film from that ignored capture:

```bash
PPP_PUBLIC_CAPTURE_ROOT=artifacts/public-demo-capture/<timestamp> \
bash docs/submission/video/build.sh
```

`bb demo-rehearsal` remains a fake-provider host regression. It is never used
as generated-product footage in the submission film.

## 3. Exact public-server story

### 1. Browser-owned Snake

```text
Build the first game in this product: a polished Snake game. It must start moving automatically from a browser timer and continue or restart automatically after a collision. Give the playable root the accessible name "Snake game", make that root keyboard-focusable, and handle real DOM ArrowUp, ArrowDown, ArrowLeft, and ArrowRight keydown events without a reload. Each accepted key must immediately update the direction state and visible direction output. Show the score and expose live values named "Snake head row", "Snake head column", "Snake direction", and "Snake score" so the running game can be verified. Keep all game logic in the browser sandbox. Do not write server, shared-domain, test, or migration files yet.
```

Verified outcome: timer movement, keyboard movement, visible score, no reload.

### 2. Cross the server boundary

```text
Add one real server-powered feature to the existing Snake game without replacing it. Register a server action that accepts the current Snake score and returns that score plus 100. Add a button named "Boost score", an output named "Boosted score", and an output named "Server response". Clicking the button must call the server action and show the returned value. Keep Snake's timer and keyboard controls. Do not add SQL or migrations. Add only the minimal rollback-only domain tests for the server rule and response shape.
```

Verified outcome: the browser shows `101` for a live score of `1`; reload keeps
the server-powered feature.

### 3. Change the running server rule

```text
Change the existing server score rule without redesigning the page or replacing Snake. The same registered action must now multiply the submitted score by 3 instead of adding 100. Keep the same "Boost score" button, "Boosted score" output, and "Server response" output. Add an output named "Score rule" containing "Triple server rule". Update only the existing rollback-only domain tests for the new rule. Do not add SQL or migrations.
```

Verified outcome: the same button returns a multiple of three, the triple-rule
label is visible, and Snake remains playable.

### 4. Make it a product family

```text
Turn this product into a small arcade while preserving the playable Snake game and its working server-powered score feature. Add a home view headed "Game library" with a Snake catalog card and a button named "Play Snake". Add a button named "Back to games" on the Snake view. This is a client-only information-architecture change: reuse the existing server action and do not write server, shared-domain, test, or migration files.
```

Verified outcome: Game library appears; Snake opens with the triple server rule.

### 5. Add Tetris without losing Snake

```text
Add Tetris as the second game in the existing Game library without removing or replacing Snake. Its catalog button must be named "Play Tetris". Tetris must advance automatically from a browser timer, accept ArrowLeft, ArrowRight, and ArrowDown without reload, use a playable root named "Tetris game", and expose live values named "Piece row" and "Piece column". Include a "Back to games" button. Preserve playable Snake, the working triple-score server action, and every existing server action. This is client-only: do not write server, shared-domain, test, or migration files.
```

Verified outcome: both games are listed; Tetris falls and accepts arrow input;
Snake and the changed server action remain available.

All five changes passed on the public server without semantic repair.

## 4. Film sequence

| Scene | Picture | Claim |
|---:|---|---|
| 01 | Product barrier title | Product people should not need installs, Git, folders, builds, and auth before the first prompt. |
| 02 | REPL versus hot reload | PPP changes behavior in long-lived runtimes; the public POC is bounded, while the self-hosted Workspace Capsule targets nREPL. |
| 03 | Real Snake request, accelerated wait, running game | A browser timer and keyboard input work without refresh. |
| 04 | Real server-feature request, accelerated wait, visible `101` result | The running browser calls a real generated server action. |
| 05 | Real server-rule request, accelerated wait, visible triple result | Conversation changes the existing server behavior without replacing Snake. |
| 06 | Real library and Tetris requests, continuous waits, outcome showcase | The product becomes a two-game library while preserving the server feature. |
| 07 | Codex contribution | Codex helped shape, evaluate, repair, verify, deploy, and produce the film. |
| 08 | Closing tagline | Where product conversations become running software. |

Provider waits remain visually continuous and are labeled `6x`, `7x`, or `8x`
when accelerated. The final outcome showcase is a second recording of the same
completed public session, not a fixture or mock.

## 5. Release checks

- [x] Fresh project created on the public judge server.
- [x] Real Codex OAuth provider used for all five changes.
- [x] Every generated-product frame comes from the new public recording.
- [x] Timer and keyboard Snake.
- [x] Visible `+100` server response.
- [x] Visible `×3` server-rule replacement.
- [x] Game library with Snake and Tetris.
- [x] Timer and keyboard Tetris.
- [x] No fake-provider or previous-capture footage.
- [x] No credential, session identifier, terminal, source, or private path.
- [x] 1440x900 H.264, AAC audio, English captions, embedded English subtitles.
- [x] Full decode passes at 173.417 seconds, below three minutes.
- [ ] Upload and Devpost submission require owner approval.
