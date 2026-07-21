# Devpost Submission Draft

Status: final public capture, video upload, and Devpost resubmission in progress
Last updated: 2026-07-22

The Devpost project page is public, but its OpenAI Build Week entry has not been
submitted (`submitted_at` is empty). Never interpret this document, a
repository push, or a working hosted URL as a completed hackathon submission.

## Submission fields

### Project name

Programmable Programming Page

### Tagline

Where product conversations become running software.

### Category

Work & Productivity

Use one category unless the event form requires otherwise.

### Short description

PPP is a browser workspace where product managers and designers build and change a running full-stack product through conversation, without installing tools or seeing code, files, or Git. Every accepted change remains real Clojure-family source, persistent SQLite data, tests, and recoverable history for developers to continue.

## Vision and hackathon proof

**Vision.** Codex should freely program a running product through its live
language environment while a product manager or designer stays in a familiar
browser conversation. The result remains a developer-continuable engineering
artifact.

**Current implementation.** The public build uses server and browser SCI to
read and evaluate complete generated Clojure-family candidates in fresh,
versioned contexts. The Host invokes tests, actions, and an isolated browser
render, then atomically replaces the active version. This is an SCI-evaluated
transactional hot swap, not a claim that Codex currently connects through or
progressively mutates a live nREPL.

The repository also includes a development-only Workspace REPL profile that
does implement that missing loop. It starts standard nREPL on loopback, keeps a
long-lived project session, and gives Codex one project-scoped client. Codex
inspects the running server, defines and redefines actual JVM Vars used by the
active action router, invokes real
actions against SQLite, and routes client forms to the active sandboxed browser
before reconciling the accepted behavior to complete source and a checkpoint.
It is disabled in production because same-process nREPL is trusted developer
authority, not tenant isolation.

The repository's explicit Workspace REPL evaluation evolves one real product
through eight cumulative client/server scenarios and passes the complete
browser outcome gate, including SQLite persistence, product accounts, Unicode
file storage/search, durable jobs, cross-tab events, public ingress, bounded
repair, and checkpoint rewind/return.

**Why this implementation.** The judge demo is a public application sharing
one JVM and consuming the owner's Codex OAuth capacity. Giving generated code a
raw host nREPL, shell, filesystem, or dependency loader would expose PPP itself
and every project. SCI capabilities and atomic staging make this proof safe to
share.

**Current limits.** The shared-process POC cannot provide the unrestricted
filesystem, processes, dependencies, and framework choice of a normal
development workspace. Its typed resources prove the live product workflow,
but are not the intended ceiling.

**Wannabe architecture.** Each project receives a disposable container or
stronger sandbox with real source, shell, dependencies, database, server
nREPL, browser CLJS REPL, product authentication, and app diagnostics. Codex
works REPL-first inside it; the external
Control Plane protects credentials, the host, and other workspaces. Accepted
checkpoints reconcile live definitions back to source, tests, data, and history.

## Inspiration

AI coding has become remarkably capable, but the people who shape products are often blocked before they can use it. A product manager or designer may never reach the first useful prompt because Git, folders, language runtimes, local servers, OAuth, and build errors are unfamiliar operational work.

They already know a better interaction model: open Figma to design, open Notion to write, and share the result. We wanted live product planning to work the same way.

PPP also follows a longer technical lineage. Emacs put a Lisp interpreter at
the center of an extensible document tool. Lisp has been called a programmable
programming language. nREPL showed how editors and other interfaces can connect
to, inspect, and change a running environment. During the Deep Space 1 Remote
Agent experiment, the team used a spacecraft Lisp REPL to diagnose a wedged
scenario and inject an event that let it continue. The flight source was not
permanently patched in space; the lesson is that a live system can be understood
and recovered without waiting for a conventional rebuild-and-redeploy loop.

PPP brings that power out of the terminal and into a bounded SaaS experience.
It never publishes a raw nREPL endpoint. The public profile accepts a
structured change and stages both runtimes and SQLite; the trusted development
profile attaches Codex through an internal project-scoped nREPL and then
reconciles the observed live result. Both keep the last successful product
recoverable.

## What it does

After shared sign-in and project selection, PPP opens the chosen product as a
white canvas with one conversation handle. The user describes the outcome they
want. The assistant can:

- answer without changing the product;
- ask one focused clarification;
- generate and apply a full-stack change;
- restore an earlier checkpoint.

A change can replace the canvas, the sidebar itself, client interactions, server actions, business rules, CSS, SQLite migrations, and domain tests. The requesting browser stages the new client in a hidden opaque-origin sandbox frame while the server stages generated Clojure and a copy of SQLite. Only when both sides succeed does PPP commit and activate the new version.

This is the central difference from a prompt-to-page builder: PPP activates a
versioned full-stack program, not just a generated preview. Browser behavior,
server rules, tests, schema, and persistent data can evolve together. Failed
candidates never replace the last successful source or database, and every
accepted state remains recoverable.

The current POC uses hot swapping, but its product contract is broader than a
file watcher. Hot reload watches a developer's saved file
and replaces a module in an existing development environment. PPP starts with
a product conversation, generates the source, validates client/server/SQL/test
boundaries, renders the candidate in isolation, and advances browser code,
server behavior, SQLite, history, and checkpoint as one version. Hot reload
shortens a coding loop; PPP changes the authoring unit to a product
conversation and preserves the observed full-stack transition for
collaboration, while leaving a real engineering artifact behind.

The hosted judge flow begins with one shared password and one server-wide
Projects list. Judges do not receive fabricated accounts or private copies;
they can open the same saved products or create a named blank project. Inside a
project, the user never sees code, filenames, diffs, Git, models, skills, or
MCP. Developers can still inspect the session's actual CLJ, CLJS, CLJC, CSS,
SQL, tests, prompts, before/after source, and source-plus-data checkpoints.

The public POC uses typed product-auth operations because its projects share a
JVM. This does not place the prototype's account database in the Control Plane.
In the intended capsule profile, Codex may inspect and replace product auth,
account rows, password hashes, and application logs while PPP access, owner
OAuth, host state, and other workspaces remain unreachable.

## Demo

The video uses one fresh project on the current public judge server. The first
request establishes a polished dark arcade. The second creates playable Snake
with a real browser timer and keyboard input. The third crosses the server
boundary by adding real product signup and sign-in plus a SQLite-backed public
profile while preserving Snake.

The fourth request improves the account experience. The film then submits a
real invalid identifier and shows the actionable error, creates Player One,
signs out, signs in, and reloads. The account and game remain because these are
real authenticated server actions and durable workspace data, not optimistic
browser labels.

One final product decision turns the arcade into a Game library and adds
timer/keyboard Tetris without losing Snake or the account. The same-session
showcase revisits login, the library, Snake, and Tetris.

The capture uses the public instance's real OAuth Codex provider with GPT-5.6
Terra at medium reasoning. Every provider wait remains a continuous part of the
real capture and is truthfully accelerated at 24x or 30x. The deterministic
fake provider remains CI and packaged-regression infrastructure; it does not
appear in the video.

### Two-minute judge test

1. Enter the private shared password and create a blank project.
2. Ask for a dark product direction, then a timer-driven keyboard game.
3. Add product signup and sign-in while preserving the game.
4. Trigger a real validation error, then create, log out, log in, and reload the
   product account.
5. Add a game library and Tetris while preserving Snake and the account.
6. Restore a checkpoint or use Safe Mode (`Ctrl+Alt+Shift+P`).

Real Codex generation can take up to two minutes; playing the generated
product, navigating Projects, and using saved actions do not consume a turn.

## How we built it

PPP is one JVM Clojure application that serves its ClojureScript browser host.

- Clojure, http-kit, Reitit, and Integrant form the fixed server kernel.
- SCI evaluates generated Clojure in a namespace and symbol allowlist.
- shadow-cljs, Reagent, and browser SCI evaluate generated client components.
- A disposable opaque-origin sandbox iframe contains generated canvas, sidebar, CSS, and rendering while keeping the authenticated parent inaccessible.
- SQLite and next.jdbc provide one persistent database per session.
- A versioned WebSocket protocol coordinates hidden browser staging and request-tab acknowledgement.
- Filesystem history is canonical; each successful checkpoint contains source plus a consistent SQLite snapshot.
- Codex CLI runs non-interactively with structured JSON output, a read-only sandbox, an empty work directory, and all tools disabled.
- A deterministic fake provider drives CI and packaged host regression without
  consuming live model quota; the final-video rehearsal is a separate explicit
  real-Codex gate.

Generated server code has no shell, filesystem, Java interop, MCP, skills,
dependency installation, secrets, or unrestricted network access. Generated
browser code has normal JavaScript and web-platform access only inside a
disposable opaque-origin frame; it cannot reach the authenticated parent,
cookie, recovery controls, or host authority. Public HTTP and authenticated
connectors use host-owned bounded capabilities.

## How OpenAI is used

Codex is the source-generation and reasoning provider behind each conversation turn. PPP sends the current complete source tree, transcript summary, runtime version, and capability catalog through stdin to `codex exec`. Codex returns one schema-constrained result: reply, clarification, change, or restore.

For a change, Codex writes complete CLJ, CLJS, CLJC, CSS, test, and migration contents. PPP does not trust the result directly. It validates the schema, paths, capabilities, SQL, quotas, server runtime, browser render, and version acknowledgement before committing.

If the active generated product later fails, PPP keeps a bounded, redacted
reason from that exact sandbox frame. On the next relevant user turn, Codex can
read the volatile evidence through an isolated temporary Skill. Normal prompts
remain free of diagnostic text, the evidence is never added to product history
or logs, and parent/extension noise is excluded.

The hackathon and trusted self-host mode reuse the owner's ChatGPT/Codex OAuth
login to avoid per-turn API cost. OAuth is stored in a separate volume and
treated as a password. The hosted judge instance admits at most 100 real Codex
process starts in every rolling hour by default, including repair attempts;
saved products, actions, data, and restore remain usable when new changes are
temporarily unavailable. A public SaaS version will replace this exception
with a metered service-account/API provider.

Model target: GPT-5.6 Terra with medium reasoning.
Codex CLI target: 0.144 series.
Live evaluation: eight scenarios, three runs each, recorded separately from CI.

The submitted `/feedback` task is the majority-build session, not a curated
one-turn example. Its owner decisions, browser failures, architectural repairs,
and evidence map are summarized in
[`docs/CODEX_SESSION.md`](docs/CODEX_SESSION.md). The session's rough edges are
part of the evidence: owner reproduction repeatedly overruled narrower
synthetic passes and produced stronger runtime contracts.

## Challenges

### Making a live change atomic

A browser and JVM are separate runtimes even when both use the Clojure family. We built a two-phase flow: stage source and SQLite on the server, render the client in a fresh hidden sandbox frame in the requesting tab, verify exact base and target versions, then commit and broadcast.

### Letting the sidebar edit itself without losing recovery

The visible sidebar is programmable, but the handle, Safe Mode, transport, sessions, and last successful fallback are immutable host code. Generated CSS lives inside the sandbox frame and cannot reach or hide the parent recovery control.

### Preserving source and data together

A UI checkpoint is insufficient when business rules and SQLite data also change. PPP stores complete source manifests and consistent database snapshots as one runtime version, then records restore as a new append-only event.

### Using direct generated code safely

We intentionally avoided a low-code DSL. Codex writes real Clojure-family source. The safety boundary is the host capability catalog and SCI contexts, combined with source, SQL, network, staging, timeout, and quota policies.

## Accomplishments

- [x] A no-refresh full-stack product generated from one conversation.
- [x] Generated product signup, sign-in, logout, and authenticated reload.
- [x] Persistent ranking through generated server actions and SQLite.
- [x] Browser-only game changes preserve existing server-owned features.
- [x] Source-plus-SQLite checkpoint restore.
- [x] Sidebar self-replacement with immutable Safe Mode recovery.
- [x] Deterministic fake-provider CI and 24-run live Codex evaluation.
- [x] Non-root Linux amd64 self-host image with no embedded credentials.
- [x] On-demand active-product failure evidence without persistent telemetry
  or parent-window collection.

Unchecked items must not appear as completed claims in the submitted text.

## What we learned

The interesting boundary is not code generation. It is activation. A generated source file becomes a product only after both runtimes, persistent data, and the requesting browser agree on one version.

We also learned that hiding code does not mean discarding engineering structure. Keeping complete source files, domain-oriented tests, manifests, and checkpoints makes the nontechnical experience more trustworthy and creates a stronger developer handoff.

Finally, a REPL is best understood here as a product property: a running system can accept a new definition and continue. The public interface still needs policy, staging, history, and recovery around that property.

## What's next

1. Hosted identity and private workspaces once external user demand validates the workflow.
2. A Responses API/service-account provider with per-workspace metering before public signup.
3. Form-aware source promotion into a developer-reviewed Git branch or pull request.
4. User-managed connectors only after encrypted secret storage, roles, scopes, and audit exist.
5. Runtime SDK extraction only after a second independent embedding proves the shared abstraction.

## Built with

```text
Clojure
ClojureScript
OpenAI Codex
GPT-5.6 Terra
SCI
Reagent
shadow-cljs
http-kit
Reitit
Integrant
SQLite
next.jdbc
Malli
Transit
test.check
Kaocha
Playwright
Babashka
Docker
```

## Links

- Repository: [github.com/Lee-WonJun/ppp](https://github.com/Lee-WonJun/ppp)
- Demo video: `TBD_PUBLIC_YOUTUBE_URL`
- Hosted demo: [ppp.openai.slopbook.org](https://ppp.openai.slopbook.org)
- Codex feedback session ID: `019f644a-b625-7a33-88f4-1ea260c3fdaa`
- Feedback-session reading guide: [`docs/CODEX_SESSION.md`](docs/CODEX_SESSION.md)

Do not place the shared password in the public Devpost body or URL. Share it
through the event's approved private field or judge instruction channel.

## Media plan

Required images:

1. Blank canvas with handle.
2. Dark arcade with conversation sidebar.
3. Playable timer/keyboard Snake.
4. Visible account validation message and signed-in state.
5. Game library with Snake and Tetris.
6. Playable Tetris with the account preserved.
7. Shared POC versus per-workspace nREPL architecture diagram.

Hero image should show the running generated product and sidebar together, not a terminal or code screenshot.

## Final submission checklist

- [x] Reproducible local Git baseline and MIT license.
- [x] Repository pushed and publicly accessible.
- [x] Public Devpost project has tagline, description, technology list,
  repository link, and hosted-demo link.
- [x] English README works from clean Linux amd64.
- [ ] Work & Productivity selected.
- [x] `bb verify` evidence complete.
- [x] 24 live evaluations meet thresholds.
- [x] Three packaged demo runs succeed consecutively.
- [x] Secret scan covers repository and image.
- [ ] New sub-three-minute public-server film is verified at 1440x900/30fps with
  English narration, burned and embedded synchronized English subtitles, real
  Codex product footage only, and an explicit explanation of nREPL plus how
  Codex and GPT-5.6 are used across planning, implementation, presentation,
  film production, and the deployed product agent.
- [ ] Video uploaded and publicly accessible.
- [x] Claims match completed evidence.
- [x] Codex and model roles stated accurately.
- [x] Feedback session ID recorded.
- [x] Hosted URL works from an unauthenticated browser and opens shared sign-in.
- [ ] Shared password delivered privately and can be rotated.
- [x] Production browser gate proves three fresh contexts, a common Projects list, and JVM restart persistence.
- [x] Owner explicitly approved the judge deployment.
- [x] Owner explicitly approves the Devpost submission.

Local release closure and the exact owner approval queue are tracked in
`docs/RELEASE.md` and `tickets/PPP-035.md`.
