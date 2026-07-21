# Three-Minute Demo

Status: polished real-Codex English narrative film built and verified locally;
public upload pending
Actual runtime: 168.751 seconds
Voice: English
Required subtitles: English

## 1. Demo purpose

The demo proves one idea:

> A product conversation can begin with a small browser game and keep evolving
> into a real service with accounts, server-owned data, and multiple products.

The visible story begins on Projects. Do not show PPP sign-in, access codes,
OAuth, a terminal, generated source, files, diffs, SQL, tests, or history. The
viewer should understand the server because signup, sign-in, authenticated
ranking, and reload persistence visibly work, not because we show its code.

## 2. Prepared environment

- Packaged Linux amd64 application using the real OAuth Codex provider.
- PPP workspace access completed before recording begins.
- Browser at 1440x900 with 100% zoom, already showing Projects.
- A clean project named `Arcade evolution` created on camera.
- No product account or product data exists before the take.
- Notifications, bookmarks bar, password managers, and unrelated tabs hidden.
- Screen recording at 30fps or higher.
- Honest time-compression cards for model waits; do not imply instant
  generation.

The final capture path is:

```bash
bb demo-live
```

`bb demo-live` runs the exact six-turn story through `PPP_AI_PROVIDER=codex`,
one fresh session, one resumed Codex thread, the compiled browser, generated
product auth, and real SQLite. It is explicit and never runs from `bb verify`
or CI.

`bb demo-rehearsal` remains a deterministic packaged regression using the fake
provider. It is useful for host timing and recovery checks, but it is not the
provider or product shown in the final video.

## 3. Exact prompts

### Prompt 1: make the first game

```text
Build the first game in this product: a polished Snake game. It must start moving automatically from a browser timer and continue or restart automatically after a collision. Give the playable root the accessible name "Snake game", make that root keyboard-focusable, and handle real DOM ArrowUp, ArrowDown, ArrowLeft, and ArrowRight keydown events without a reload. Each accepted key must immediately update the direction state and visible direction output. Show the score and expose live values named "Snake head row", "Snake head column", "Snake direction", and "Snake score" so the running game can be verified. Keep all game logic in the browser sandbox. Do not write server, shared-domain, test, or migration files yet.
```

Expected visible outcome:

- Snake advances automatically.
- Arrow keys change direction.
- Score and game state are visible.
- No refresh occurs.

### Prompt 2: add product accounts

```text
Add real product signup and sign-in to this Snake product, while keeping Snake playable. Use the provided product-auth capabilities, not PPP workspace access, and never store passwords yourself. Start with a simple account area with fields named "Display name", "Sign-in ID", and "Password", a button named "Create account", and a switch named "Have an account? Sign in". The sign-in submit button must be named "Sign in" and the reverse switch must be named "Need an account? Create one". Store the public display-name profile in a host-approved migration keyed by the auth user id. After signup or sign-in show "Signed in as <display name>" and a button named "Sign out". Add rollback-only domain tests for signup, profile lookup, sign-in state, and rejection paths.
```

Expected visible outcome:

- Signup and sign-in appear without removing Snake.
- The account belongs to the generated product, not the PPP workspace.
- Passwords remain in the Kernel-owned product-auth boundary.

### Prompt 3: improve the observed account UX

```text
The account area feels bolted onto the game. Redesign it as a cohesive arcade account panel and make invalid input, duplicate-account, wrong-password, and server failures visibly explain what the player can fix. Keep the existing Snake game and every product-auth/server action unchanged. Put the latest visible account result in an output named "Account message". This is a client-only presentation and error-handling change: do not write server, shared-domain, test, or migration files.
```

Expected visible outcome:

- Submit an invalid one-character sign-in ID.
- The product visibly explains that a valid identifier is required.
- Correct the form, create `Player One`, sign out, sign back in, and reload.
- The product still shows `Signed in as Player One` after reload.

### Prompt 4: add the server ranking

```text
Add a persistent Snake ranking for signed-in players. Keep the game and account experience. Add a button named "Save score" that sends the current Snake score to a registered server action. The action must require the authenticated product user, derive identity from the auth context instead of trusting a typed player name, store the player's best score in SQLite, and return a deterministic ranking ordered by score descending with stable ties. Render it in a region named "Snake ranking" and show the signed-in player's display name and score. Put action feedback in an output named "Ranking status". Add a host-approved migration and rollback-only domain tests for auth enforcement, best-score updates, ordering, response shape, and reload persistence.
```

Expected visible outcome:

- Save the current score while signed in.
- `Player One` appears in the ranking.
- Reload and see the same account and score.
- The visible behavior proves a server action and SQLite persistence.

### Prompt 5: turn the game into a platform

```text
This should be a game platform, not only a Snake page. Turn the product into an arcade with a home view headed "Game library" and a catalog card for Snake with a button named "Play Snake". Snake must remain playable as one game and keep the signed-in account, existing SQLite ranking, stored score, and full account controls. Add a button named "Back to games" on the Snake view. This is a client-only information-architecture change: reuse every existing server action and do not write server, shared-domain, test, or migration files.
```

Expected visible outcome:

- A Game library replaces the single-game landing page.
- Snake is one catalog entry and still opens.
- Account and ranking data remain unchanged.

### Prompt 6: add the second game

```text
Add Tetris as the second game in the existing Game library without removing or replacing Snake. Its catalog button must be named "Play Tetris". Tetris must advance automatically from a browser timer, accept ArrowLeft, ArrowRight, and ArrowDown without reload, use a playable root named "Tetris game", and expose live values named "Piece row" and "Piece column". Include a "Back to games" button. Preserve the signed-in account, Snake ranking, stored score, playable Snake, and every existing server action. This is client-only: do not write server, shared-domain, test, or migration files.
```

Expected visible outcome:

- Snake and Tetris are both listed.
- Tetris falls automatically and accepts arrow keys.
- Returning to Snake shows the same signed-in account and ranking.

## 4. Shot and narration plan

| Time | Picture | English subtitle / narration |
|---:|---|---|
| 0:00-0:09 | Projects is already open. Create `Arcade evolution` and enter its blank canvas. | `The hard part for many product people is not prompting. It is getting past installs, Git, folders, builds, and authentication.` |
| 0:09-0:24 | Send Prompt 1, briefly show real progress, then an honest time-compression card. | `PPP starts where familiar browser tools start: open a project and describe the outcome.` |
| 0:24-0:39 | Play Snake. Show automatic movement, arrow input, and score. | `The first result is a real browser game. Its timer and keyboard behavior are already running, with no refresh.` |
| 0:39-0:55 | Send Prompt 2. Show the first account form beside Snake. | `Now the same conversation crosses the server boundary and adds product accounts without replacing the game.` |
| 0:55-1:10 | Point at the awkward form, send Prompt 3, show the redesigned account panel. | `Product work is iterative. The first form is not good enough, so I ask for a better interface and useful errors.` |
| 1:10-1:34 | Submit invalid ID, read the visible error, correct it, create Player One, sign out, sign in, reload. | `This error comes from the real account boundary. I fix the input, create a player, sign out, sign back in, and the session survives reload.` |
| 1:34-1:51 | Send Prompt 4 and save the current score. | `Next, Snake becomes a service. The signed-in player saves a score through a server action into SQLite.` |
| 1:51-2:06 | Show Player One in ranking, reload, show it still present. | `The ranking is server-owned data, not a painted mockup. The account and score remain after reload.` |
| 2:06-2:23 | Send Prompt 5. Land on Game library, then open Snake. | `A product decision changes the shape of the whole service. Snake is now one game in a platform, without losing its users or data.` |
| 2:23-2:40 | Send Prompt 6. Game library gains Tetris. | `One more conversation adds a second game while preserving everything that already works.` |
| 2:40-2:54 | Open Tetris, show automatic falling and arrow input, return to Snake and its ranking. | `Tetris runs in the browser. Accounts and rankings stay on the server. Both evolve inside one live product workspace.` |
| 2:54-2:59 | Final frame: library, sidebar, and tagline. | `Where product conversations become running software.` |

The polished English narrative cut is 168.751 seconds. It opens with the
installation and Git barrier, distinguishes the nREPL-driven product loop from
source hot reload, then uses only the verified real-Codex footage for the
generated product story. Long model waits use explicit compression cards, and
the brief real Tetris timer/keyboard sequence is labeled as 8x slow playback.
The export remains below 180 seconds and explicitly explains Codex and GPT-5.6
across product decisions, implementation, runtime repair, verification,
deployment operations, and production of the film itself.

## 5. Camera and editing rules

- The first visible frame is Projects. Never show PPP login or access setup.
- Keep at least one real progress transition per turn, then use an explicit
  `Generation time compressed` card when shortening the wait.
- Do not splice in fake-provider output or claim a deterministic fixture was
  generated live.
- Never show a terminal, source tree, generated code, diff, SQL, test output,
  history directory, model name, token count, Skill, or MCP.
- Keep pointer movement deliberate and show the visible consequence before
  narrating the implementation.
- Do not expose shared passwords, product passwords, OAuth files, cookies,
  access fragments, provider diagnostics, or local personal paths.
- Do not claim public multi-tenancy, multiplayer, payments, source promotion,
  or deployed scale.

## 6. English subtitle file

The synchronized English subtitle sidecar is in the ignored local submission
directory beside `ppp-build-week-demo.mp4`; the MP4 also embeds the same
English subtitle track. The reproducible deck, narration, subtitle merge, and
FFmpeg pipeline live in `docs/submission/video/`.

Subtitle review checks:

- every spoken sentence is represented;
- narration and subtitles are both English;
- the spoken and written story explicitly explains how Codex and GPT-5.6 are
  used;
- `PPP workspace account` and `generated product account` are never conflated;
- browser-only and server-owned changes match what is visible;
- no subtitle claims fake output is live generation;
- reading speed is reasonable and lines do not cover controls.

## 7. Rehearsal gates

### Deterministic package regression

```bash
bb demo-rehearsal
```

This existing three-run fake-provider gate proves packaged host mechanics,
session persistence, restore, and Safe Mode without spending provider capacity.
It is not the final-video story and its Gallery fixture must not appear in the
recording.

### Exact real-Codex story

```bash
bb demo-live
```

The real gate passes only when all six turns use one resumed Codex thread and
the compiled browser proves:

- timer and keyboard Snake;
- product-auth form without losing Snake;
- visible invalid-signup error;
- signup, sign-out, sign-in, and authenticated reload;
- authenticated SQLite ranking and reload persistence;
- Game library with preserved Snake/account/ranking;
- timer and keyboard Tetris with Snake still available;
- one active sandbox frame and no unresolved client-stage failure after every
  turn.

Any manual source edit, fake-provider substitution, terminal repair, lost
account, lost ranking, reload-required activation, or hidden product error
invalidates the take.

## 8. Capture checklist

- [x] First frame is Projects, not login.
- [x] Blank new project.
- [x] Real Codex progress appears honestly.
- [x] Playable timer/keyboard Snake.
- [x] Initial account form with Snake preserved.
- [x] Improved arcade account UI.
- [x] Visible invalid sign-in-ID error.
- [x] Create, sign out, sign in, reload as Player One.
- [x] Save score and prove ranking persistence after reload.
- [x] Game library lists Snake.
- [x] Game library lists Snake and Tetris.
- [x] Playable timer/keyboard Tetris.
- [x] Return to preserved Snake account and ranking.
- [x] No login, terminal, source, diff, SQL, test, or history shot.
- [x] Final frame includes product and tagline.
