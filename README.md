# Programmable Programming Page

> Where product conversations become running software.

Programmable Programming Page (PPP) is a self-hostable live product workspace for product managers and designers. A user describes an outcome in a browser conversation. PPP generates real Clojure, ClojureScript, CLJC, CSS, and SQLite migrations, validates them in bounded server and browser runtimes, and applies the new full-stack product without a refresh.

The user never needs to install a development environment, clone a repository, inspect files, understand Git, or operate an AI coding agent. Developers still receive a complete source tree, domain tests, and an append-only record of every accepted change.

Status: the complete session resource plane, shared judge workspace,
on-demand generated-product diagnostics, exact final-video story gate, and
the verified 168.518-second polished local real-Codex film are complete. External
publication remains owner-controlled. Automated
verification, production-configured shared-access browser tests, the 24-case
live Codex evaluation, the eight-step cumulative real-product evolution gate,
the six-of-six final-video story, and three consecutive packaged demo
rehearsals are the release evidence; exact current counts are recorded in
`docs/RUBRIC.md` and `docs/TRACEABILITY.md`.

The development-only Workspace REPL profile now also passes its own eight-step
real OAuth evolution: Codex attaches through standard nREPL, redefines running
JVM Vars, exercises browser and server behavior, repairs bounded failures, and
round-trips source, SQLite data, and both runtime surfaces through checkpoint
restore.

While Codex works, the conversation now advances one calm progress line from
actual provider lifecycle events. The Kernel exposes only fixed
product-language summaries; raw reasoning, event text, source, paths, commands,
models, and token details never reach the browser.

The final film uses a separate real-Codex story gate that evolves Snake into a
product-authenticated, SQLite-ranked game platform and then adds Tetris. Its
opening explains why PPP is an nREPL-driven product workspace rather than
source hot reload, and its closing records Codex's contribution from product
decisions through verification, judge deployment operations, and production
of the film. Real provider waits stay on the same product screen and are played
at 4x speed, so the request-to-result sequence remains continuous. The
deterministic fake provider remains test infrastructure and is never presented
as final-video generation.

In the conversation composer, Enter sends and Shift+Enter inserts a newline.

## Vision, proof, and intended architecture

### Vision

PPP is a browser workspace where a product manager or designer discusses a
running product while Codex programs that product through its live language
environment. Installation, repositories, build commands, and agent tooling are
not prerequisites for product thinking. The result still remains real source,
data, tests, and history for engineering handoff.

### Current implementation

The public hackathon build uses fresh SCI contexts on the JVM and in an
opaque-origin browser frame. SCI reads and evaluates complete generated
CLJ/CLJS candidates; the Host invokes their tests, actions, and isolated
browser render; a successful context atomically replaces the prior active
version. This is an **SCI-evaluated transactional hot swap**. It uses REPL-like
evaluation, but Codex does not connect to nREPL or progressively redefine the
same live context in this public profile.

For trusted local development, set `PPP_RUNTIME_PROFILE=workspace-repl`. In
that profile Codex is given a single project-scoped client connected through
standard loopback nREPL to the already-running JVM. It inspects and evaluates
the live server by defining and redefining actual JVM Vars in a persistent
project namespace. The active action router retains those Vars, so the next
real HTTP request sees a new `defn` immediately. The same turn can evaluate the
requesting browser runtime, repair failures, and return source only as the
durable reconciliation of behavior it already exercised. The host records the
eval evidence and refuses a change that skipped an affected runtime.

This opt-in profile is deliberately refused in production while all projects
share one JVM; loopback is transport containment, not tenant isolation.

### Why it is implemented this way

The judge deployment is one public application using the owner's Codex OAuth
capacity. Its Kernel, credentials, and many projects share one JVM process. A
raw nREPL, shell, filesystem, or dependency loader in that process would let one
generated product affect the host and every other project. The POC therefore
puts the boundary around each evaluated program with SCI capabilities,
server/browser staging, SQL policy, quotas, and last-known-good recovery.

### Current limits

The in-process boundary means Codex cannot freely install JVM/npm dependencies,
run arbitrary processes, choose another server stack, or use a normal project
filesystem and nREPL as a developer could. PPP currently recreates common
product effects as typed session resources. That is appropriate for this
shared public proof, but it is narrower than the intended creative workspace.

### Wannabe architecture

The intended self-hosted and hosted architecture gives every workspace a
disposable container, gVisor sandbox, or microVM containing its real source
tree, shell, dependencies, database, Clojure server, nREPL, shadow-cljs, and
browser CLJS REPL. Codex can explore REPL-first and repair freely inside that
capsule. The Control Plane limits the whole environment—not individual
application functions—and owns identity, credentials, routing, quotas,
snapshots, and cross-workspace isolation. An accepted checkpoint reconciles the
successful live definitions back to source, tests, data, and history.

## Why it exists

The largest barrier for many product managers and designers is not learning how to write a better prompt. It is everything before the prompt: installing tools, finding a repository, authenticating providers, running a build, and recovering when generated code breaks.

PPP moves live programming behind a familiar SaaS interaction:

```text
open workspace -> describe outcome -> use running product -> restore when needed
```

The result is not a document or mockup. It has persistent data and server-side business rules.

## A lineage of live systems

PPP did not begin with “put a chat box next to a web page.” It comes from a
line of systems where the running program remains open to extension:

- **Emacs made a document tool programmable from within.** GNU Emacs places an
  Emacs Lisp interpreter at its core. Richard Stallman's original paper
  describes a display editor whose users can add or replace functions while it
  is running—not after a compile-link-restart cycle.
- **Lisp made programs unusually available to themselves.** John Foderaro's
  phrase, preserved by GNU CLISP, calls Common Lisp a “programmable programming
  language.” Clojure carries that interactive tradition into persistent data,
  namespaces, Vars, and a modern hosted runtime.
- **nREPL connected live runtimes to tools.** It gives editors and other clients
  a message-oriented way to evaluate and inspect a running environment. That
  separation—stable runtime on one side, adaptable interface on the other—is
  part of PPP's inspiration.
- **Deep Space 1 showed why this matters beyond local development.** JPL records
  that a bug interrupted the first Remote Agent experiment. Engineer Ron
  Garret's first-hand account describes the team reaching the spacecraft's Lisp
  REPL through the Deep Space Network, diagnosing the wedged scenario, and
  injecting an event so it could continue.

The last story is often shortened to “NASA patched a spacecraft with Lisp.”
The evidence supports something more precise and just as important: engineers
used a live REPL to understand and recover a running system. JPL says the flight
source did not need to be permanently fixed for the follow-up experiment.

PPP brings that capability to product work: discuss the running system, change
it in place, observe the result, and recover. But it deliberately does **not**
publish an nREPL or arbitrary evaluator. A fixed kernel validates generated
source, stages browser/server/SQLite changes, and preserves the last successful
version before anything becomes live.

Sources: [GNU Emacs](https://www.gnu.org/software/emacs/),
[Stallman's Emacs paper](https://www.gnu.org/software/emacs/emacs-paper.html),
[GNU CLISP](https://www.gnu.org/software/clisp/clisp.html),
[nREPL](https://nrepl.org/nrepl/index.html),
[nREPL server security guidance](https://nrepl.org/nrepl/usage/server.html),
[JPL's Deep Space 1 Remote Agent record](https://www.jpl.nasa.gov/nmp/ds1/tech/autora.html),
and [Ron Garret's account](https://corecursive.com/lisp-in-space-with-ron-garret/).

### Hot swap is the mechanism, not the product thesis

Hot reload is an excellent developer tool, but it starts after a developer,
repository, source edit, build watcher, and runtime already exist. PPP changes
the authoring model and the activation contract:

| | Hot reload | PPP |
|---|---|---|
| Author | Developer editing source files | Product manager or designer describing an outcome |
| Trigger | File watcher observes a saved edit | Conversation produces a schema-constrained source change |
| Scope | Usually one client module or one server process | Client, server action, business rule, tests, and SQLite migration in one version |
| Validation | Compiler/module replacement; application-specific checks vary | Path, syntax, capability, SQL, domain test, server stage, and hidden browser render |
| Activation | Replace a module in the current development runtime | Commit only after browser and server acknowledge the same staged version |
| Persistence | The edited repository remains the record | Source, rationale, validation, history, and SQLite checkpoint remain the record |
| Failure | Build/runtime error may leave the developer to repair state | Candidate is rejected; the last successful product and data remain active |
| Audience | Speeds up an existing coding workflow | Removes the coding workflow as a prerequisite for product collaboration |

The current POC does use hot swapping internally. What differs is the authoring
and collaboration contract. Hot reload normally shortens the loop from
**developer edit to running code**. PPP
creates a different loop: **product conversation to validated running software
to recoverable engineering artifact**. It may use live replacement techniques
internally, but replacing code quickly is not the product boundary.

## Why this is different

PPP is not a chat wrapper around a template generator. It does not stop after
producing HTML, a preview, or a client-side prototype. One accepted change can
replace browser interactions, server actions, business rules, domain tests,
and SQLite schema together. The requesting browser renders the candidate in a
fresh isolated frame while the server stages the candidate runtime and a copy
of the database. Only the same validated version becomes live; failure leaves
the previous product and data intact.

The other difference is what survives the conversation. PPP keeps complete
Clojure-family source, migrations, tests, append-only history, and
source-plus-data checkpoints. Nontechnical users never have to operate those
artifacts, but developers inherit a real program rather than a screenshot or
an opaque builder format.

For OpenAI Build Week, the primary Codex task and its build/repair arc are
summarized in [the Codex feedback session guide](docs/CODEX_SESSION.md).

## Judge quick test

After entering the privately supplied shared password at the
[hosted workspace](https://ppp.openai.slopbook.org):

1. Create a blank project and ask for a timer-driven game; play it with the
   keyboard without refreshing.
2. Ask for signup/sign-in and a persistent ranking for the same game.
3. Create a product user, save a score, reload, and confirm the user and score
   remain.
4. Ask to change the ranking rule or add a second game. The existing product
   should continue instead of being replaced by a new isolated mockup.
5. Restore an earlier checkpoint, or press `Ctrl+Alt+Shift+P` to verify the
   immutable recovery surface.

The first request may take up to two minutes because it runs real Codex and
validates both runtimes. Saved product actions and navigation do not consume
the Codex queue.

## Three-minute Docker quickstart

Requirements:

- Linux amd64
- Docker Engine with Compose v2

Build and run the deterministic rehearsal provider:

```bash
PPP_AI_PROVIDER=fake docker compose up --build
```

Open the workspace and enter the development shared password `ppp-local`:

```text
http://localhost:8787/
```

Successful sign-in opens the server-wide Projects list. Create a named project
to open its literal blank canvas, or open any existing project. Everyone with
the shared password sees the same `local` workspace; PPP does not invent judge
accounts or private copies.

The fake provider exists for repeatable development, CI, packaged host
regression, and recovery checks. It does not pretend to be a general model and
is not the provider used in the final video.

For real Codex generation, authenticate once into the persistent `codex-home` volume:

```bash
docker compose run --rm app codex login --device-auth
docker compose run --rm app codex login status
docker compose up
```

Use a high-entropy shared password and cookie secret before sharing the server.
Production disables fragment access, throttles repeated failed login attempts,
and limits real Codex process starts to 100 in every rolling hour by default.
Full volume, backup, capacity, and rollback instructions are in
`docs/DEPLOYMENT.md`.

## Native development

Requirements: Java 21, Clojure CLI, Babashka, Node.js 22, and npm.

```bash
npm install
npx playwright install chromium
bb client-release
PPP_AI_PROVIDER=fake \
PPP_ACCESS_CODE=ppp-local \
PPP_COOKIE_SECRET=development-only-cookie-secret-change-before-sharing \
bb dev
```

## Codex OAuth mode

PPP can run Codex CLI on the server with the owner's ChatGPT login:

```bash
codex login --device-auth
codex login status
PPP_AI_PROVIDER=codex bb dev
```

This is deliberately limited to the shared-password-gated hackathon and trusted self-host scenario. `auth.json` is a credential and must never enter this repository, an image, logs, or session data. A public hosted service requires a service-account/API provider described in `TODOS.md`.

## Optional named connectors

Copy `connectors.edn.example` to the ignored local file `connectors.edn`, then configure only developer-owned endpoint contracts and environment-backed secret headers. Generated code and the model receive the alias, description, allowed method/path/query/body contract only; they never receive the secret, its environment-variable name, or an unrestricted URL.

Public runtime HTTP is HTTPS-only and rejects private/reserved DNS results, unsafe redirects and headers, oversized responses, and unbounded timeouts. See `docs/SECURITY.md` for the complete policy.

## Demo flow

1. Begin the visible recording on Projects with workspace access already done.
2. Create Snake and show its browser timer and keyboard input.
3. Add generated-product signup and sign-in without removing Snake.
4. Improve the first account UI, submit invalid input, and show a useful
   product-auth error.
5. Create a player, sign out, sign in, and reload while remaining signed in.
6. Add an authenticated SQLite-backed Snake ranking, save a score, and prove
   it survives reload.
7. Turn the single game into a Game library with Snake preserved.
8. Add Tetris as the second playable game while account and ranking data stay
   intact.

No change activation uses a technical Apply control, build, restart, or page
refresh. Reload is used only afterward to prove server-owned persistence.
The edited film contains no login, terminal, source, diff, SQL, test, or
history shot. Its reproducible local build pipeline is documented in
`docs/submission/video/`; the MP4 and SRT stay ignored until public upload is
separately authorized.

## Architecture

```text
Browser
┌──────────────────────────────────────────────────────┐
│ Immutable host: access, session, transport, recovery │
│  └─ Opaque sandbox frame -> browser SCI -> Reagent  │
│       ├─ generated canvas                            │
│       └─ generated conversation sidebar              │
└──────────────────────┬───────────────────────────────┘
                       │ HTTP + WebSocket
JVM server             │
┌──────────────────────▼───────────────────────────────┐
│ Fixed kernel: policy, Codex, history, journal        │
│  ├─ server SCI -> generated actions                 │
│  ├─ per-session SQLite                              │
│  ├─ blobs, search, jobs, ingress, product events     │
│  └─ restricted public HTTP / named connectors       │
└──────────────────────────────────────────────────────┘
```

Generated source is real source, not a private low-code DSL. Server code has no
shell, filesystem, Java interop, dependency installation, MCP, skills,
credentials, or unrestricted network access. Generated browser code may use
ordinary JavaScript and web APIs only inside its disposable opaque-origin
frame, never in the authenticated parent.

When that generated product fails, the active frame retains only a bounded,
redacted action/runtime/Promise/console/network reason. The next explicit user
turn may expose that volatile evidence to Codex through an isolated temporary
Skill. It is absent from normal turns, visible conversation, history, logs, and
the parent browser environment; extension noise such as MetaMask failures is
not collected.

## Project documents

- [Product thesis](docs/THESIS.md)
- [Product requirements](docs/PRD.md)
- [Implementation specification](docs/SPEC.md)
- [Security model](docs/SECURITY.md)
- [Sandboxed browser runtime decision](docs/ADR-001-SANDBOXED-CLIENT-RUNTIME.md)
- [Browser runtime incident analysis](docs/RCA-001-REPEATED-CLIENT-RUNTIME-FAILURES.md)
- [Design system](DESIGN.md)
- [Evaluation rubric](docs/RUBRIC.md)
- [Requirements traceability](docs/TRACEABILITY.md)
- [Codex feedback session guide](docs/CODEX_SESSION.md)
- [Demo script](docs/DEMO.md)
- [Devpost draft](docs/DEVPOST.md)
- [Deployment guide](docs/DEPLOYMENT.md)
- [Local release closure](docs/RELEASE.md)
- [Adopted long-term work](TODOS.md)
- [Project working agreement](AGENTS.md)
- [Implementation tickets](tickets/)

## Development workflow

Every change belongs to one Markdown ticket. Tickets include acceptance criteria, domain/property tests, evidence, and explicit exclusions. The source-of-truth order and working rules are in `AGENTS.md`.

Useful commands:

```bash
bb lint
bb format-check
bb test
bb client-test
bb client-release
bb e2e
bb hosted-access-e2e
bb docker-smoke
bb demo-preflight
bb demo-reset
bb demo-rehearsal
bb demo-live
bb verify
bb provider-capacity
```

`bb verify` must use the fake provider and make no live model calls. Live evaluation is explicit:

```bash
bb eval-live
bb eval-evolution
```

`bb demo-live` is the explicit final-video rehearsal. It uses the real OAuth
Codex provider, one fresh project, one resumed thread, compiled browser
outcomes, generated product auth, and SQLite. It never runs from CI or
`bb verify`.

`bb eval-evolution` uses one fresh session and one resumed Codex thread to move
from dark and floating visual changes to timer/keyboard Tetris, SQLite-backed
ranking, a changed server scoring rule, a client-only Gomoku replacement,
product accounts, and a complete resource workbench. The eighth turn composes
exact binary upload, Unicode search, durable background work, cross-tab product
events, and public ingress while preserving every earlier feature. The gate
fails if browser outcomes, changed runtime surfaces, generated domain tests,
repair metadata, thread continuity, persistence, or preservation do not agree.

## Docker

The release target is one Linux amd64 application container with two persistent volumes:

- `ppp-data`
- `codex-home`

Verified packaged commands:

```bash
docker compose run --rm app codex login --device-auth
docker compose run --rm app codex login status
docker compose up --build
```

The approved judge instance is available at
[ppp.openai.slopbook.org](https://ppp.openai.slopbook.org). See
`docs/DEPLOYMENT.md`; future VPS or Coolify deployments still require explicit
owner approval.

## Testing philosophy

Tests protect:

- domain invariants and business rules;
- capability and public API contracts;
- security and session boundaries;
- commit, restore, and recovery properties;
- observable user outcomes.

Tests do not freeze exact wording, CSS classes, DOM nesting, private call order, progress-event counts, or small spacing choices. Visual quality is reviewed through deliberate screenshots, while browser automation uses semantic roles and real persistent outcomes.

`bb provider-capacity` reports only safe rolling-capacity metadata. Stop the
application before the explicit owner operation `bb reset-provider-capacity`.
Fake-provider verification never consumes this ledger.

## Scope

The hackathon MVP is one shared-password-gated local workspace. It does not include public signup, judge identities, project ownership, organizations, billing, real-time collaboration, source/diff UI, automatic pull requests, runtime dependency installation, user-managed connectors, or multi-node HA.

The intended future can be either a hosted Figma-like workspace or a trusted self-hosted application. Those paths and prerequisites are recorded in `TODOS.md`.

## License

[MIT](LICENSE)
