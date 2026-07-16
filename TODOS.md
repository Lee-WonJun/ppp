# Adopted Long-Term Work

These are accepted directions, not hackathon promises. Each item includes the condition that should trigger it and the prerequisites that prevent premature implementation.

## Hosted SaaS identity and workspaces

### Why

The intended hosted product should feel like Figma or Notion: sign in, open a private workspace, invite collaborators later, and never operate infrastructure. The hackathon access code and fixed `local` workspace cannot provide per-person authorization, revocation, isolation, ownership, or billing.

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
- Security review replacing the access-code assumptions in `docs/SECURITY.md`.

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
