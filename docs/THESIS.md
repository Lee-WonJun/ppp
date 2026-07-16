# Product Thesis

Status: source of truth
Last updated: 2026-07-15

## The claim

The next useful interface for product planning is not a better prompt box around a code editor. It is a running product that can be discussed and changed in place.

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

The resulting artifact is stronger than a document or mockup. It contains executable business rules, a persistent SQLite database, client behavior, server actions, domain tests, and a versioned source tree.

## The handoff contract

PPP does not remove developers. It changes the point at which they enter the process.

- The product manager or designer works with observable outcomes.
- The runtime records complete CLJ, CLJS, CLJC, CSS, SQL, and tests.
- Each accepted change records the prompt, prior source, resulting source, validation, and data checkpoint.
- A developer can read the same source tree and history without reverse-engineering a mockup.
- Future source-promotion tooling may turn accepted runtime work into a branch or pull request, but that automation is outside the hackathon MVP.

## Why a bounded runtime

PPP is not a public nREPL endpoint. It does not give generated code a shell, general filesystem access, Java or JavaScript interop, credentials, arbitrary network access, MCP servers, or skills.

The AI writes real Clojure-family source, but a fixed kernel decides what that source may do. SCI evaluates it with an explicit namespace and symbol catalog. SQL migrations are validated and applied to a staging database. Client code must render in a fresh hidden opaque-origin sandbox frame before the live state commits. Every successful version can be restored together with its source and data.

This boundary preserves the essential property of a REPL, changing a running system, while making it suitable for a product surface shared with non-programmers.

## The long-term view

The hackathon uses one local workspace, an access-code gate, and per-session SQLite. The architecture is intended to support two later forms:

1. A hosted, Figma-like SaaS where a signed-in person or team owns private workspaces.
2. A self-hosted application where an organization controls the runtime, data, identity, and AI provider.

Both forms keep the same promise:

> Product conversations become running software, and running software remains an inspectable engineering artifact.
