# Three-Minute Demo

Status: final public-server capture and upload in progress
Target runtime: 2:25 to 2:55
Voice: English
Subtitles: burned English captions plus an embedded English track

## 1. What the film must prove

> A product conversation can create a running browser product, cross the server
> boundary with real product identity and SQLite data, and keep evolving without
> exposing a source editor, Git workflow, or rebuild step to the product user.

The film uses one new project on the current public judge server. It must not
reuse fake-provider footage, an older generated session, or a failed capture.
The public Shared POC and the intended self-hosted Workspace Capsule are
described separately so the film never claims that a shared judge JVM exposes
an unrestricted nREPL endpoint.

## 2. Recording source

All generated-product footage comes from:

```text
https://ppp.openai.slopbook.org
```

The recording begins on the public Projects screen after workspace sign-in. It
creates a new project and uses the deployed OAuth-backed Codex provider with
GPT-5.6 Terra at medium reasoning. The password is passed only through an
ephemeral process environment and is never written to the repository, capture,
application log, or film.

```bash
PPP_DEMO_PUBLIC_PASSWORD=... bb demo-public-capture
PPP_DEMO_PUBLIC_PASSWORD=... \
PPP_PUBLIC_CAPTURE_ROOT=artifacts/public-demo-capture/<timestamp> \
bb demo-public-showcase

PPP_PUBLIC_CAPTURE_ROOT=artifacts/public-demo-capture/<timestamp> \
bash docs/submission/video/build.sh
```

`bb demo-rehearsal` is a deterministic host regression only. It is never used
as submission footage.

## 3. Exact public-server story

### 1. Establish the visual language

The first request turns the blank product into a dark arcade welcome screen.
The result must visibly use a near-black canvas, high-contrast type, restrained
neon accents, and the heading `Night Shift Arcade` without a page refresh.

### 2. Create browser-native Snake

The second request adds a playable Snake game. The browser outcome gate proves
that a real timer advances the snake and real arrow-key input changes the
running state. This step remains client-only.

### 3. Cross the server boundary with product identity

The third request adds real product signup and sign-in while preserving Snake.
It uses the workspace's product-auth capability and a host-approved SQLite
migration for the public profile. Passwords remain request-local and hashed by
the host; the account, public profile, and product data belong to the generated
workspace rather than the PPP Control Plane.

### 4. Observe and repair the real account experience

The fourth request improves the account panel and error behavior. The recording
then performs the actual user path:

1. submit an invalid identifier and show the actionable validation message;
2. create `Player One`;
3. sign out;
4. sign in with the new account;
5. reload and show that the signed-in state and Snake remain.

This is the server proof shown in the film. It demonstrates a real authenticated
action and durable workspace data, not a local flag or scripted success label.

### 5. Evolve the product into a platform

The final request turns the arcade into a game library and adds timer-driven,
keyboard-controlled Tetris. The final showcase revisits the account flow, the
library, Snake, and Tetris in the same completed public session.

## 4. Film sequence

| Scene | Picture | Claim |
|---:|---|---|
| 01 | Editorial problem slide | Product people are blocked by setup before the first useful prompt. |
| 02 | REPL architecture slide | PPP evaluates a running product; the shared POC is bounded and the per-workspace destination is nREPL-driven. |
| 03 | Real dark-theme request and Snake request | Conversation changes visual direction and creates browser-native behavior. |
| 04 | Real product-auth request and result | The same workspace crosses into server identity and SQLite-backed product data. |
| 05 | Real error, signup, logout, login, reload | The account path is observable, repairable, and persistent. |
| 06 | Real library/Tetris request and same-session showcase | A product decision reshapes the service without losing prior behavior. |
| 07 | Codex contribution slide | Codex and GPT-5.6 powered planning, implementation, presentation, film production, and the deployed product agent. |
| 08 | Closing slide | Where product conversations become running software. |

Normal-speed typing and Enter are visible for every featured request. Only the
continuous provider wait is accelerated, with a truthful `24x wait` or `30x
wait` badge. The cut continues from that wait into the real result; no
generation-compression card or fake transition replaces it.

## 5. Release checks

- [ ] New deployment revision is live and `/readyz` reports OAuth Codex ready.
- [ ] Fresh project created on the public judge server.
- [ ] All five real-Codex changes pass their browser outcome gates.
- [ ] Dark theme, timer/keyboard Snake, and no-refresh activation are visible.
- [ ] Invalid signup error is visible and actionable.
- [ ] Actual signup, logout, login, and authenticated reload pass.
- [ ] Game library and timer/keyboard Tetris pass without losing Snake/account.
- [ ] No fake-provider or previous-capture footage appears.
- [ ] No credential, session identifier, terminal, source, or private path appears.
- [ ] 1440x900 H.264, AAC audio, English burned captions, embedded subtitles.
- [ ] Full decode passes and duration is below 180 seconds.
- [ ] Public YouTube URL plays without sign-in.
- [ ] Devpost project is updated and reports `Submitted` with a non-null time.
