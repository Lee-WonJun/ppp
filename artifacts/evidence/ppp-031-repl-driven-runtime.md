# PPP-031 REPL-driven runtime evidence

Date: 2026-07-20
Status: local development evidence; no deployment or raw provider transcript

## Mechanism proved

- A standard nREPL server binds to loopback and keeps one session and namespace
  for each project.
- Codex receives a generated project-scoped `./ppp-repl` client in an otherwise
  empty job directory.
- Server inspection, incremental form evaluation, action invocation, migration,
  and exact-tab browser evaluation are recorded by the Kernel. Returned files
  alone cannot satisfy a Workspace REPL change.
- Server forms are evaluated as ordinary quoted Clojure forms in the project
  JVM namespace. The live action router retains registered Vars; a subsequent
  `defn` redefinition changes the next action result without restaging source.
- Successful live behavior is independently reconciled through the existing
  source, tests, SQLite, history, and checkpoint transaction.
- A terminal turn restores its pre-turn SQLite backup, rebuilds server behavior
  from the last durable source, and resynchronizes the browser.

## Automated evidence

- The focused nREPL gate covers persistent Vars, redefinition, project
  namespace separation, loopback-only binding, UUID selection, input/output
  bounds, active product inspection, direct Var-backed action replacement, and 1,000
  generated state-retention sequences.
- The server runtime gate covers live action replacement, retained state,
  deterministic live migration naming, dynamic declared SQL, and invocation
  against the migrated SQLite database.
- The browser runtime gate covers active incremental evaluation without reload,
  serializable state retention, exact request-tab/version acknowledgement, and
  rejected evaluation results.

## Real Codex and Chromium evidence

The owner-facing port was not modified. A paired host/frame build ran from an
isolated repository copy on a separate local port with the Workspace REPL
profile and real OAuth Codex provider.

### Running server redefinition

1. Codex inspected the active version-zero server over nREPL.
2. It incrementally registered and invoked a counter action.
3. It evaluated the client form in the open sandbox frame.
4. The new interface appeared while the conversation still showed Working,
   before source staging and checkpoint completion.
5. After reconciliation, the action incremented by the newly defined rule and
   the value survived a page reload through SQLite.

The accepted history sequence contained server inspection, server evaluation,
multiple real action invocations, and client evaluation.

### Live migration and repair

1. Codex applied a notes-table migration to the live project database through
   the project nREPL helper.
2. It evaluated list/create actions and invoked them against that table.
3. An attempted server form referencing an unavailable namespace was rejected.
4. Codex observed the rejection, evaluated a corrected form in the same turn,
   and invoked both invalid and valid product cases.
5. It evaluated the notes interface in the active browser. The interface and
   observed rows appeared before the checkpoint existed.
6. Reconciliation completed as Checkpoint 1 with accepted migration, server
   evaluation, action invocation, and client evaluation operations.
7. A new memo entered through the browser remained visible after an actual top
   page reload. Chromium reported no console errors.

### Failure rollback and performance defect found by dogfooding

- Before the nREPL client fix, new-project initialization consistently waited
  for the ten-second client timeout per evaluation and took about 20.8 seconds.
  The client had consumed the raw session response stream, which ends only when
  a session closes. Using the standard nREPL message completion boundary reduced
  the observed new-project request to roughly 0.35 seconds.
- A real provider turn that exceeded the earlier 120-second limit had already
  changed the live browser and database. The terminal path restored the blank
  durable client and logical SQLite state. Workspace REPL now defaults to 240
  seconds, and the repeated migration scenario completed successfully.

## Scope statement

This evidence proves genuine nREPL-driven authoring in the trusted development
profile: standard nREPL evaluates real JVM Vars used by the running server.
The accepted durable source is still independently checked in PPP's restricted
SCI runtime before checkpointing. The public shared-process judge profile
remains a transactional SCI hot swap. Moving ordinary project
processes, dependencies, and raw runtime authority into a disposable isolated
workspace capsule remains separate long-term work.

## Release and evolution gates

- `bb verify` passed after the direct JVM Var refinement: 196 JVM tests with
  1,409 assertions, 31 CLJS tests with 145 assertions, 25 Chromium paths with
  one intentional skip, two production-style restart checks, amd64 non-root
  read-only Docker smoke, and a 218-file secret scan.
- The dedicated real OAuth Workspace REPL evolution passed six consecutive
  scenarios in one project and one provider thread: dark styling, floating
  sidebar, timer/keyboard Tetris, SQLite ranking persistence, live server rule
  replacement, and client-only Gomoku replacement with one repair.
- The gate exposed and fixed two harness/runtime defects: the evolution test
  still expected an auto-created session before its own create request, and
  repair-attempt nREPL observations were discarded instead of accumulating
  across the same user turn.
- EVOLVE-07 remains a release blocker for this ticket. A generated account
  change exhausted the original three host repair attempts on domain tests.
  After increasing the bounded default to six, the resumed provider asked for
  permission to continue a clear imperative request and produced no commit.
  The active version and SQLite remained unchanged. No 8/8 claim is made.
