# Codex Feedback Session Guide

Submitted session ID: `019f644a-b625-7a33-88f4-1ea260c3fdaa`

Task title: `PPP — OpenAI Build Week build, verification, and judge feedback`

This is the primary Codex task in which Programmable Programming Page moved
from a product thesis to a working, hosted full-stack runtime. It is long
because it preserves the actual collaboration: the owner repeatedly tested the
browser, rejected synthetic or client-only substitutes, reported failures, and
required Codex to find the root cause and prove the original user path.

## Ninety-second reading guide

PPP is not a prompt-to-mockup tool. A conversation produces real CLJ, CLJS,
CLJC, CSS, SQL migrations, and domain tests. The result is staged in an
isolated browser frame and server runtime, committed with its SQLite data only
after both sides agree, and saved as a recoverable checkpoint.

The submitted task demonstrates four things:

1. **Product judgment came from the owner.** The owner defined the audience as
   product managers and designers blocked by installation, Git, local servers,
   and OAuth—not by prompt writing. They insisted on a Figma/Notion-like SaaS
   interaction and on real source that developers can continue.
2. **Codex did the engineering work.** Codex turned that direction into the
   PRD, specification, security boundary, Markdown tickets, Clojure/ClojureScript
   implementation, tests, Docker packaging, demo tooling, and deployment
   evidence.
3. **Failures changed the architecture.** Real browser reports exposed frame
   startup timing, Korean IME corruption, over-restricted browser capabilities,
   generic failure messages, missing client diagnostics, and static progress.
   The fixes became durable runtime contracts and regression gates rather than
   demo-specific patches.
4. **Completion was measured in the running product.** The final gates use
   real OAuth Codex, one resumed thread, browser timers and keyboard input,
   generated product accounts, authenticated server actions, SQLite reload
   persistence, and cumulative game-platform evolution.

## Collaboration arc

| Phase | Owner decision or observed failure | Codex result | Evidence |
|---|---|---|---|
| Product thesis | Nontechnical collaborators should edit a running product, not learn Git or agent tooling. | PRD, Lisp/REPL lineage, modular-monolith architecture, source-plus-history handoff. | [`THESIS.md`](THESIS.md), [`PRD.md`](PRD.md), [`SPEC.md`](SPEC.md) |
| Runtime boundary | A client-only builder or public server REPL was rejected. The sandbox should allow a product to do almost anything without reaching the host. | Opaque-origin browser runtime, capability-limited server runtime, atomic browser/server/SQLite activation. | [`ADR-001`](ADR-001-SANDBOXED-CLIENT-RUNTIME.md), [`SECURITY.md`](SECURITY.md), `PPP-013`, `PPP-020`, `PPP-021` |
| Owner reproduction | Fresh sessions failed after a delay even though narrow tests passed. | Root-cause analysis, delayed-frame regression, three fresh contexts, stable owner-facing port discipline. | [`RCA-001`](RCA-001-REPEATED-CLIENT-RUNTIME-FAILURES.md), `PPP-017` |
| Real input and feedback | Korean composition corrupted text; failures said only “not applied”; progress was a static word. | IME-safe frame-local drafts, deepest bounded stage reason, optional client diagnostics, streamed product-language Codex progress. | `PPP-018`, `PPP-023`, `PPP-026` |
| Product breadth | Timer games, signup/login, ranking, server-rule changes, and replacement of one game with another must work in one sandbox. | Generated product identity, SQLite actions, browser interop, resources, events, jobs, ingress, and search. | `PPP-020`, `PPP-021`, `bb eval-evolution` |
| Submission proof | The demo must use real Codex, not the deterministic test fixture, and the public host must preserve OAuth. | Six-turn real-Codex video story, English narration/subtitles, Coolify deployment, restart-persistent OAuth and checkpoint. | `PPP-024` through `PPP-027` |

## How Codex and GPT-5.6 are used

The browser conversation reaches the server's Codex provider. PPP sends the
current source tree, capability catalog, runtime version, and bounded
conversation context to `codex exec` through stdin. GPT-5.6 reasons over that
state and Codex returns one schema-constrained reply, clarification, change, or
restore result.

For changes, Codex writes complete source files and migrations. PPP—not the
model—owns trust: it validates paths, syntax, capabilities, SQL, quotas,
generated domain tests, server staging, hidden browser rendering, exact
version acknowledgement, history, and rollback. Raw model reasoning and source
are never streamed into the product UI.

## What to verify instead of trusting this summary

- Run `bb verify` for the deterministic release contract.
- Run `bb eval-live`, `bb eval-evolution`, or `bb demo-live` explicitly for
  OAuth-backed model evaluation; none runs in CI or inside `bb verify`.
- Open the judge host, create a project, request a client-only change, then add
  a SQLite-backed server action and reload.
- Restore a checkpoint or use Safe Mode to confirm that programmability does
  not remove recovery.
- Inspect [`TRACEABILITY.md`](TRACEABILITY.md) for the requirement-to-ticket-to-
  evidence map.

## Honest limits

The hackathon build uses one shared-password workspace, not private accounts or
real-time collaboration. The Codex OAuth provider is appropriate only for this
trusted, rate-limited judge deployment and self-hosting; a public SaaS needs a
metered API/service-account provider. Automatic Git/PR promotion is future
work. These limits are deliberate and are not hidden by the demo.
