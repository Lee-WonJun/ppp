# Product Requirements Document

Product: Programmable Programming Page
Tagline: Where product conversations become running software.
Status: approved implementation baseline
Category: Work & Productivity
Last updated: 2026-07-20

## 1. Summary

Programmable Programming Page is a SaaS-style live programming workspace for
product managers and designers. A user changes a running full-stack product
through one natural-language conversation. The system evaluates real
Clojure-family programs in isolated server and browser runtimes, applies
validated behavior without a page refresh, and records the intent, observed
outcome, source, data, tests, and checkpoint for later product and engineering
work. Each PPP session behaves like a private programmable product sandbox:
normal browser, server, identity, data, and integration behavior is available
through session-owned resources, while the fixed Kernel prevents access to PPP
credentials, host resources, and other sessions.

This PRD is the acceptance contract for the public hackathon proof, not the
maximum authority of the intended product. The build now has two explicit
profiles. `shared-poc` is the public default: an SCI-evaluated transactional hot
swap in fresh candidate contexts. `workspace-repl` is the trusted development
profile: Codex receives a project tool backed by a standard loopback nREPL
connection to the already-running JVM, defines and redefines actual Clojure
Vars used by the live action router, observes the changed runtime, repairs it
in the same session, and only then reconciles
source and a checkpoint. Because that nREPL still shares the PPP JVM, the
profile is refused outside development until each workspace has a disposable
container or stronger sandbox. In that target deployment, workspace-local
source, shell, filesystem, dependencies, server nREPL, and browser CLJS REPL
are ordinary creative tools; the permanent boundary protects the Control
Plane, credentials, host, and other workspaces.

The product is based on a stronger belief than “AI can generate code.” Product
managers and designers will increasingly create real working behavior, not
only requirements and mockups. Today they are often blocked before the first
useful prompt by installation, Git, folders, OAuth, local servers, and broken
environment recovery. PPP removes that operational threshold and makes the
running product the shared planning surface.

The five-part positioning is therefore:

1. **Vision:** product conversation directly programs a running full-stack
   product and its semantic runtime history remains a developer-continuable
   artifact.
2. **Current implementation:** public `shared-poc` SCI transactional hot swap,
   plus a trusted `workspace-repl` path that attaches Codex to the running
   server and requesting browser before durable reconciliation.
3. **Implementation reason:** the public POC shares one JVM and consumes the
   owner's OAuth capacity, so arbitrary host authority is unsafe.
4. **Current limit:** typed capabilities cannot equal a normal development
   environment without repeatedly recreating platform functions.
5. **Wannabe architecture:** Codex freely uses nREPL and the browser REPL inside
   a workspace capsule; checkpoint acceptance reconciles runtime, source,
   tests, data, and history.

## 2. Problem

AI coding products assume that the user can cross a development-environment threshold. The intended user often fails before AI becomes useful:

- installing Git, a language runtime, and package tooling;
- cloning and locating a repository;
- configuring OAuth or an API key;
- understanding files, branches, diffs, builds, and local servers;
- recovering from a broken generated change.

That is not a lack of product judgment. It is a tooling-access problem. Asking
every planner and designer to become a repository and local-environment
operator before they can create executable behavior wastes the new capability
AI provides.

Product managers and designers already collaborate successfully in browser-based tools such as Figma and Notion. They need the same operational simplicity for executable product work.

Current alternatives leave a handoff gap:

- Documents describe behavior but do not execute it.
- Mockups demonstrate appearance but not persistent data or server rules.
- AI website builders often produce an artifact that is difficult to continue as a normal codebase.
- IDE agents are powerful but expose the entire development environment.

## 3. Product insight

The running product can be the product specification, and a semantic runtime
transition can be the unit of product collaboration.

A conversation should be able to change the interface, server action,
persistent schema, and business rule in one validated transaction. The primary
record is not a line-level diff. It is the causal loop:

```text
intent
-> runtime evaluation
-> observed product outcome
-> validation evidence
-> source-and-data checkpoint
-> next conversation
```

Source remains essential for reproducibility and engineering, but editing it
is not the user's interaction model. Git remains an appropriate downstream
tool for hardened production work, but a Git commit does not by itself capture
why a product change was requested, what was observed in the running system,
or which outcome was accepted.

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

- Needs real CLJ, CLJS, CLJC, CSS, SQL, tests, and causal runtime history.
- Needs deterministic checkpoints and failure evidence.
- Must be able to distinguish accepted product intent from incidental generated
  implementation, then harden, rewrite, and extend it conventionally.

## 5. Jobs to be done

| ID | Job |
|---|---|
| JTBD-01 | When I have a product idea, I want to describe the intended outcome and use a working version immediately. |
| JTBD-02 | When the behavior is wrong, I want to change the rule in plain language and verify it with real data. |
| JTBD-03 | When a visual surface feels wrong, I want to change it in the same conversation without a build or refresh. |
| JTBD-04 | When an experiment fails, I want the prior working product to remain available. |
| JTBD-05 | When I explore alternatives, I want named checkpoints that restore source and data together. |
| JTBD-06 | When engineering continues the work, I want accepted intent, observed outcomes, evidence, source, and data to survive the handoff. |

## 6. Product principles

1. The user edits the running product, not files.
2. One conversation handles discussion, clarification, change, and restore.
3. Successful changes apply automatically. There is no technical Apply step.
4. A failed change must not damage the current product.
5. Source, runtime behavior, and SQLite data form one versioned state.
6. The immutable kernel stays small and recoverable.
7. Tests protect domain rules and public contracts, not incidental copy or DOM nesting.
8. Generated source remains real source, not an internal low-code DSL.
9. Product behavior is allowed by default when its effects remain inside the
   session sandbox. Restrictions follow authority boundaries, not categories
   such as games, accounts, dashboards, or collaboration.
10. Sensitive primitives are mediated, not omitted. The Kernel owns secrets,
    credentials, tokens, host networking policy, and resource limits while
    generated code owns the product rules built from those capabilities.
11. Runtime history is the primary collaboration record. Source snapshots and
    eventual Git promotion support engineering; textual diffs do not define
    the user's product-planning loop.
12. PPP is language-enabled, not language-exclusive. Clojure and SCI make live
    evaluation, redefinition, and inspectable forms natural, but an equivalent
    persistent evaluator and history contract could be implemented in another
    language.

## 7. Core experience

### 7.1 Access and first frame

- The shared hackathon instance is protected by one owner-configured shared
  password. It is delivered to judges only through the private submission
  instructions.
- An unauthenticated browser sees a normal password form. A successful
  origin-checked exchange creates an HttpOnly signed cookie; logout expires it.
- Production does not accept password-bearing URL fragments. Fragment exchange
  remains an explicitly enabled local-development and deterministic-test aid.
- After access, Projects lists every session in the shared `local` workspace
  and offers one New project action. There are no judge identities, private
  copies, owners, memberships, templates, or per-user views.
- Opening an existing project loads its latest source and data. Creating a
  project opens the literal white version-zero canvas with only the immutable
  sidebar handle visible.
- No tutorial modal, repository setup, code surface, or technical dashboard is
  shown.

### 7.2 Sidebar

- The default sidebar is a right-side overlay, 420px wide on desktop.
- Top: an All projects action, current session selector, and a `+` control.
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

The primary visible progress vocabulary is limited to:

```text
Generating
Validating
Applying
Applied
```

Each phase may include one bounded plain-language detail clause. During
generation, the Kernel may advance that clause from actual provider JSONL
lifecycle events such as turn start, reasoning-item completion, plan update,
and response preparation. The visible clause comes from a fixed allowlist
selected only by event type and state; provider text is never copied. It is not
a reasoning transcript and never contains provider reasoning, diagnostics,
generated source, paths, commands, models, tokens, or invented event claims. A
visual ellipsis may animate while a phase is active without creating additional
progress events. Progress remains one current line rather than a growing event
log and is never persisted.

Errors use plain language, identify whether loading, validation, initial drawing, bridge transport, action execution, or an active interaction failed, retain the current product, and offer a retry where safe. A generated change that fails a repairable validation or staging gate is returned to the same provider thread with structured, bounded, non-secret feedback for up to five corrected attempts before the turn is rejected. If those attempts are exhausted, the rejected source never becomes current and the terminal provider branch is detached. The next explicit user turn starts a fresh provider thread reconstructed from durable source, transcript summary, capabilities, and semantic history, so failed code and exhausted instructions cannot contaminate it. Restore and non-repairable provider failures use the same reset boundary. A generic rejection without a concrete reason category is not an acceptable product outcome. Internal stages and error codes remain available in logs and test evidence.

### 7.5 Sessions and checkpoints

- Projects lists every shared session by title and last update.
- New project and `+` create a named persistent blank session.
- Switching sessions restores its latest product and data.
- A successful change creates a checkpoint.
- Reply and clarification turns do not create a runtime version or checkpoint.
- Restore creates a new append-only event and does not delete future checkpoints.
- A later restore can return to a checkpoint that was previously left behind.

### 7.6 Product sandbox

The generated product is not a visual template. Within its session it can
combine:

- arbitrary in-frame browser UI and interaction;
- relational schemas, transactions, persistent records, and business rules;
- product-owned accounts, login sessions, profiles, roles, and permissions;
- restricted public HTTP and developer-configured authenticated connectors;
- browser workers, WebAssembly, Canvas, media, timers, and files selected by
  the user when the browser platform permits them;
- durable binary objects and generated assets;
- product events shared across the session's open browser tabs;
- durable delayed and background work with retry and idempotency;
- bounded public API and webhook entrypoints;
- full-text and caller-supplied-vector search over session-owned documents.

The Kernel may deny an effect only when it could cross a trust boundary or
consume an unbounded host resource. It must not deny an ordinary product
feature merely because that feature was not part of the demo. When an effect
needs privileged machinery, PPP provides a typed capability that operates on
session-owned virtual resources instead of exposing raw host authority.

PPP workspace access and generated-product identity are separate layers. The
hackathon shared password decides who may open PPP. A generated game, community,
admin tool, or marketplace may still create and authenticate its own users
inside that one product session.

## 8. Functional requirements

### Access and host

| ID | Requirement |
|---|---|
| PRD-F01 | Exchange a server-configured shared password submitted through an origin-checked login form for a signed HttpOnly, SameSite=Strict cookie; allow fragment exchange only when explicitly enabled for development or tests. |
| PRD-F02 | Protect state-changing HTTP requests with CSRF. |
| PRD-F03 | Show every shared `local` project after access; opening a newly created version-zero project renders a literal blank canvas and immutable recovery handle. |
| PRD-F04 | Enter Safe Mode through the handle or `Ctrl+Alt+Shift+P`. |
| PRD-F05 | Keep the last successful sidebar available if generated sidebar staging or rendering fails. |

### Session and conversation

| ID | Requirement |
|---|---|
| PRD-F06 | Create and persist independently stored, bounded-title projects under workspace `local`. |
| PRD-F07 | List and switch every shared project without losing source, data, transcript, or current version and without per-judge ownership filtering. |
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
| PRD-F22 | Preserve intent, assistant explanation, observed outcome, before/after source, validation, checkpoint identity, and event metadata in append-only semantic runtime history. |
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

### Programmable product sandbox

| ID | Requirement |
|---|---|
| PRD-F33 | Define generated authority through session-owned browser, database, identity, network, and connector resources; deny only Kernel, host, credential, cross-session, and unbounded effects. |
| PRD-F34 | Let generated server source implement product signup, login, logout, current-user, password change, account deletion, profiles, roles, and authenticated business actions through typed identity capabilities. |
| PRD-F35 | Keep PPP Control Plane credentials, provider secrets, host state, other workspaces, and parent-window cookies inaccessible. Application auth schema, hashes, account state, and failure evidence are workspace-owned data: the Shared Public POC reaches them through typed identity capabilities, while the isolated Workspace Capsule may inspect and repair them through its project nREPL and database tools. Do not place user-entered plaintext passwords in ambient provider context or logs. |
| PRD-F36 | Scope every product login to one PPP session, preserve valid logins across ordinary product changes and reloads, revoke superseded credentials, and invalidate all live logins after checkpoint restore. |
| PRD-F37 | Require the provider to distinguish PPP workspace access from accounts belonging to the generated product and to use available product capabilities instead of refusing ordinary product features. |
| PRD-F38 | Maintain and verify a capability coverage matrix for common full-stack product effects; any unsupported ordinary sandbox-owned effect is tracked as a product gap rather than described as intrinsically impossible. |
| PRD-F39 | Let generated server source create, read, list, replace, and delete bounded binary objects in the session database without receiving host paths; include object bytes in checkpoint, restore, quota, and logical resource verification. |
| PRD-F40 | Let a committed action, background job, or public ingress publish a bounded product event to every browser tab subscribed to the same session and active runtime; never publish effects from a rolled-back operation or across sessions. |
| PRD-F41 | Let generated source register named durable jobs, schedule or cancel them with an idempotency key and bounded delay/retry policy, execute them in Kernel-owned workers, and expose status without granting thread or process authority. |
| PRD-F42 | Let generated source register bounded public API/webhook handlers with optional developer-owned HMAC verifier aliases, request-size and rate limits, safe request metadata, typed JSON responses, and no PPP access-cookie authority. |
| PRD-F43 | Let generated source index, replace, delete, and query session-owned documents using Unicode full-text relevance and optional bounded numeric vectors, with deterministic ranking and no cross-session index. |
| PRD-F44 | Treat blob, event, job, ingress, and search capabilities as one checkpoint-aware resource plane. Restore preserves durable blobs and search documents, cancels restored pending/running jobs, discards ephemeral events, and restores ingress definitions from source. |

### Hosted judge operations

| ID | Requirement |
|---|---|
| PRD-F45 | Let an authorized browser log out by expiring the PPP access cookie and disposing its live host connection without modifying any project. Throttle repeated failed shared-password attempts per kernel-observed remote address. |
| PRD-F46 | Limit the real Codex provider to 100 actual process starts in any rolling 60-minute window by default, count every repair attempt, persist the global ledger across restart, and exclude the fake provider. |
| PRD-F47 | When provider capacity is exhausted, reject only new AI turns with a bounded retry signal while keeping project opening, generated actions, SQLite data, history, checkpoints, restore, Safe Mode, and logout available. |
| PRD-F48 | Preserve bounded failures from the active generated product frame and make them available only to the next AI turn through an optional, progressively disclosed diagnostic Skill. Exclude parent-window and browser-extension noise, redact secrets, and never add the diagnostics to ordinary prompt context, history, or application logs. |
| PRD-F49 | Stream actual provider lifecycle progress to the requesting tab as one volatile, bounded, event-type-derived product-language detail. Never copy provider event text or expose reasoning, source, paths, commands, models, tokens, diagnostics, or raw JSONL. |

## 9. Hackathon demo acceptance

The final video must support this exact continuous real-Codex story:

1. Begin on Projects after workspace access is already established, create a
   named project, and open its blank white canvas.
2. Type a concise dark-theme request character by character and press Enter;
   show the blank product become a coherent dark canvas without refresh.
3. Ask for Snake and immediately use its browser timer, arrow-key input, and
   visible score without refresh.
4. Type a generated-product account request and add signup, sign-in, sign-out,
   and visible recoverable errors through the Shared Public POC's typed
   product-auth bridge. State that app auth belongs to the workspace in the
   target capsule rather than to the external Control Plane.
5. Create a real product account, sign out, sign in again, reload, and keep the
   signed-in account surface visible as the product continues evolving.
6. Decide the product should be a game platform, turn the single-game page into
   a Game library, and preserve Snake plus the signed-in account surface.
7. Add timer/keyboard Tetris as the second listed game while the existing
   account and Snake remain available.

The demo fails if any change requires a terminal, file picker, manual Apply control, build, server restart, or browser refresh.

The visible recording starts on Projects and does not show PPP login, access
codes, OAuth, generated source, files, diffs, SQL, tests, or filesystem history.
The provider shown in the final video is real OAuth Codex. Fake-provider flows
remain deterministic regression tests and must not be presented as live model
generation.

Visible prompts are entered with rapid sequential typing and submitted with
Enter. The recording keeps the composer, request, progress, and result in view
instead of visually pasting text or leaving the conversation scrolled away.
Genuine provider waits may be aggressively accelerated in edit, but every such
segment is labeled and remains continuous with the real request and outcome.

The contribution section states that Codex with GPT-5.6 Terra at medium
reasoning supported product planning, implementation, the presentation deck
and film, and is the live agent behind the recorded public test server.

An extended playground gate also asks for accounts around an existing product,
registers a new user, reloads while remaining signed in, enforces one
authenticated server action, logs out, and signs in from a second fresh browser
context. This proves that PPP can extend the product's server boundary rather
than only redraw its frontend.

The complete sandbox gate then extends that same product with one uploaded
asset, indexed content, a delayed job, a cross-tab product event, and a public
ingress request. The asset and search result survive reload and checkpoint
restore, the delayed job changes SQLite without another user action, the event
updates a second open tab, and the ingress changes only its matching session.

## 10. Success criteria

### Product outcome

- A nontechnical viewer can explain the product after the three-minute demo.
- The demo communicates a live full-stack change, not only a visual transformation.
- A developer can locate complete generated source and history on disk.

### Reliability

- `bb verify` passes from a clean checkout.
- The packaged demo completes three consecutive times.
- The exact six-turn real-Codex game-platform rehearsal passes before the
  final take.
- All eight live Codex evaluation scenarios pass three times each before recording.
- Every rejected test fixture preserves source and logical SQLite hashes.
- Safe Mode recovers from a deliberately broken sidebar.
- Generated product signup, authenticated reload, protected server behavior,
  and logout pass in three fresh browser contexts.
- The complete resource-plane product passes binary round-trip, search,
  background completion, cross-tab event, public ingress, reload, and restore
  outcomes against real SQLite and the compiled browser runtime.

### Submission

- Internal rubric score is at least 80/100.
- Public secret scan is clean.
- Video is under three minutes with English narration and synchronized English
  subtitles. The narration explicitly explains how Codex and GPT-5.6 turn the
  current product context into validated runtime changes.
- README setup and sample flow work on Linux amd64.

## 11. Non-goals for the hackathon

- Public signup, accounts, organizations, workspace tenancy, or billing for the
  PPP service itself. This does not exclude accounts and permissions belonging
  to a generated product inside a session sandbox.
- PPP workspace collaborative editing, presence, cursors, or CRDTs. Generated
  products may use session-scoped product events and ordinary persisted data.
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
- Every browser with the shared password can see and modify every project in
  `local`; the hackathon build has no judge identity or ownership boundary.
- Each session owns one SQLite database.
- The app runs as one public JVM process and serves the compiled browser shell.
- The requesting browser tab alone decides client-stage commit readiness.
- Other tabs follow through broadcast or manifest resynchronization.
- Filesystem history is canonical. Codex thread state is a conversational cache.
- Checkpoints are never pruned automatically. New AI changes stop when quota is exhausted.
- Codex provider defaults to `gpt-5.6-terra` with medium reasoning and 120-second timeout.
- Real Codex process starts are globally limited to 100 in a rolling hour by
  default and stored under Kernel data; fake-provider work is unmetered.
- OAuth credentials are acceptable only for the shared-password-gated hackathon and trusted self-host deployment.

## 13. Risks

| Risk | Product response |
|---|---|
| Generated source compiles but violates intent | Domain tests, hidden client render, server contract checks, observable E2E scenarios. |
| Live code execution becomes remote code execution | Server SCI allowlist and no JVM interop; opaque-origin browser frame; path and SQL policy; no shell/filesystem/tool exposure. |
| A missing capability is mistaken for an impossible product request | Capability coverage matrix, product-identity E2E, provider guidance, and regression prompts based on owner reports. |
| A background job or webhook obtains host authority | Kernel-owned runner and ingress adapter, named generated handlers, bounded payloads, no thread/socket/server control, and session-scoped databases. |
| A binary object or search index bypasses restore or quota | Reserved SQLite resource tables, size/count limits, snapshot inclusion, logical resource hashes, and restore properties. |
| A realtime event leaks or survives a rollback | Post-commit typed effects, exact session/runtime broadcast, opaque-frame event handlers, and cross-session negative tests. |
| Product authentication leaks across workspaces or into the Control Plane | Shared Public POC typed auth and opaque cookies; Workspace Capsule containment around app-owned auth schema/data; provider and host secrets remain external; restore revocation and isolation properties. |
| Client and server change different versions | Base-version check and request-tab two-phase staging. |
| SQLite and source diverge during crash | Prepared journal, before backup, runtime metadata version comparison, idempotent recovery. |
| OAuth material leaks | Separate volume, mode 0600, no logs, no image/repository inclusion, shared-password gate. |
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
