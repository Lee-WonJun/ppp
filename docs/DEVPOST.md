# Devpost Submission Draft

Status: evidence-complete draft; links and submission remain owner-controlled
Last updated: 2026-07-16

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

## Inspiration

AI coding has become remarkably capable, but the people who shape products are often blocked before they can use it. A product manager or designer may never reach the first useful prompt because Git, folders, language runtimes, local servers, OAuth, and build errors are unfamiliar operational work.

They already know a better interaction model: open Figma to design, open Notion to write, and share the result. We wanted live product planning to work the same way.

PPP also follows a longer technical lineage. Emacs put a Lisp interpreter at the center of an extensible document tool. Lisp has been called a programmable programming language. The Deep Space 1 Remote Agent team used a spacecraft Lisp REPL to diagnose and un-wedge a running scenario. PPP brings the power to change and recover a live system out of the terminal and into a bounded SaaS experience.

## What it does

PPP opens as a white canvas with one conversation handle. The user describes the outcome they want. The assistant can:

- answer without changing the product;
- ask one focused clarification;
- generate and apply a full-stack change;
- restore an earlier checkpoint.

A change can replace the canvas, the sidebar itself, client interactions, server actions, business rules, CSS, SQLite migrations, and domain tests. The requesting browser stages the new client in a hidden opaque-origin sandbox frame while the server stages generated Clojure and a copy of SQLite. Only when both sides succeed does PPP commit and activate the new version.

The user never sees code, filenames, diffs, Git, models, skills, or MCP. Developers can still inspect the session's actual CLJ, CLJS, CLJC, CSS, SQL, tests, prompts, before/after source, and source-plus-data checkpoints.

## Demo

The demo starts from a blank canvas. We ask PPP to redesign its own conversation sidebar as a floating panel. We then ask it to build a Gallery / Submit / Leaderboard application with six seed projects, public and judge voting, server-side ranking, and SQLite persistence.

After casting votes and proving they survive a reload, we change the rule in natural language: judge votes become three points, public votes remain one, ties stay deterministic, and the top three receive podium marks. The UI and server rule activate together without refreshing.

Finally, we create another session, return to the original product and data, restore an older checkpoint, return to the newest checkpoint, and recover a deliberately broken sidebar through immutable Safe Mode.

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
- A deterministic fake provider drives CI and repeatable demo rehearsal without consuming live model quota.

Generated server code has no shell, filesystem, Java interop, MCP, skills,
dependency installation, secrets, or unrestricted network access. Generated
browser code has normal JavaScript and web-platform access only inside a
disposable opaque-origin frame; it cannot reach the authenticated parent,
cookie, recovery controls, or host authority. Public HTTP and authenticated
connectors use host-owned bounded capabilities.

## How OpenAI is used

Codex is the source-generation and reasoning provider behind each conversation turn. PPP sends the current complete source tree, transcript summary, runtime version, and capability catalog through stdin to `codex exec`. Codex returns one schema-constrained result: reply, clarification, change, or restore.

For a change, Codex writes complete CLJ, CLJS, CLJC, CSS, test, and migration contents. PPP does not trust the result directly. It validates the schema, paths, capabilities, SQL, quotas, server runtime, browser render, and version acknowledgement before committing.

The hackathon and trusted self-host mode reuse the owner's ChatGPT/Codex OAuth login to avoid per-turn API cost. OAuth is stored in a separate volume and treated as a password. A public SaaS version will replace this exception with a metered service-account/API provider.

Model target: GPT-5.6 Terra with medium reasoning.
Codex CLI target: 0.144 series.
Live evaluation: eight scenarios, three runs each, recorded separately from CI.

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
- [x] Persistent voting and project submission through generated server actions.
- [x] Atomic client/server business-rule change.
- [x] Source-plus-SQLite checkpoint restore.
- [x] Sidebar self-replacement with immutable Safe Mode recovery.
- [x] Deterministic fake-provider CI and 24-run live Codex evaluation.
- [x] Non-root Linux amd64 self-host image with no embedded credentials.

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

- Repository: `TBD_PUBLIC_GITHUB_URL`
- Demo video: `TBD_PUBLIC_YOUTUBE_URL`
- Hosted demo: `TBD_HOSTED_URL/#access=TBD_SEPARATELY_SHARED_CODE`
- Codex feedback session ID: `019f644a-b625-7a33-88f4-1ea260c3fdaa`

Do not place a real access code in the public Devpost body. Share it through the event's approved private field or judge instruction channel.

## Media plan

Required images:

1. Blank canvas with handle.
2. Floating conversation sidebar.
3. Generated Gallery product.
4. Persistent vote and leaderboard.
5. Weighted top-three podium.
6. Checkpoint history and restore.
7. Architecture diagram.

Hero image should show the running generated product and sidebar together, not a terminal or code screenshot.

## Final submission checklist

- [x] Reproducible local Git baseline and MIT license.
- [ ] Repository pushed and publicly accessible.
- [x] English README works from clean Linux amd64.
- [ ] Work & Productivity selected.
- [x] `bb verify` evidence complete.
- [x] 24 live evaluations meet thresholds.
- [x] Three packaged demo runs succeed consecutively.
- [x] Secret scan covers repository and image.
- [ ] Video public, under three minutes, with English subtitles.
- [x] Claims match completed evidence.
- [x] Codex and model roles stated accurately.
- [x] Feedback session ID recorded.
- [ ] Hosted URL works from an unauthenticated browser.
- [ ] Access code delivered privately and can be rotated.
- [ ] Owner explicitly approves deployment and submission.

Local release closure and the exact owner approval queue are tracked in
`docs/RELEASE.md` and `tickets/PPP-016.md`.
