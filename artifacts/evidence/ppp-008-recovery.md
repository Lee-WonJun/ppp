# PPP-008 Checkpoint and Recovery Evidence

Date: 2026-07-15

## Executable evidence

- Every created session has a version-zero source/SQLite checkpoint.
- Checkpoint construction uses SQLite online backup, `integrity_check`, migration hashes, logical content hash, gzip archive hash, and atomic directory replacement.
- Restore validates the complete checkpoint before browser staging and creates a new monotonically increasing version/history event.
- The original and later checkpoints remain present, so old and future product states can both be restored.
- Successful restore clears the Codex thread before subsequent generation.
- Startup runs journal recovery before reactivating server runtimes or reporting readiness.

## Property runs

```text
clojure -M:test --focus ppp.property.restore-test +  --focus ppp.property.recovery-test
2 tests, 4 assertions, 0 failures.
```

- PBT-03 seed `8003`, 1,000 cases: arbitrary valid database changes followed by restore reproduce checkpoint source and logical SQLite hash.
- PBT-04 seed `8004`, 1,000 cases: base/target/mixed journal states select deterministic finalize, abandon, or rollback outcomes.

## Matrix and browser evidence

- `ppp.session.store-test` injects target/target, base/base, and mixed manifest/SQLite crash states, runs recovery twice, and proves idempotence.
- Corruption fixtures mutate manifest, source, gzip, and SQLite independently; every case rejects restore and preserves current.
- `ppp.runtime.sqlite-test/concurrent-online-backups-remain-consistent` takes repeated snapshots while 200 writes run.
- `ppp.coordinator-test/restore-rewinds-source-and-data-while-preserving-future-checkpoints` covers past restore, future restore, data rewind, history, registry, and thread reset.
- Playwright's full conversation scenario restores checkpoint 2 as version 4 and checkpoint 3 as version 5 without a page refresh.
