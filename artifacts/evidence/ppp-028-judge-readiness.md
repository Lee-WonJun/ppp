# PPP-028 Judge Readiness Evidence

Date: 2026-07-20 KST

This is a bounded, non-secret record. It contains no shared password, cookie,
OAuth material, provider JSONL, prompt, generated session source, or runtime
session identifier.

## Live OpenAI Build Week review

The authoritative Devpost criteria were reviewed on 2026-07-20:

- Technological Implementation
- Design
- Potential Impact
- Quality of Idea

The event remains open for submissions until 2026-07-22 00:00 UTC
(2026-07-22 09:00 KST). The host asks entrants to lead with the problem,
provide a public video under three minutes with audio explaining Codex and
GPT-5.6, document Codex acceleration and key decisions, provide a majority-build
`/feedback` session ID, and give judges a way to test developer tools without
rebuilding.

## Project state before this ticket

Devpost project `1331761` existed, but its live project fields returned an empty
tagline, empty description, no video URL, and no hackathon submission. That
state could not win regardless of repository quality.

## Reversible project update

The public project copy was updated to version `3` with:

- the established product name and tagline;
- a problem-first, owner-derived description;
- an explicit explanation of Codex and GPT-5.6;
- the repository and hosted demo links;
- the verified implementation technology list.

The post-update read returned:

- project state: `published`;
- tagline and description: non-empty;
- OpenAI Build Week `submitted_at`: empty;
- video URL: empty.

No video was uploaded, no private credential was entered, and no hackathon
submission was performed.

## Feedback-session improvement

The required feedback session remains the majority-build task:

```text
019f644a-b625-7a33-88f4-1ea260c3fdaa
```

It was renamed to `PPP — OpenAI Build Week build, verification, and judge
feedback`. `docs/CODEX_SESSION.md` now gives judges a ninety-second guide to the
owner decisions, Codex contributions, real-browser failures, architectural
repairs, verification commands, and honest limits preserved in that task.

## Risk-adjusted assessment

The internal release score remains 97/100 because it measures verified
requirements. The separate first-impression judging assessment is 91/100:

```text
Technical implementation  25/25
Design                    22/25
Potential impact          20/25
Quality of idea           24/25
```

This means award-competitive evidence, not a probability of winning. No
entrant-pool or judge-ranking data exists. The main unearned impact evidence is
independent product-manager/designer validation; it is disclosed rather than
invented.

## Remaining owner-only blockers

1. Upload the verified 168.74-second English-narrated, English-subtitled video
   to a public or unlisted YouTube/Vimeo URL.
2. Add that URL to the project.
3. Put the shared password only in Devpost's private judge instructions.
4. Provide submitter type, country, Work & Productivity, repository URL,
   feedback session ID, and developer-tool test instructions.
5. Explicitly approve and perform the final hackathon submission before the
   deadline.

## Local verification

- `bb lint`: zero errors and zero warnings.
- `bb format-check`: all source files formatted correctly.
- Judge-document reference existence check: all named files present.
- `git diff --cached --check`: clean.
- `bb secret-scan`: 206 staged project files inspected, pass.

No product source or runtime contract changed, so the already recorded full
`bb verify` result remains the relevant product gate; this ticket adds no
substitute behavioral claim from its narrower documentation checks.
