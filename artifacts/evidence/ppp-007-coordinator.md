# PPP-007 Atomic Coordinator Evidence

Date: 2026-07-15

## Executable evidence

- `ppp.access-test/turn-route-returns-an-asynchronous-job` proves the authenticated mutation route returns HTTP 202 with a job ID.
- `ppp.websocket-test` covers exact requester identity, follower rejection, stale version rejection, disconnect, timeout, duplicate/late ACK, requester activation, and follower resync.
- `ppp.coordinator-test` covers reply/clarify immutability, source rejection, missing requester, stale turns, three monotonic commits, SQLite/server/source agreement, and short locked action invocation.
- `ppp.property.coordinator-test` passed three 1,000-case properties:
  - PBT-01 seed `7001`: accepted changes/restores are unique, contiguous, monotonic versions.
  - PBT-02 seed `7002`: generated source/SQL rejections preserve manifest, source, and logical SQLite hashes.
  - PBT-08 seed `7008`: validly encoded but mismatched request/session/tab/version ACKs never authorize a stage.
- Playwright `requesting tab commits while follower tabs resync without refresh` passed in 6.3 seconds against the browser/server protocol.

## Recorded runs

```text
clojure -M:test --focus ppp.access-test --focus ppp.websocket-test
14 tests, 51 assertions, 0 failures.

clojure -M:test --focus ppp.runtime.server-test --focus ppp.coordinator-test
19 tests, 77 assertions, 0 failures.

clojure -M:test --focus ppp.property.coordinator-test
3 tests, 3 assertions, 0 failures.

npx playwright test e2e/coordinator.spec.mjs --grep "requesting tab commits"
1 passed (6.3s)
```

## Runtime invariants checked

- The provider queue returns before generation work and runs FIFO outside the HTTP request.
- Source, SQLite migration, server SCI, and hidden browser render are staged before commit.
- The exact transaction/request/tab/base/target tuple is the only positive browser vote.
- The active base version is checked again while the session commit lock is held.
- Commit materializes the journaled source/database pair before history/checkpoint activation.
- Requester receives `runtime/activate`; followers receive the complete current bundle through `runtime/resync`.
- Any pre-commit rejection leaves the current product hashes unchanged.
