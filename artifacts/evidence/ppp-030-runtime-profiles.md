# PPP-030 Runtime Profile Evidence

Date: 2026-07-20 KST

## Decision

PPP has one product vision and two execution profiles:

| Profile | Purpose | REPL model | Isolation boundary |
|---|---|---|---|
| Shared Public POC | Current judge deployment using owner OAuth | Fresh persistent-per-version SCI contexts; Host-controlled staged evaluation | Individual generated forms and typed session capabilities inside one JVM |
| Workspace Capsule | Intended self-hosted/hosted product | Codex-operated server nREPL plus browser CLJS REPL, shell, source, dependencies, and processes | Disposable per-workspace container, hardened sandbox, or microVM |

The current implementation is legitimately REPL-derived: SCI reads and
evaluates source, the active context retains evaluated functions, UI/actions
make results observable, and another conversation repeats the loop. It is not
a direct current nREPL connection.

## Implementation reason

The public POC shares one JVM and consumes the owner's Codex OAuth capacity.
Its restrictive SCI boundary protects the Kernel, credentials, and other
projects. Those restrictions are a deployment profile, not the product's
maximum creative authority.

## Target contract

The Workspace Capsule permits broad authority only inside the workspace:
source, shell, filesystem, dependencies, database, server nREPL, browser CLJS
REPL, and application processes. Control Plane authority, host resources,
container orchestration, cloud metadata, credentials, and other workspaces
remain inaccessible.

REPL-first experiments may be ephemeral. Accepted checkpoints reconcile the
successful runtime to source, locked dependencies, tests, data, and history so
restart and developer handoff remain reproducible.

## Non-change

PPP-030 changes positioning and the adopted target architecture only. It does
not add nREPL, containers, dependencies, or new generated authority to the
deployed hackathon runtime, and it does not weaken any current release or
security gate.

## Public project synchronization

The guarded Devpost update inserted the same five-part explanation after the
existing lineage paragraph and produced project version `6`. A post-update read
confirmed staged-REPL terminology, the owner-OAuth implementation reason, the
Wannabe Architecture, and REPL-first source reconciliation. Project state
remains `published`, while the OpenAI Build Week `submitted_at` and video URL
remain empty.

## Verification

- Cross-document search found no remaining claim that workspace-local shell,
  filesystem, processes, or dependencies are permanently outside the product;
  current-profile and permanent Control Plane boundaries are distinguished.
- Cross-document search found no claim that current Codex directly connects to
  nREPL.
- Every ticket evidence and source-of-truth document named by PPP-030 exists.
- `bb lint`: zero errors and zero warnings.
- `bb format-check`: all source files formatted correctly.
- `git diff --check`: clean.
