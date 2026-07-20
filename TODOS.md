# Adopted Long-Term Work

These are accepted directions, not hackathon promises. Each item includes the condition that should trigger it and the prerequisites that prevent premature implementation.

## Workspace Capsule REPL runtime

### Why

PPP's intended creative model is not an ever-growing in-process capability
catalog. Codex should be able to use a real project filesystem, shell,
dependency manager, Clojure nREPL, and browser CLJS REPL as freely as a
developer, while product managers and designers remain in the browser
conversation.

The hackathon cannot safely do this because its public judge deployment shares
one JVM and the owner's Codex OAuth capacity. The current staged SCI runtime is
the Shared Public POC Profile, not the maximum product vision.

### Target shape

```text
Control Plane
  access / workspaces / provider / routing / quota / snapshots
                         |
                         v
Workspace Capsule
  source / shell / dependencies / database / nREPL / CLJS REPL
                         |
                         v
Isolated product origin
```

Codex may experiment REPL-first inside the capsule. A checkpoint becomes
shareable only after live definitions reconcile to source, tests, data, and
history. A broken runtime may be restarted from the last accepted snapshot
without risking the Control Plane or another workspace.

### Trigger

Begin when either:

- the self-host profile needs dependencies or processes outside the curated
  SCI runtime; or
- hosted identity is ready to assign one isolated execution lifecycle and
  provider budget to each workspace.

### Prerequisites

- Workspace image/build policy and lifecycle supervisor.
- Rootless execution with no host/container-runtime socket or host mounts.
- CPU, memory, process, disk, and wall-clock quotas with forced termination.
- Default-deny host, metadata, and cross-workspace networking; scoped egress
  and connector proxy where required.
- Separate product origin and no Control Plane cookie or object access.
- Internal-only nREPL transport, preferably a Unix socket; never a public raw
  evaluator endpoint.
- Server nREPL and browser CLJS REPL session routing and health recovery.
- Source/REPL reconciliation and reproducible dependency lockfiles.
- Filesystem, database, and runtime snapshot/restore consistency.
- Hosted multi-tenant decision between gVisor, Kata, or microVM isolation;
  plain Docker is acceptable only for the trusted self-host profile after its
  own threat review.
- Migration from the current per-session SCI source/history format.

### What remains outside every capsule

- Control Plane code and database.
- Owner, provider, billing, and connector credentials except narrowly brokered
  scoped calls.
- Other workspaces and their data.
- Host filesystem, container runtime, cloud metadata, and orchestration APIs.

This target replaces form-level capability enumeration with environment-level
containment. It does not remove checkpoint, history, browser isolation,
provider, quota, or recovery responsibilities.

## Programmable sandbox resource coverage

### Rule

The generated product should be able to build any ordinary product behavior
whose effects remain inside session-owned resources. “That category is not
supported” is not a design answer. Either the behavior composes from an
existing browser/data/identity/network capability, or the missing virtual
resource is recorded here and implemented behind a typed Kernel boundary.

In the Shared Public POC Profile, raw shell, filesystem, JVM/process control,
and dependency installation are intentionally unavailable because they would
be host authority. PPP credentials, Kernel data, host resources, and
cross-session access remain permanent sandbox escapes in every profile.
Workspace-local shell, filesystem, processes, and dependencies become ordinary
tools only after the Workspace Capsule provides the outer isolation boundary.

### Coverage ledger

| Resource/effect | Current state | Next complete slice |
|---|---|---|
| Browser UI, timers, keyboard, Canvas, media, workers, WASM | available in opaque frame | keep browser conformance E2E current |
| Relational data, transactions, schema additions | available through SQLite | add bounded destructive/schema-copy migrations when a real evolution case needs them |
| Product accounts and authenticated actions | available through typed identity capabilities | extend with configured email, social OAuth, or recovery connectors only when a product case needs them |
| Product profiles, roles, ownership, moderation | compose from auth user ID plus generated tables | add reusable generated examples, not new Kernel authority |
| Public outbound APIs | available through restricted HTTPS | add streaming only when a product needs it |
| Private APIs, email, SMS, payments, social OAuth | possible through developer-owned named connectors | add consent/callback and user-managed secret work only with hosted identity |
| Durable binary uploads and generated assets | available through bounded SQLite blob resource | no remaining sandbox gap |
| Multi-client product events | available through post-commit session/runtime events | durable reconstruction comes from SQLite by design; no event replay gap |
| Background and scheduled work | available through durable named jobs, leases, retry, idempotency, cancellation, and restore policy | no remaining sandbox gap |
| Inbound public routes and webhooks | available through capability-named routes, rate/body limits, and optional named HMAC verifier | no remaining sandbox gap |
| Full-text and vector search | available through session FTS5 and bounded caller-supplied vectors | no remaining sandbox gap |
| Server-side compute | Shared Public POC: pure SCI plus restricted HTTP/connectors; browser Worker/WASM for arbitrary client compute | Workspace Capsule: ordinary workspace-local processes; native host extensions remain outside every capsule |
| Runtime dependencies | Shared Public POC: browser platform and curated server capabilities | Workspace Capsule: workspace-local locked dependencies; mutating the Control Plane or host classpath remains forbidden |

### Acceptance rule for new gaps

Each new resource ticket must prove ownership, quota, transaction/checkpoint
semantics, failure observability, provider documentation, fake-provider flow,
and one real browser outcome. A connector-shaped workaround does not count when
the product needs a durable session-owned resource.

PPP-021 closes every ordinary session-owned resource gap required by the Shared
Public POC Profile. New hackathon product requests should first compose these
primitives. The Workspace Capsule is a different execution profile, not another
typed resource to add to the current Kernel.

## Hosted SaaS identity and workspaces

### Why

The intended hosted product should feel like Figma or Notion: sign in, open a private workspace, invite collaborators later, and never operate infrastructure. The hackathon shared password and fixed `local` workspace cannot provide per-person authorization, revocation, isolation, ownership, or billing.

### Trigger

Begin after the single-user demo has repeatable demand from at least three external product/design users who complete a working product flow without developer assistance.

### Prerequisites

- Identity provider selection and account recovery.
- Workspace, membership, role, and session ownership model.
- Tenant-scoped storage encryption and backup/restore.
- Provider credential and cost attribution per workspace.
- Abuse prevention, rate limits, moderation, and legal data lifecycle.
- Evaluator workload isolation beyond in-process SCI.
- Migration plan from format version 1 local sessions.
- Security review replacing the shared-password assumptions in `docs/SECURITY.md`.

### Explicitly not now

Do not add a superficial login screen over shared filesystem access. Tenancy is a data and execution boundary, not only an authentication UI.

## Responses API and custom AI providers

### Why

ChatGPT OAuth through Codex CLI makes the trusted self-host and hackathon affordable, but it is not the right credential or accounting model for a public SaaS. A provider abstraction also allows organizations to choose managed OpenAI, a proxy, or another contract-compatible source generator.

### Trigger

Begin before any public signup or whenever the application must meter usage independently from the owner's ChatGPT account.

### Prerequisites

- Preserve the current normalized provider result and evaluation harness.
- OpenAI service-account/API credential storage and rotation.
- Per-workspace usage, budget, retry, and cancellation accounting.
- Streaming/structured-output implementation with equivalent schema validation.
- Data retention and zero-data-retention decision.
- Model capability/version compatibility matrix.
- Re-run all 24 live scenarios for each provider/model combination.

### Migration rule

Filesystem source and transcript summary remain canonical. A provider thread or response ID stays a disposable conversational cache.

## Runtime SDK extraction

### Why

The fixed host, capability catalog, staging protocol, and source/checkpoint format could eventually embed into products other than PPP. Extracting too early would freeze abstractions around a single example.

### Trigger

Extract only after a second independent embedding case implements the runtime and demonstrates which boundaries are actually shared.

### Prerequisites

- Two working consumers with different domain surfaces.
- Stable protocol and capability-version migration story.
- Clear separation of kernel, browser host, storage adapter, and provider adapter.
- Package security review and public API compatibility policy.
- Independent integration test kit and example application.

### Explicitly not now

Do not move code into a public package only to make the repository appear modular. Module boundaries inside the monolith are sufficient for the MVP.

## Source promotion to Git

### Why

The long-term developer handoff should turn accepted runtime source into a conventional branch or pull request while preserving form structure and history.

### Prerequisites

- Reliable source-file ownership and base repository hash.
- `rewrite-clj` form-aware replacement with comments preserved.
- Conflict detection when base source changes outside PPP.
- Test and security gate before branch creation.
- Human review of commit message and diff.
- Explicit credentials and repository authorization.

### Shape

```text
accepted runtime checkpoint
-> compare against source base
-> structure-aware patch
-> tests
-> user review
-> optional branch/PR
```

Never make live runtime and Git source automatically overwrite each other.

## Real-time collaboration

### Why

Figma-like collaboration is a plausible product direction, but concurrent natural-language changes introduce intent conflicts beyond cursor synchronization.

### Prerequisites

- Hosted identity and workspace roles.
- A collaboration model for conversation, product actions, and change ownership.
- Conflict/merge semantics for concurrent generated source and migrations.
- Presence/cursor transport isolated from commit protocol.
- Audit and moderation for team changes.

CRDTs alone do not solve atomic server/client/data intent conflicts.

## User-managed connectors and secrets

### Why

Real products need authenticated APIs, but exposing secret configuration too early expands the most sensitive trust boundary.

### Prerequisites

- Hosted identity, roles, and audit.
- Encrypted secret store and rotation.
- OAuth consent/callback model.
- Connector review, scopes, egress, quotas, and revocation.
- Clear UI that never sends secret material to model context.

Until then, connectors remain developer-owned aliases in deployment configuration.

## Multi-node availability

### Why

A public hosted product eventually needs evaluator isolation and application redundancy. The single-node filesystem canonical store cannot usefully scale through stateless replicas.

### Prerequisites

- Durable event/object store selection.
- Distributed session commit lease or transaction coordinator.
- Version broadcast and activation acknowledgements across nodes.
- SQLite replacement or a well-defined remote database-per-workspace model.
- Recovery, backup, and failover tests at every commit point.

Do not add Redis broadcast alone and call the system highly available.
