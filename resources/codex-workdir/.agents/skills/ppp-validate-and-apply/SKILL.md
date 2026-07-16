---
name: ppp-validate-and-apply
description: Generate or repair complete Clojure, ClojureScript, CLJC, CSS, and SQLite runtime changes for Programmable Programming Page. Use for every PPP change result and every host rejection that asks for a corrected attempt before staging or activation.
---

# PPP Validate and Apply

Act as the source generator in a host-controlled validate, stage, and apply loop. The host owns execution and is the only authority that can activate a change.

## Produce a change

1. Preserve behavior outside the user's request.
   - First identify the smallest affected runtime surface from the requested
     outcome and the current source tree.
   - Visual styling, layout, navigation, local UI state, timers, keyboard input,
     Canvas, WebAssembly, and browser games belong only in `src/client/**` and
     `styles/**`. Do not rewrite server, shared, test, or migration files for a
     client-only outcome.
   - Persistence, registered server actions, or domain/business-rule changes
     must update the relevant server/shared source, rollback-only domain tests,
     and migrations when schema is needed.
   - A later request evolves the complete current product. Preserve unrelated
     features and stored data unless the user explicitly asks to remove them.
     Replacing a browser game must not discard an independent ranking feature.
2. Return complete file contents, never patches or partial forms.
3. Check every Clojure-family file before returning it:
   - Balance delimiters and strings.
   - Declare the required entrypoint namespace.
   - Use CLJ forms only in server files, CLJS forms only in client files, and portable forms in CLJC files.
   - Resolve every referenced symbol from the supplied capability catalog or the same source tree.
   - Keep side effects inside registered actions or UI event handlers.
4. Check runtime compatibility:
   - Register exactly the required page, sidebar, and server actions.
   - Treat action input as the submitted payload map directly.
   - Use only static parameterized SQL through the supplied wrappers.
   - Preserve the host-owned state and recovery sidebar contract.
   - Treat `api/page-state` as the host-owned reactive atom value. Read it with `@api/page-state` and update it with `swap!` or `reset!`; never call it as a function.
   - Keep every value whose change must redraw the UI or survive runtime replacement in `api/page-state`. Local atoms are allowed only for disposable implementation details.
   - For local interactions such as games, navigation, and form drafts, update `api/page-state` directly. For server actions whose response changes the UI, use the three-argument `api/action!` and render from its target key.
   - Trace every interactive mutation end to end before returning it: rendered
     button or form handler -> three-argument `api/action!` -> registered server
     action -> complete response view model -> the exact target key read by the
     renderer. The initial list action and every create/update/delete action
     for the same view must return the same response shape. For example, if
     the page renders `(:projects (:gallery/data @api/page-state))`, both
     `:projects/list` and `:votes/create` must return `{:projects [...]}` and
     the vote handler must call `(api/action! :votes/create payload
     :gallery/data)`. Never start an immediate refresh that can race and
     overwrite the mutation response.
   - A visible mutation control is incomplete unless one activation changes
     its visible result without reload and the same result is reconstructed
     from SQLite after reload. Mentally simulate both states for voting,
     submission, and other persisted CRUD before returning the files.
   - Client code runs inside a disposable opaque-origin browser frame. Use normal JavaScript interop and browser APIs for timers, animation frames, keyboard input, Canvas/WebGL, audio, workers, WebAssembly, observers, refs, and other frontend behavior.
   - The authenticated parent DOM, cookies, CSRF state, and recovery handle are outside the frame. Do not access `js/parent` or `js/top`; use `runtime.api/action!` and supplied sidebar callbacks for host operations.
   - Browser listeners and timers may live for the frame lifetime and are cleaned up when the frame is rejected or replaced. `api/start-interval!` remains an optional keyed convenience, not the limit of available browser behavior.
5. Check each migration for valid ordered SQL and never edit an already committed migration.
6. Keep `test/runtime/domain_test.cljc` executable and update it for every
   domain or business-rule change:
   - Use `clojure.test/deftest`, `is`, and `testing`.
   - Use `runtime.test/invoke!` to call registered actions against the staged
     SQLite database. The host runs the tests before commit and always rolls
     their writes back.
   - Assert observable invariants, action response shapes, initial state,
     mutation deltas, and persistence through the corresponding read action.
     Do not assert copy, CSS classes, DOM nesting, private call order, or minor
     layout details.
   - Tests must remain valid after arbitrary legitimate user data changes.
     Create rollback-only fixture rows with distinctive values or compare a
     mutation to a captured baseline. Never assume a seeded row count, an
     existing project's score, or an empty table remains unchanged after the
     feature is used.
   - Voting rules must prove that no votes score 0, one public vote adds
     exactly 1, one judge vote adds exactly 3, and ties use deterministic
     ordering. In a `LEFT JOIN` aggregate, explicitly map a missing joined row
     to zero; a `CASE ... ELSE 1` must never award a synthetic NULL row.
7. Return only the structured result required by the provider schema.

## Repair a rejected attempt

When `PREVIOUS ATTEMPT REJECTED BEFORE COMMIT` is present:

1. Keep the original user outcome unchanged.
2. Treat the feedback code, path, cause code, and validation errors as authoritative.
3. Replace every affected file with a complete corrected version.
4. Re-run the syntax and runtime-compatibility checklist mentally.
5. Return a `change`; do not downgrade the turn to an explanation, reply, or clarification.

The host may request another correction if parsing, policy validation, server SCI staging, temporary SQLite migration, or hidden browser rendering fails. Never claim that a change is applied. The host activates it only after every gate succeeds.

## Boundary

Do not use or request shell, filesystem, MCP, dependency installation, Java interop, or host credentials. Generated server code may use only its named HTTP capabilities. Generated client code may use the browser and JavaScript APIs inside its opaque-origin frame, but must not attempt to reach the authenticated parent. Do not connect to a raw JVM REPL. The host's isolated JVM and browser SCI contexts are the only REPL-like application surfaces for generated code.
