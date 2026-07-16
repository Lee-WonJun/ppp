# Product Design System

Status: source of truth
Last updated: 2026-07-15

## Design read

PPP is a real-time product workspace for nontechnical product teams. It should feel as immediate and unsurprising as opening a Figma file or Notion page, while retaining the quiet confidence of an instrument that can recover from mistakes.

This is dense product UI, not a marketing page. The immutable parent uses native CSS and Reagent. Generated surfaces live together in an isolated full-viewport browser frame and may define their own frontend system.

Design dials:

- Layout variance: 3/10. Predictable workspace geometry builds trust.
- Motion: 3/10. Motion communicates progress and state replacement only.
- Density: 4/10. Daily-product density with generous conversation reading space.

## Experience principles

1. Start with nothing. The blank canvas is the invitation.
2. Keep technical machinery invisible.
3. Let the result, not a confirmation dialog, prove the change.
4. Make progress legible without narrating internal implementation.
5. Keep recovery controls outside the programmable surface.
6. Prefer plain functional language over agent jargon or playful filler.

## Host anatomy

```text
┌────────────────────────── viewport ──────────────────────────┐
│                                                    [handle]  │
│                                                              │
│                    Sandboxed product frame                  │
│                                                              │
│                               ┌────────────────────────────┐ │
│                               │ Generated sidebar         │ │
│                               │ session selector        + │ │
│                               │                            │ │
│                               │ conversation               │ │
│                               │ checkpoints                │ │
│                               │                            │ │
│                               │ composer              Send │ │
│                               └────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

The handle belongs to the immutable parent and remains above the frame. Canvas and sidebar rendering belong to the sandbox frame, so they can collaborate freely without gaining access to recovery controls.

## Blank state

- Background is literal `#ffffff`.
- Only the 38px circular handle is visible.
- The handle sits 18px from the top and right on desktop, 12px on mobile.
- It has an accessible label, visible keyboard focus, and a simple two-line menu mark built with CSS.
- No logo, greeting, empty-state copy, template grid, or decorative illustration appears on the canvas.

## Sidebar

### Default geometry

- Desktop width: 420px.
- Default placement: full-height right overlay.
- Mobile: full viewport width and `100dvh` height.
- The sidebar may become floating after a generated change, but it must remain keyboard reachable.

### Structure

Header:

- Session selector fills available width.
- `+` creates a blank persistent session.
- Rename and delete controls are excluded.

Conversation:

- User messages receive a quiet warm-neutral fill.
- Assistant messages sit directly on the surface.
- Checkpoints use outcome titles, never version hashes or filenames.
- Long content scrolls independently from the composer.

Composer:

- One multiline input handles discussion and changes.
- Enter sends a nonblank idle draft; Shift+Enter inserts a newline.
- IME composition confirmation never sends a turn.
- `Send` is the only primary control.
- The button shows `Working` while the current turn is queued or active.
- The draft remains intact after a recoverable transport failure.

## Visual tokens

```css
--canvas: #ffffff;
--ink: #171714;
--muted: #6e6d65;
--line: #deddd6;
--surface: #f8f7f2;
--surface-strong: #eceae2;
--accent: #285a43;
--danger: #a33a32;
--radius-control: 9px;
--radius-panel: 14px;
--radius-floating: 18px;
```

Rules:

- The hackathon workspace is intentionally light-only because the product requirement is a literal white canvas.
- `#285a43` is the single interactive accent.
- Red is reserved for actual failure or destructive risk.
- No gradients, neon glows, decorative status dots, or card grids in the host.
- Generated products may define their own visual language inside the sandbox frame.

## Typography

- Host UI: `IBM Plex Sans`, `Aptos`, `Segoe UI`, sans-serif fallback.
- Default size: 14-16px.
- Conversation line height: 1.5.
- Technical monospaced text is not visible in the user experience.
- Headings use sentence case.
- Labels state an action or object directly.

## Motion

Allowed motion communicates one of three things:

- the sidebar opening or closing;
- a progress state changing;
- a successfully staged surface becoming active.

Use transform and opacity transitions between 120ms and 180ms. Do not animate layout dimensions. Honor `prefers-reduced-motion`; in reduced mode, state changes are immediate.

## Product language

Visible words:

```text
Generating
Validating
Applying
Applied
Working
Send
Try again
Safe Mode
Restore
```

Do not expose:

```text
CLJ
CLJS
SCI
REPL
eval
manifest
diff
Git
model
token
skill
MCP
WebSocket
ACK
```

Error copy describes the user outcome:

- Good: `The new version could not be applied. Your current product is unchanged.`
- Bad: `SCI eval failed in runtime.client at line 17.`

## Progress behavior

Progress is a single current phrase, not a growing event log.

```text
Generating -> Validating -> Applying -> Applied
```

- `Applied` may remain attached to the assistant response as a quiet completion note.
- The number and timing of internal events are not part of the UI contract.
- If a stage fails, the assistant response explains that the current product was preserved.

## Checkpoints

- Title describes the outcome, for example `Judge votes count for three points`.
- Timestamp may appear in locale-friendly form.
- A restore control uses the word `Restore`.
- Runtime version may be present in an accessible detail view for debugging, but is not the primary label.
- Restoring shows the same progress vocabulary as a change.

## Safe Mode

Safe Mode is immutable host UI.

Entry:

- Click and hold the host handle to expose recovery actions, or
- press `Ctrl+Alt+Shift+P`.

Safe Mode behavior:

- Unmount the generated sidebar.
- Render the bundled last-success fallback sidebar.
- Keep the current generated canvas visible when safe.
- Offer session switching, checkpoint restore, and retry.
- Never execute generated sidebar code while Safe Mode remains active.

## Responsive behavior

At widths below 640px:

- Sidebar fills the viewport.
- Header controls remain on one usable row unless translated text requires wrapping.
- Composer stays reachable above the virtual keyboard using dynamic viewport units.
- The host handle remains above generated surfaces.

The generated canvas owns its own responsive behavior and must pass hidden staging at the requesting viewport before commit.

## Accessibility

- All controls have programmatic names.
- Keyboard focus is always visible at 3:1 or better.
- Body and control text meet WCAG AA contrast.
- Conversation updates use a polite live region.
- Progress is conveyed by text, not color alone.
- The host handle is a real button.
- Frame focus order follows visual order and the immutable handle remains reachable.
- Safe Mode is fully keyboard operable.
- Browser E2E selects semantic roles and labels, not CSS classes or DOM nesting.

## States that must exist

- inaccessible: access-code entry guidance;
- blank: white canvas and closed sidebar;
- open empty session;
- queued and each progress phase;
- successful reply, clarification, change, and restore;
- recoverable provider failure;
- rejected generated change with prior runtime preserved;
- disconnected and resynchronizing browser;
- storage quota reached while history remains readable;
- Safe Mode with last successful sidebar;
- no Codex OAuth/model readiness.

## Manual visual evidence

CI does not gate on pixel snapshots. Before release, capture and review:

1. blank desktop at 1440x900;
2. default sidebar desktop;
3. floating generated sidebar;
4. gallery desktop and 390px mobile;
5. progress and failure states;
6. checkpoint restore;
7. Safe Mode;
8. keyboard focus sequence.
