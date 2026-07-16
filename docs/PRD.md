# Product Requirements Document

Product: Programmable Programming Page
Tagline: Where product conversations become running software.
Status: approved implementation baseline
Category: Work & Productivity
Last updated: 2026-07-15

## 1. Summary

Programmable Programming Page is a SaaS-style live programming workspace for product managers and designers. A user changes a running full-stack product through one natural-language conversation. The system generates real Clojure-family source and SQLite migrations, validates them in isolated server and browser runtimes, applies them without a page refresh, and records source, data, and history for later engineering work.

## 2. Problem

AI coding products assume that the user can cross a development-environment threshold. The intended user often fails before AI becomes useful:

- installing Git, a language runtime, and package tooling;
- cloning and locating a repository;
- configuring OAuth or an API key;
- understanding files, branches, diffs, builds, and local servers;
- recovering from a broken generated change.

Product managers and designers already collaborate successfully in browser-based tools such as Figma and Notion. They need the same operational simplicity for executable product work.

Current alternatives leave a handoff gap:

- Documents describe behavior but do not execute it.
- Mockups demonstrate appearance but not persistent data or server rules.
- AI website builders often produce an artifact that is difficult to continue as a normal codebase.
- IDE agents are powerful but expose the entire development environment.

## 3. Product insight

The running product can be the product specification.

A conversation should be able to change the interface, server action, persistent schema, and business rule in one validated transaction. The system should preserve those changes as source and checkpoints so a developer inherits executable intent rather than a static approximation.

## 4. Target users

### Primary: product manager

- Can define user outcomes and business rules.
- Uses browser SaaS tools daily.
- Does not want to operate a repository or local build.
- Needs to test a real flow before handing it to engineering.

### Primary: product designer

- Can describe and evaluate interaction and visual behavior.
- Wants to adjust the live interface without a code handoff loop.
- Needs data-backed states, not only idealized screens.

### Secondary: developer receiving the handoff

- Needs real CLJ, CLJS, CLJC, CSS, SQL, tests, and change history.
- Needs deterministic checkpoints and failure evidence.
- Must be able to harden and extend the generated product conventionally.

## 5. Jobs to be done

| ID | Job |
|---|---|
| JTBD-01 | When I have a product idea, I want to describe the intended outcome and use a working version immediately. |
| JTBD-02 | When the behavior is wrong, I want to change the rule in plain language and verify it with real data. |
| JTBD-03 | When a visual surface feels wrong, I want to change it in the same conversation without a build or refresh. |
| JTBD-04 | When an experiment fails, I want the prior working product to remain available. |
| JTBD-05 | When I explore alternatives, I want named checkpoints that restore source and data together. |
| JTBD-06 | When engineering continues the work, I want the executable source and rationale to survive the handoff. |

## 6. Product principles

1. The user edits the running product, not files.
2. One conversation handles discussion, clarification, change, and restore.
3. Successful changes apply automatically. There is no technical Apply step.
4. A failed change must not damage the current product.
5. Source, runtime behavior, and SQLite data form one versioned state.
6. The immutable kernel stays small and recoverable.
7. Tests protect domain rules and public contracts, not incidental copy or DOM nesting.
8. Generated source remains real source, not an internal low-code DSL.

## 7. Core experience

### 7.1 Access and first frame

- The shared hackathon instance is protected by a judge access code.
- The access code arrives in a URL fragment and is exchanged for an HttpOnly signed cookie.
- The fragment is removed immediately after exchange.
- After access, the screen is literally white except for a small fixed sidebar handle.
- No welcome dashboard, template picker, tutorial modal, or repository setup is shown.

### 7.2 Sidebar

- The default sidebar is a right-side overlay, 420px wide on desktop.
- Top: current session selector and a `+` control.
- Center: conversation messages and nontechnical checkpoint cards.
- Bottom: one composer for questions and changes.
- The sidebar itself is generated runtime UI and may be completely redesigned.
- The immutable host always retains the handle, Safe Mode, and last successful sidebar.
- `Ctrl+Alt+Shift+P` enters Safe Mode.
- Session rename and delete are absent in the MVP.

### 7.3 Turn types

| Kind | Observable behavior |
|---|---|
| `reply` | The assistant answers. Product state and version do not change. |
| `clarify` | The assistant asks exactly one focused question. Product state and version do not change. |
| `change` | Progress shows generation, validation, staging, and application. A successful change becomes live without refresh. |
| `restore` | A selected checkpoint is restored as a new history event. Source and SQLite data move together. |

The UI never exposes code, filenames, diffs, Git, models, skills, MCP, provider settings, or raw evaluator output.

### 7.4 Progress and failure

Visible progress vocabulary is limited to:

```text
Generating
Validating
Applying
Applied
```

Errors use plain language, identify whether loading, validation, initial drawing, bridge transport, action execution, or an active interaction failed, retain the current product, and offer a retry where safe. A generated change that fails a repairable validation or staging gate is returned to the provider with structured, bounded, non-secret feedback for up to two corrected attempts before the turn is rejected. A generic rejection without a concrete reason category is not an acceptable product outcome. Internal stages and error codes remain available in logs and test evidence.

### 7.5 Sessions and checkpoints

- `+` creates a persistent blank session.
- Switching sessions restores its latest product and data.
- A successful change creates a checkpoint.
- Reply and clarification turns do not create a runtime version or checkpoint.
- Restore creates a new append-only event and does not delete future checkpoints.
- A later restore can return to a checkpoint that was previously left behind.

## 8. Functional requirements

### Access and host

| ID | Requirement |
|---|---|
| PRD-F01 | Exchange a fragment-delivered access code for a signed HttpOnly, SameSite=Strict cookie. |
| PRD-F02 | Protect state-changing HTTP requests with CSRF. |
| PRD-F03 | Render a literal blank canvas and immutable recovery handle after access. |
| PRD-F04 | Enter Safe Mode through the handle or `Ctrl+Alt+Shift+P`. |
| PRD-F05 | Keep the last successful sidebar available if generated sidebar staging or rendering fails. |

### Session and conversation

| ID | Requirement |
|---|---|
| PRD-F06 | Create and persist independent sessions under workspace `local`. |
| PRD-F07 | Switch sessions without losing source, data, transcript, or current version. |
| PRD-F08 | Accept one prompt of at most 4,000 characters and return `202` with a job identifier. |
| PRD-F09 | Serialize provider work globally and per session with FIFO capacity eight. |
| PRD-F10 | Broadcast progress and final state over protocol-versioned WebSocket messages. |

### Live runtime

| ID | Requirement |
|---|---|
| PRD-F11 | Let generated client source replace routes, components, sidebar, and CSS without refresh. |
| PRD-F12 | Let generated server source register bounded actions and business rules. |
| PRD-F13 | Let generated migrations create and evolve per-session SQLite data within policy. |
| PRD-F14 | Preserve serializable client state across compatible sandbox runtime replacements. |
| PRD-F15 | Keep JVM interop, shell, filesystem, MCP, skills, host credentials, and dependency installation unavailable while allowing ordinary browser APIs inside the isolated generated-client sandbox. |

### Atomic application

| ID | Requirement |
|---|---|
| PRD-F16 | Validate source paths, size, entrypoints, capabilities, and migrations before staging. |
| PRD-F17 | Stage migrations and server code against a copy of the current SQLite database. |
| PRD-F18 | Stage client code in a hidden opaque-origin sandbox frame in the requesting browser. |
| PRD-F19 | Commit only after the requesting tab acknowledges the exact staged base and target versions. |
| PRD-F20 | Reject stale clients and resynchronize them to the current manifest. |
| PRD-F21 | Never modify the active source or database after a rejected or timed-out stage. |

### History, recovery, and restore

| ID | Requirement |
|---|---|
| PRD-F22 | Preserve prompt, assistant explanation, before/after source, validation, and event metadata in append-only history. |
| PRD-F23 | Materialize the current successful manifest instead of replaying all history on load. |
| PRD-F24 | Checkpoint source, manifest, and a consistent SQLite snapshot together. |
| PRD-F25 | Recover an interrupted commit by deterministically finalizing or rolling back a journal. |
| PRD-F26 | Restore any valid checkpoint as a new version and reset the Codex conversation thread. |

### Provider and external capabilities

| ID | Requirement |
|---|---|
| PRD-F27 | Support Codex CLI OAuth as the trusted self-host/hackathon provider and a deterministic fake provider for tests. |
| PRD-F28 | Validate provider JSON against a static schema and Malli before use. |
| PRD-F29 | Allow HTTPS public requests only after scheme, DNS, address, redirect, timeout, and size validation. |
| PRD-F30 | Expose authenticated external APIs only through developer-owned named connector aliases. |
| PRD-F31 | Keep the authenticated parent window, cookie, CSRF state, recovery handle, and parent DOM inaccessible to generated client code. |
| PRD-F32 | Derive the affected runtime surfaces from the returned source: client-only work must remain in the isolated browser runtime, while server, shared-domain, test, or migration work must pass server and SQLite staging without discarding unrelated live features. |

## 9. Hackathon demo acceptance

The packaged product must support this exact uninterrupted story:

1. Open an authenticated blank white canvas and reveal the sidebar.
2. Ask for a floating sidebar and see it replace itself without refresh.
3. Ask for a Gallery / Submit / Leaderboard product with six seed projects, public and judge voting, SQLite storage, and server actions.
4. Cast a vote, reload the browser, and observe the vote still present.
5. Ask for judge votes worth three points, public votes worth one, and a top-three podium.
6. Create a second session, then switch back and see the first product and data restored.
7. Restore an earlier checkpoint, then restore the newer checkpoint.

The demo fails if any change requires a terminal, file picker, manual Apply control, build, server restart, or browser refresh.

## 10. Success criteria

### Product outcome

- A nontechnical viewer can explain the product after the three-minute demo.
- The demo communicates a live full-stack change, not only a visual transformation.
- A developer can locate complete generated source and history on disk.

### Reliability

- `bb verify` passes from a clean checkout.
- The packaged demo completes three consecutive times.
- All eight live Codex evaluation scenarios pass three times each before recording.
- Every rejected test fixture preserves source and logical SQLite hashes.
- Safe Mode recovers from a deliberately broken sidebar.

### Submission

- Internal rubric score is at least 80/100.
- Public secret scan is clean.
- Video is under three minutes with English subtitles.
- README setup and sample flow work on Linux amd64.

## 11. Non-goals for the hackathon

- Public signup, accounts, organizations, workspace tenancy, or billing.
- Real-time collaboration, presence, cursors, CRDTs, or concurrent editing.
- User-facing source, diff, file, terminal, Git, skill, or MCP interfaces.
- Automated Git commit, branch, pull request, or source promotion.
- Runtime JVM or npm dependency installation.
- Generated shell or general filesystem access.
- Multi-node application HA or distributed commit consensus.
- Public SDK or package extraction.
- Linux ARM64 or native desktop installers.
- User-managed secrets and connectors.
- Deployment to Coolify without explicit owner approval.

## 12. Constraints and assumptions

- Workspace ID is always `local`.
- Each session owns one SQLite database.
- The app runs as one public JVM process and serves the compiled browser shell.
- The requesting browser tab alone decides client-stage commit readiness.
- Other tabs follow through broadcast or manifest resynchronization.
- Filesystem history is canonical. Codex thread state is a conversational cache.
- Checkpoints are never pruned automatically. New AI changes stop when quota is exhausted.
- Codex provider defaults to `gpt-5.6-terra` with medium reasoning and 120-second timeout.
- OAuth credentials are acceptable only for the access-code-gated hackathon and trusted self-host deployment.

## 13. Risks

| Risk | Product response |
|---|---|
| Generated source compiles but violates intent | Domain tests, hidden client render, server contract checks, observable E2E scenarios. |
| Live code execution becomes remote code execution | SCI allowlist, no interop, path policy, SQL policy, no shell/filesystem/tool exposure. |
| Client and server change different versions | Base-version check and request-tab two-phase staging. |
| SQLite and source diverge during crash | Prepared journal, before backup, runtime metadata version comparison, idempotent recovery. |
| OAuth material leaks | Separate volume, mode 0600, no logs, no image/repository inclusion, access-code gate. |
| Demo consumes unpredictable model quota | Fake provider for deterministic local and CI coverage; live eval only by explicit command. |
| Generated tests become brittle | Test domain invariants, public contracts, security boundaries, and outcomes only. |

## 14. Source-of-truth order

When documents disagree, use this order:

1. `docs/PRD.md` for product behavior and scope.
2. `docs/SPEC.md` for technical contracts.
3. `docs/SECURITY.md` for trust-boundary restrictions.
4. `DESIGN.md` for visible interaction and visual rules.
5. `docs/RUBRIC.md` for release evidence.
6. `tickets/PPP-*.md` for implementation slices.

No ticket may silently weaken a requirement from a higher source.
