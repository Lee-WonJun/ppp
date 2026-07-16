# PPP-006 browser runtime evidence

Date: 2026-07-15
Build under test: `shadow-cljs release app`

## Executable evidence

- `bb client-test`: 10 tests, 63 assertions, zero failures.
- `bb test`: 50 tests, 307 assertions, zero failures.
- `bb e2e`: 2 Chromium scenarios passed twice consecutively against the release bundle.
- `bb lint && bb format-check`: zero warnings/errors and all source files formatted.

The browser suite proves the following observable outcomes:

1. The version-zero session runtime loads from the filtered client runtime endpoint.
2. Version 1 and version 2 replace the canvas and sidebar without navigation or refresh.
3. Compatible page state survives the replacement.
4. A sidebar that throws during its hidden React commit is rejected; active version 2,
   its page, sidebar, CSS, and state remain visible.
5. `document`, browser globals, direct interop, an `iframe/src` resource escape, and
   CSS browser-network primitives are rejected without issuing the external request.
6. Generated CSS that attempts to hide `.ppp-handle` cannot cross the canvas
   ShadowRoot or its lower stacking context.
7. `Ctrl+Alt+Shift+P` and a 700 ms handle hold both enter immutable Safe Mode.
8. The 390 px sidebar fills the viewport, the handle remains keyboard focusable,
   and reduced-motion CSS removes its normal transition.

The CLJS suite additionally exercises exact capability-catalog parity, stable state,
duplicate and stale versions, 18 browser/Node escape fixtures, unsafe Hiccup tags and
attributes, CSS `@import`/`url()`/`image-set`, an infinite SCI loop, an unbounded lazy
render tree, and preservation of Hiccup child-sequence semantics under release
optimization.

## Manual visual review

- Desktop floating surface: [`ppp-006-floating.png`](ppp-006-floating.png)
- Immutable recovery surface: [`ppp-006-safe-mode.png`](ppp-006-safe-mode.png)
- 390 px viewport: [`ppp-006-mobile.png`](ppp-006-mobile.png)

The screenshots were inspected at original resolution. The generated canvas and
sidebar remain visually independent, the host handle stays above both roots, Safe
Mode remains legible without generated CSS, and the mobile sidebar has no clipped
controls in the exercised state.
