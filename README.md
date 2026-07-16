# Programmable Programming Page

> Where product conversations become running software.

Programmable Programming Page (PPP) is a self-hostable live product workspace for product managers and designers. A user describes an outcome in a browser conversation. PPP generates real Clojure, ClojureScript, CLJC, CSS, and SQLite migrations, validates them in bounded server and browser runtimes, and applies the new full-stack product without a refresh.

The user never needs to install a development environment, clone a repository, inspect files, understand Git, or operate an AI coding agent. Developers still receive a complete source tree, domain tests, and an append-only record of every accepted change.

Status: locally complete release candidate closure is tracked by PPP-016.
Automated verification, the 24-case live
Codex evaluation, a six-turn real product-evolution evaluation, and three
consecutive packaged demo rehearsals pass. Final video publication, hosted
deployment, and Devpost submission remain owner-controlled steps.

In the conversation composer, Enter sends and Shift+Enter inserts a newline.

## Why it exists

The largest barrier for many product managers and designers is not learning how to write a better prompt. It is everything before the prompt: installing tools, finding a repository, authenticating providers, running a build, and recovering when generated code breaks.

PPP moves live programming behind a familiar SaaS interaction:

```text
open workspace -> describe outcome -> use running product -> restore when needed
```

The result is not a document or mockup. It has persistent data and server-side business rules.

## Three-minute Docker quickstart

Requirements:

- Linux amd64
- Docker Engine with Compose v2

Build and run the deterministic rehearsal provider:

```bash
PPP_AI_PROVIDER=fake docker compose up --build
```

Open:

```text
http://localhost:8787/#access=ppp-local
```

The fake provider exists for repeatable development, CI, and demo rehearsal. It supports the exact prompts in `docs/DEMO.md`; it does not pretend to be a general model.

For real Codex generation, authenticate once into the persistent `codex-home` volume:

```bash
docker compose run --rm app codex login --device-auth
docker compose run --rm app codex login status
docker compose up
```

Use non-development access and cookie secrets before sharing the server. Full volume, backup, and rollback instructions are in `docs/DEPLOYMENT.md`.

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

This is deliberately limited to the access-code-gated hackathon and trusted self-host scenario. `auth.json` is a credential and must never enter this repository, an image, logs, or session data. A public hosted service requires a service-account/API provider described in `TODOS.md`.

## Optional named connectors

Copy `connectors.edn.example` to the ignored local file `connectors.edn`, then configure only developer-owned endpoint contracts and environment-backed secret headers. Generated code and the model receive the alias, description, allowed method/path/query/body contract only; they never receive the secret, its environment-variable name, or an unrestricted URL.

Public runtime HTTP is HTTPS-only and rejects private/reserved DNS results, unsafe redirects and headers, oversized responses, and unbounded timeouts. See `docs/SECURITY.md` for the complete policy.

## Demo flow

1. Start from a white canvas and open the conversation handle.
2. Ask the sidebar to redesign itself as a floating panel.
3. Ask for a Gallery / Submit / Leaderboard product with six seed projects and persistent public/judge voting.
4. Vote, reload, and verify persistence.
5. Make judge votes worth three points, public votes worth one, and mark the top three.
6. Create a second session, switch back, and recover the first product and data.
7. Restore an old checkpoint, then return to the newer checkpoint.

No change uses a technical Apply control, build, restart, or page refresh.

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
│  └─ restricted public HTTP / named connectors       │
└──────────────────────────────────────────────────────┘
```

Generated source is real source, not a private low-code DSL. The fixed kernel withholds shell, filesystem, Java/JavaScript interop, dependency installation, MCP, skills, credentials, and unrestricted network access.

## Project documents

- [Product thesis](docs/THESIS.md)
- [Product requirements](docs/PRD.md)
- [Implementation specification](docs/SPEC.md)
- [Security model](docs/SECURITY.md)
- [Design system](DESIGN.md)
- [Evaluation rubric](docs/RUBRIC.md)
- [Requirements traceability](docs/TRACEABILITY.md)
- [Demo script](docs/DEMO.md)
- [Devpost draft](docs/DEVPOST.md)
- [Deployment guide](docs/DEPLOYMENT.md)
- [Local release closure](docs/RELEASE.md)
- [Implementation tickets](tickets/PPP-001.md)

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
bb docker-smoke
bb demo-preflight
bb demo-reset
bb demo-rehearsal
bb verify
```

`bb verify` must use the fake provider and make no live model calls. Live evaluation is explicit:

```bash
bb eval-live
bb eval-evolution
```

`bb eval-evolution` uses one fresh session and one resumed Codex thread to move
from dark and floating visual changes to timer/keyboard Tetris, SQLite-backed
ranking, a changed server scoring rule, and a client-only Gomoku replacement.
It fails if browser outcomes, changed runtime surfaces, generated domain tests,
repair metadata, thread continuity, or persisted data do not agree.

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

See `docs/DEPLOYMENT.md`. No VPS or Coolify deployment is performed without explicit owner approval.

## Testing philosophy

Tests protect:

- domain invariants and business rules;
- capability and public API contracts;
- security and session boundaries;
- commit, restore, and recovery properties;
- observable user outcomes.

Tests do not freeze exact wording, CSS classes, DOM nesting, private call order, progress-event counts, or small spacing choices. Visual quality is reviewed through deliberate screenshots, while browser automation uses semantic roles and real persistent outcomes.

## Scope

The hackathon MVP is one access-code-gated local workspace. It does not include public signup, organizations, billing, real-time collaboration, source/diff UI, automatic pull requests, runtime dependency installation, user-managed connectors, or multi-node HA.

The intended future can be either a hosted Figma-like workspace or a trusted self-hosted application. Those paths and prerequisites are recorded in `TODOS.md`.

## License

[MIT](LICENSE)
