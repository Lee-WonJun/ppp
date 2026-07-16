# ADR-001: Sandboxed, fully programmable client runtime

Status: accepted
Date: 2026-07-16

## Context

The first browser runtime evaluated generated CLJS in the authenticated parent window and relied on SCI symbol denial plus ShadowRoots. That protected the host only by removing ordinary frontend abilities. A timer exposed the architectural flaw: adding `setInterval`, keyboard, Canvas, WebGL, audio, observers, and every future browser feature one capability at a time makes the product a constrained builder rather than a programmable page.

ShadowRoot is style and DOM encapsulation, not a security boundary. Broad JavaScript interop in that same window could reach the parent DOM, in-memory CSRF value, same-origin APIs, and recovery UI.

## Decision

The immutable parent window owns access, session selection, provider jobs, WebSocket coordination, commit ACK, Safe Mode, and the recovery handle. Generated canvas and sidebar source runs in a separate iframe with `sandbox` excluding `allow-same-origin`.

The frame may use normal browser APIs and JavaScript interop within its own document. It receives no cookie, CSRF token, parent object, or direct authenticated API access. A versioned `postMessage` bridge carries only:

- staged source and activation commands;
- bounded serializable UI state;
- generated server action requests and results;
- sidebar conversation events and view model;
- bounded failure diagnostics.

The requesting tab ACKs only after a fresh hidden frame evaluates and paints both generated surfaces. Activation reveals that exact frame and destroys the previous frame after handoff. Rejection destroys only the staged frame.

## Consequences

- Product features no longer wait for a new host capability merely because they use a normal browser API.
- Destroying a frame is a reliable cleanup boundary for timers, listeners, workers, and DOM mutations.
- State that must survive replacement must remain structured-clone-safe; arbitrary JS objects can remain local but are intentionally not promoted across versions.
- Authenticated server work still crosses the validated bridge, so generated code cannot silently inherit parent credentials.
- A future hosted SaaS can add per-workspace frame origins and network-permission policy without changing the programming model.
