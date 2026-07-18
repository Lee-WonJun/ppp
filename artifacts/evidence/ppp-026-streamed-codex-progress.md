# PPP-026 Streamed Codex Progress

Date: 2026-07-18
Status: passed

## Outcome

The conversation now advances its one-line `Generating` detail from actual
Codex JSONL lifecycle events before the provider process exits. The Kernel
selects every visible sentence from a fixed allowlist using only event type,
item type, and lifecycle state. Provider-authored event text is discarded.

## Real OAuth browser observation

A fresh project on the stable `http://localhost:8787` surface used the real
Codex provider with ChatGPT OAuth. A normal reply turn visibly advanced through
the following states before the final answer appeared:

```text
Generating... · Thinking through your request
Generating... · Understanding the outcome you described
Generating... · The proposal is ready to check
```

The first event-derived detail appeared while the provider process was still
running. The final reply completed normally. Browser inspection reported no
console errors and no failed HTTP requests. A second real turn reproduced the
event-derived `Understanding the outcome you described` state.

No session identifier, prompt, response, raw event, provider reasoning, source,
path, command, token, model name, credential, or diagnostic is retained in this
evidence record.

## Automated verification

- `bb verify`: passed.
- JVM: 186 tests, 1,336 assertions, zero failures.
- CLJS: 29 tests, 132 assertions, zero failures.
- Chromium: 25 passed paths and one intentional skip.
- Production access/restart: passed before and after JVM restart.
- Docker smoke: linux/amd64, non-root, read-only root, persistent Codex home,
  backup/restore rollback passed.
- Secret scan: 201 candidate files inspected, passed.
- Provider stream tests prove supported progress is observable before process
  exit, invalid/oversized JSONL behavior remains bounded, and provider-authored
  fields are never copied.
- Property tests run 1,000 generated provider events and 1,000 arbitrary
  protocol details with fixed seeds.
- Coordinator integration proves details reach only the requesting tab and are
  absent from history.
- Compiled-browser tests prove allowlisted details appear in the existing
  one-line status while untrusted detail falls back to safe phase copy.
- The paired stable browser bundles promoted to port `8787` were:
  - host `app.js`: `4449d3a49ad547bf21c1376d45035215d4ed36435a956260344f452539627c03`
  - sandbox `frame.js`: `2b52ab3b7225cbd6080beefc9bbf9a6ea7c322cf46418f908ed0e8e3cd976bac`

## Security boundary

Visible progress is metadata-derived, not a chain-of-thought transcript. Raw
reasoning summaries, agent-message text, JSONL, generated source, commands,
paths, models, token usage, and diagnostics never enter the WebSocket payload.
Duplicate details are suppressed in the provider and progress is volatile.
