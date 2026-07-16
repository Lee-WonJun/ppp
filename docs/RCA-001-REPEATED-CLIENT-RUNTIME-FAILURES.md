# RCA-001: Repeated client runtime failures and repeated owner reports

Status: verified and promoted to the stable development server
Date: 2026-07-16
Owner ticket: `PPP-013`

## Incident

Every newly created session could fall into the immutable recovery view a few seconds after creation:

```text
Your current product is unchanged.
The product view could not be loaded. Your saved session is unchanged.
```

Generated frontend changes also failed behind the same generic rejection, and a Tetris product could not use automatic falling. The owner repeatedly reported that the failure was deterministic, including after deleting sessions, refreshing, and clearing browser state.

## Why the owner had to repeat the same request

The repeated conversation was caused by our debugging process, not unclear reporting.

1. We treated a product-architecture requirement as individual capability requests. `setInterval` and other browser behavior were patched or discussed one feature at a time instead of making generated frontend a normal browser environment.
2. We trusted a warm headless v0 pass over the owner's repeatable cold in-app-browser failure. When evidence conflicted, we incorrectly speculated about cache, tabs, WSL, and SSH.
3. We used narrow gates as completion evidence. Compilation and one browser path did not exercise cold frame startup, activation-only effects, SQLite actions, or follower resync.
4. We discarded the deepest error at multiple boundaries. `client-stage-failed` reached the UI while `Unable to resolve symbol: js/parent` remained hidden, so unrelated failures looked identical.
5. We compiled browser assets in the same tree served by port `8787`, allowing the owner to encounter partially integrated host/frame builds.
6. We added pre-release compatibility machinery when deleting development sessions was the correct, cheaper policy.

The correct response to the first deterministic report was to reproduce that exact path, capture the stage, and keep the product classified as broken until it passed.

## Technical root causes

### 1. Frame loading was misclassified as generated render failure

`frame_host.cljs` started one five-second timer immediately after creating the iframe. That timer included HTML fetch, a roughly 6.4 MiB development SCI bundle, script evaluation, handshake, generated source evaluation, and two paints.

Delaying only `/frame-js/frame.js` by 6.5 seconds reproduced the owner's new-session failure with valid version-zero source:

```clojure
{:code :runtime/client-frame-timeout
 :saved-version 0
 :active-version nil}
```

The host now registers the pending stage before attaching the iframe, allows 30 seconds for `:frame/ready`, and starts a separate 10-second render deadline only after readiness.

### 2. SCI did not have the JavaScript root object

The frame collected 1,241 names from `globalThis`, including `parent`, `setInterval`, and `WebAssembly`, but SCI resolves value-position `js/foo` through a class named `js` and then reads `foo`. The map had every leaf and no root.

This produced the concrete hidden failure:

```text
Unable to resolve symbol: js/parent
```

Binding SCI class `js` to the frame's `globalThis` made `js/parent`, `js/window`, `js/setInterval`, and `js/WebAssembly` resolve through one general mechanism. The opaque iframe, not a feature allowlist, remains the authority boundary.

### 3. Activation did not rerun generated page effects

Staging intentionally suppresses authenticated actions. Activation incremented a parent render atom, but the generated child retained the same function and props identity, so Reagent did not invoke the page again. The gallery heading rendered from local source while `ensure-action!` never ran.

Captured state before the fix:

```clojure
{:phase :active
 :ensured #{}
 :pending #{}
 :page-state {}
 :action-http-requests 0}
```

Activation now changes the generated page and sidebar child keys. The active-phase remount invokes `ensure-action!`; the gallery probe receives HTTP 200, six SQLite rows, and six Vote buttons.

### 4. Failure detail was dropped

Frame errors carried a cause chain, but the host, WebSocket rejection, coordinator exception, repair request, and final message preserved only a broad keyword. The bridge now carries at most four strings of 240 characters each. The same bounded reason is available to provider repair and the final plain-language rejection.

Example after the fix:

```text
The generated interface passed source checks, but it could not be drawn in this browser preview.
Your previous product is still running. Reason: Hidden render fixture.
```

### 5. Successful resync retained an obsolete cancellation diagnostic

HTTP bootstrap and WebSocket resync can legitimately race when a tab initially reports an older saved version. Resync cancels the older staged frame, but `client-frame-reset` remained in the debug state even after the replacement activated successfully. Successful activation is now authoritative and clears any obsolete staging-cancellation diagnostic.

## Verification added

- Cold fresh context with `/frame-js/frame.js` delayed 6.5 seconds.
- Fresh-session version-zero activation with no debug error.
- Raw `js/setInterval`, keyboard input, Canvas pixel, WebAssembly, and `js/parent` isolation.
- Replacement state preservation and rejected-frame rollback.
- SQLite-backed server action returning six persisted projects.
- Requesting-tab commit and follower-tab resync.
- Render rejection preserves version and shows a concrete reason.
- Bounded browser rejection details validated at the shared protocol and WebSocket layers.
- Deterministic generated Tetris with automatic falling, arrow-key movement, and
  rejected-change recovery.

## Final verification evidence

- `bb verify`: 120 JVM tests / 684 assertions, 16 CLJS tests / 71 assertions,
  release host and frame builds with zero warnings, eight Playwright paths,
  Docker package smoke, and secret scan all passed.
- Stable `http://localhost:8787`: three fresh browser contexts and fresh sessions
  passed while `/frame-js/frame.js` was delayed by 6.5 seconds each time.
- Real Codex OAuth generated and committed a 20 by 10 Tetris implementation with
  seven pieces, scoring, line clearing, `runtime.api/start-interval!`, and arrow-key
  control. The live browser smoke proved the rendered playfield changed over time.
- `capability-version` remained `1`. Development sessions were deleted instead of
  adding a compatibility branch.

## Permanent working rules

- User reproduction beats conflicting synthetic evidence.
- No environmental blame without evidence from that boundary.
- Port `8787` is stable-only; browser work builds in a temporary copy and verifies on a separate port.
- No browser-runtime completion claim until three fresh-session runs pass, including one cold delayed-frame run.
- No generic rejection without a stage and concrete reason category.
- Before the first public release, breaking runtime changes delete development sessions and keep `capability-version` fixed at `1`.

These rules are normative in `AGENTS.md`; this document records why they exist.
