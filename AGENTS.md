# Project Working Agreement

This file applies to every human or AI contributor in this repository.

## Mission

Build Programmable Programming Page as a working, documented, self-hostable live full-stack product workspace. Do not substitute a mockup, scripted demo, visual-only builder, or narrower client-only implementation for the approved product.

## Source of truth

Read in this order before changing behavior:

1. `docs/PRD.md`
2. `docs/SPEC.md`
3. `docs/SECURITY.md`
4. `DESIGN.md`
5. `docs/RUBRIC.md`
6. the active `tickets/PPP-*.md`
7. `docs/TRACEABILITY.md`

If documents conflict, the earlier item wins. Fix the lower document in the same change. Never silently weaken an upper-level requirement to fit existing code or tests.

## Ticket workflow

All implementation work must belong to one ticket.

Allowed status values:

```text
todo
in_progress
done
blocked
```

Before implementation:

- confirm dependencies are `done`;
- set exactly one implementation ticket to `in_progress`;
- read its Outcome, Scope, Acceptance Criteria, Domain/PBT Tests, Evidence, and Out of Scope;
- confirm all mapped PRD requirements remain represented.

Before marking `done`:

- satisfy every acceptance checkbox;
- run the ticket's named tests;
- add or link the named evidence;
- update `docs/TRACEABILITY.md` completion ledger;
- inspect the actual runtime or package when the requirement is broader than unit tests.

Code existing in the tree is not proof of completion by itself.

## Architecture boundaries

Immutable kernel code owns:

- access, sessions, provider process, capability policy;
- history, checkpoint, journal, and recovery;
- WebSocket stage/ACK rules;
- Safe Mode and the recovery handle;
- credentials, quotas, and logs.

Generated runtime code may affect only:

- client routes/components/sidebar/CSS;
- shared pure domain functions;
- registered server actions and business rules;
- host-approved migrations;
- domain/property tests;
- restricted public HTTP and named connectors.

Never expose shell, general filesystem, JVM interop, dynamic dependency loading, MCP, skills, host credentials, or a public REPL to generated code.

Generated browser code is different: normal browser and JavaScript interop is allowed only inside the opaque-origin sandbox frame. It must never execute in the authenticated parent window. The parent exposes session actions and recovery controls only through the versioned message bridge.

## Test philosophy

Test:

- domain invariants and business rules;
- public API and capability contracts;
- security boundaries and negative paths;
- transaction, checkpoint, restore, and crash properties;
- semantic user outcomes with real SQLite persistence.

Do not test:

- exact prose or punctuation;
- CSS class names or DOM nesting;
- private function call order;
- exact progress-event count;
- incidental spacing or pixel snapshots in CI.

Use test.check for the named PBT properties and retain failing seeds. Browser tests select semantic roles and verify state/data. Visual screenshots are manual review evidence, not brittle CI snapshots.

## Verification

Fast checks should run during a ticket. The release contract is:

```bash
bb verify
```

It must make no live OAuth/model calls. Live provider evaluation is separate and explicit:

```bash
bb eval-live
bb eval-evolution
```

`bb eval-evolution` is also live and explicit; it must never run from
`bb verify` or CI.

Never claim Docker, browser, restore, crash recovery, or security completion from a narrower namespace-load or unit-test check.

### User-reproduction precedence

- A repeatable owner report outranks a narrower synthetic pass. When they conflict, the product remains broken until the reported path passes.
- Do not attribute a failure to cache, tabs, WSL, SSH, or browser state without captured evidence from that exact boundary.
- A compiler pass, unit test, or one warm browser run is never evidence for a cold new-session startup claim.
- Before declaring a browser-runtime incident fixed, run the original semantic path in three fresh browser contexts with fresh sessions. At least one run must delay the sandbox-frame bundle beyond the former five-second failure window.
- Browser-platform acceptance must cover a timer-driven update, keyboard input, Canvas drawing, WebAssembly availability, opaque-parent isolation, a server action backed by SQLite, and Safe Mode recovery.

### Stable development surface

- Port `8787` is the owner-facing stable development surface. Do not expose partially compiled host/frame pairs there.
- Build and investigate browser changes in a temporary repository copy on a separate port. Promote both browser bundles together only after focused gates pass.
- Because the development server reads `resources/public` directly, compiling in the working tree mutates what a refreshed `8787` client receives even when the JVM process is unchanged.
- Until the first public release, breaking runtime-contract changes reset all development sessions instead of adding compatibility branches or incrementing `capability-version`. Keep the field fixed at `1` while it remains in the manifest schema.
- Run `bb reset-dev-sessions` after promoting a breaking pre-release runtime change, then restart any running development JVM so its in-memory runtime registry is empty too. Preserve `data/kernel` and OAuth state.

### Failure observability

- Preserve the deepest bounded non-secret failure reason across frame, host, WebSocket, coordinator, provider repair, history, and user response boundaries.
- Distinguish frame loading, source evaluation, registration, initial render, bridge transport, action execution, and active callback failures.
- A generic “not applied” sentence without a stage-specific reason is a release-blocking defect.
- Error propagation tests assert semantic reason categories, not exact punctuation or incidental implementation wording.

## Data and secrets

- Never commit `.env`, `auth.json`, access codes, cookies, connector values, prompts, session databases, or provider JSONL.
- Never print prompt/source/secret/process environment in application logs.
- Tests use temporary session roots and the fake provider.
- Do not inspect or copy the owner's OAuth file except for non-secret readiness and file-mode checks explicitly required by the ticket.
- Generated source fixtures must contain obvious fake values only.
- Commit only sanitized bounded evaluation reports. Raw browser observations,
  provider JSONL, session identifiers/data, and transient Playwright output are
  local evidence and must remain ignored.

## Filesystem discipline

- Preserve unrelated user changes.
- Generated session paths must parse UUIDs and remain below the configured root.
- Do not follow symlinks in session operations or quota scans.
- Do not edit committed migration fixtures in place; add a new migration.
- Keep capability definitions in the single catalog, then derive evaluator exposure and provider documentation from it.
- During pre-release development, delete only `data/workspaces/local/sessions/*` after a breaking runtime-contract change. Preserve `data/kernel`, OAuth state, and provider assets.

## Deployment boundary

Local build, Docker build, and packaged smoke are authorized by the project goal. External actions are not.

Do not perform any of these without explicit owner approval:

- push to a remote;
- publish an image;
- create a pull request;
- deploy to a VPS or Coolify;
- upload a video;
- submit to Devpost;
- rotate or create external credentials.

## Product language

User-visible UI must not mention code, files, Git, models, tokens, skills, MCP, SCI, REPL, eval, WebSocket, or ACK. Translate internal failures into the outcome language defined in `DESIGN.md` while retaining stable internal error codes.
