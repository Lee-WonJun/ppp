# Product Thesis

Status: source of truth
Last updated: 2026-07-20

## The claim

The next useful interface for product planning is not a better prompt box around a code editor. It is a running product that can be discussed and changed in place.

We believe product managers and designers will increasingly create real
working product behavior, including server rules and persistent data. The
limiting factor is not whether they can imagine the product or write a clever
prompt. Many never reach the first useful prompt because installing Codex,
Git, runtimes, dependencies, a repository, OAuth, and a local server is already
a development project.

Programmable Programming Page (PPP) gives a product manager or designer a familiar SaaS surface. They sign in, open a workspace, and describe an outcome. The workspace changes immediately. The server behavior, persistent data, interface, and business rules remain real source code that a developer can inspect and extend later.

The user does not need to install a language runtime, clone a repository, understand a filesystem, configure OAuth, learn Git, or discover agent-specific concepts. Those are implementation concerns, not prerequisites for product thinking.

## The lineage

### Emacs: a document surface backed by a live interpreter

GNU describes Emacs as an extensible editor whose core contains an Emacs Lisp interpreter. Richard Stallman's 1981 Emacs paper goes further: Emacs can be extended while it is running, and an online extensible system must be able to accept and execute new code without a compile-link-restart cycle.

PPP borrows the structural idea, not the editor interface. A stable host protects access, recovery, and persistence. A live language runtime controls the product surface built on top of it.

Sources:

- [GNU Emacs](https://www.gnu.org/software/emacs/)
- [EMACS: The Extensible, Customizable Display Editor](https://www.gnu.org/software/emacs/emacs-paper.html)

### Lisp: a programmable programming language

The GNU CLISP manual preserves John Foderaro's concise description: "Common Lisp is a programmable programming language."

Lisp programs can represent program structure with the same data structures used by the language. Clojure adds persistent data structures, namespaces, Vars, atoms, and an ecosystem designed around interactive development. PPP uses those properties to keep generated changes inspectable, testable, and replayable instead of turning them into opaque UI configuration.

Source: [GNU CLISP](https://www.gnu.org/software/clisp/clisp.html)

### nREPL: connect tools to a running environment

nREPL turns the REPL from a terminal convention into a message-oriented
tooling protocol. Its official overview describes a Clojure network REPL for
editors and other tools that evaluate, inspect, debug, experiment with, and
even update running applications. The interface and the runtime do not need to
be the same process or user experience.

PPP borrows that separation. The generated product runtime stays alive while a
different surface—the browser conversation—describes and observes changes. But
PPP does not expose the nREPL protocol itself. Official nREPL server guidance
warns that a default unauthenticated public endpoint lets a connection modify
application behavior or run code on the host. PPP replaces that authority with
a narrow change contract, capability catalog, staged evaluators, tests,
version checks, history, and recovery.

Sources:

- [nREPL overview](https://nrepl.org/nrepl/index.html)
- [nREPL server and security guidance](https://nrepl.org/nrepl/usage/server.html)

### Deep Space 1: diagnose and recover a running system

NASA/JPL's official record establishes that the Remote Agent experiment ran an autonomous planning and execution system aboard Deep Space 1. Ron Garret, an engineer on the project, later described the operational detail: the team communicated with a Lisp REPL on the spacecraft through the Deep Space Network, diagnosed a wedged scenario, and injected an event that allowed the scenario to continue.

This story must be stated carefully. The cited account supports live diagnosis and recovery through the REPL. It does not establish that the team permanently patched the flight source tree in space. PPP deliberately separates those two acts:

1. Apply a validated change to the live runtime.
2. Preserve the exact source and data state so it can later be promoted into a conventional development workflow.

Sources:

- [JPL: Deep Space 1 Remote Agent](https://www.jpl.nasa.gov/nmp/ds1/tech/autora.html)
- [Ron Garret: LISP in Space](https://www.corecursive.com/lisp-in-space-with-ron-garret/)

## What PPP changes

Traditional AI coding products begin after a repository and development environment already exist. That starting point excludes many product managers and designers. Their barrier is frequently not prompt quality. It is the installation and operational chain before the first prompt:

```text
install tools
configure a folder
clone a repository
understand branches
authenticate providers
run a build
find the right command
interpret errors
```

PPP replaces that chain with the interaction model already taught by Figma and Notion:

```text
open workspace
describe outcome
use the running result
continue the conversation
return to an earlier checkpoint when needed
```

The resulting artifact is stronger than a document or mockup. It contains
executable business rules, a persistent SQLite database, client behavior,
server actions, domain tests, causal runtime history, and materialized source.

## The AI-era REPL development loop

PPP is REPL-driven development translated from a developer tool into a product
collaboration model:

```text
describe intent
-> evaluate in the running environment
-> use and observe the result
-> repair through the next conversation
-> accept or restore a checkpoint
```

The collaboration unit is a **semantic runtime transition**, not a textual
patch. A useful history entry answers:

- What outcome did the collaborator request?
- What client, server, data, and rule behavior was evaluated?
- What did the running product actually demonstrate?
- Which domain and safety evidence passed?
- Which complete source-and-data state was accepted or restored?

This is why code is deliberately absent from the normal product surface. The
planner or designer is editing product behavior. Materialized source exists so
the runtime is reproducible and engineering can continue it, not because file
editing is secretly still the collaboration model.

## Could this be Python plus hot reload?

Yes. Another language can reproduce the visible PPP experience if it provides
a persistent evaluator, safe isolation, transactional staging, observable
results, semantic history, and source reconciliation. The product thesis does
not depend on claiming that only Lisp can do live programming.

Clojure is a strong implementation fit because code forms are data, long-lived
namespaces and Vars are designed to be redefined, and REPL-driven development
is an established practice rather than an added file-watcher convention. SCI
also lets the public proof create explicit, isolated evaluation contexts on
the JVM and in the browser.

The meaningful distinction is therefore not “Clojure can, Python cannot.” It
is **runtime-first collaboration versus file-first collaboration**. A typical
hot-reload loop treats the edited file as the canonical cause and reloads the
runtime as a consequence. A REPL-first loop changes the living environment,
observes it, repairs it through further evaluation, and reconciles an accepted
state to durable source afterward. A Python system built around that latter
contract would be philosophically compatible with PPP.

## Why hot swap is not the product thesis

Hot reload observes a developer's file changes, recompiles or reloads the
affected module, and updates a development runtime. It is an implementation
technique inside an already established coding workflow. It assumes someone
has the repository, understands the source, runs the watcher, and owns any
repair when the new module fails.

The default Shared Public POC does use an SCI-evaluated transactional hot swap:
it evaluates complete candidate source in fresh contexts and activates the
successful version. PPP begins before the ordinary file-watcher workflow,
however. A nontechnical collaborator describes an
outcome; Codex writes complete source; the fixed host validates paths, syntax,
capabilities, SQL, and domain tests; the server stages code and a database copy;
and the requesting browser renders the same candidate version in an isolated
frame. Only then do source, server registry, client manifest, SQLite, history,
and checkpoint advance together.

PPP now also has a trusted Workspace REPL implementation. Codex uses a standard
nREPL client to attach to the already-running JVM, evaluates incremental forms
as real Clojure Vars in a persistent project namespace, and registers those
Vars in the current action router. A later `defn` changes what the next real
HTTP action invokes. Codex observes that server action or browser
render, and repairs failures in the same session. Complete source is returned
afterward as reconciliation, not as the mechanism that caused the first live
behavior. That is the REPL-driven loop; transactional hot swap remains the
bounded public judge profile.

## The handoff contract

PPP does not remove developers. It changes the point at which they enter the process.

- The product manager or designer works with observable outcomes.
- The runtime records the intent, observed result, validation, checkpoint, and
  complete CLJ, CLJS, CLJC, CSS, SQL, and tests.
- A developer sees the causal product decisions and accepted behavior before
  judging incidental generated implementation choices.
- A developer can reproduce the same state, then harden, rewrite, or extend it
  without reverse-engineering a mockup or inheriting an unexplained code dump.
- Future source-promotion tooling may turn accepted runtime work into a branch or pull request, but that automation is outside the hackathon MVP.

Git remains valuable at that engineering boundary. It is not the primary PPP
interaction because line changes alone do not preserve product intent,
runtime observation, or the data state against which a decision was accepted.

## Why a bounded runtime

PPP is not a public nREPL endpoint. Generated server code receives no shell,
general filesystem access, Java interop, credentials, arbitrary network access,
MCP servers, or skills. Generated browser code may use normal JavaScript and
web-platform APIs only inside a disposable opaque-origin frame that cannot
reach the authenticated parent.

The AI writes real Clojure-family source, but a fixed kernel decides what that source may do. SCI evaluates it with an explicit namespace and symbol catalog. SQL migrations are validated and applied to a staging database. Client code must render in a fresh hidden opaque-origin sandbox frame before the live state commits. Every successful version can be restored together with its source and data.

This boundary preserves the essential property of a REPL—changing a running
system—and nREPL's separation between tools and runtime, while removing the
host-level authority that makes a raw public endpoint unsuitable for a product
surface shared with non-programmers.

The bounded SCI runtime is the implementation profile for this public proof,
not the complete product ambition. It protects one shared JVM and the owner's
Codex OAuth capacity while judges can submit arbitrary prompts. This is why the
public `shared-poc` Codex process proposes source and the Host performs
evaluation. The development-only `workspace-repl` profile makes the opposite
trade: it gives Codex a project tool backed by direct loopback nREPL and is
refused outside development until workspaces are process-isolated.

## Vision, implementation, reason, limits, and wannabe

### Vision

Codex and a nontechnical collaborator should be able to discuss and directly
program a running product. The live system—not a static requirements document—
is the shared product specification.

### Current implementation

The public profile lets server and browser SCI evaluate complete generated
source in fresh candidate contexts, so it remains a transactional hot swap.
The trusted development profile keeps a standard project nREPL session and
namespace alive across turns. Codex changes the running server and browser
before it returns complete source; the Host then validates that materialization
and creates the durable checkpoint. The distinction is explicit in
configuration and evidence rather than hidden behind the word “REPL.”

### Implementation reason

The hackathon host is public, shares one JVM across projects, and spends the
owner's OAuth capacity. In-process arbitrary shell, filesystem, dependencies,
or nREPL would collapse the boundary between a generated prototype and PPP
itself. Capability-limited SCI made a safe, testable public proof possible.

### Current limits

Form-level containment requires the Kernel to virtualize common application
effects. It cannot offer the same freedom as a normal development environment
without continually adding capabilities. This optimizes shared-process safety
and density over unrestricted prototyping.

### Wannabe architecture

Each workspace becomes a disposable execution capsule with real source, shell,
dependencies, database, server nREPL, and browser CLJS REPL. Codex receives
broad authority inside that capsule and can work REPL-first. The Control Plane
protects identity, credentials, routing, quotas, snapshots, the host, and other
workspaces. At checkpoint time PPP reconciles live definitions into source,
tests, data, and history for restart and developer handoff.

## The long-term view

The hackathon uses one local workspace, a shared-password gate, per-session
SQLite, and in-process staged SCI. The architecture is intended to support two
later forms built around Workspace Capsules:

1. A hosted, Figma-like SaaS where a signed-in person or team owns private workspaces.
2. A self-hosted application where an organization controls the runtime, data, identity, and AI provider.

Both forms keep the same promise:

> Product conversations become running software, and running software remains an inspectable engineering artifact.

A further public form could behave like a programmable whiteboard: anyone may
fork an accepted checkpoint, express an alternative through conversation, run
it, and compare observable outcomes before a direction is adopted. Here
“decentralized planning” means that executable alternatives need not pass
through one spec author or one developer. It is not a blockchain claim. Public
identity, moderation, branch/merge semantics, isolation, attribution, and cost
controls are prerequisites, so this remains a long-term direction rather than
a hackathon promise.
