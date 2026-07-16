# Three-Minute Demo

Status: packaged rehearsal passed 3/3; final recording remains owner-controlled
Target runtime: 175 seconds
Voice: Korean or English
Required subtitles: English

## 1. Demo purpose

The demo must prove one idea:

> A product manager can turn a conversation into a running full-stack product, change its business rules, keep real data, and recover earlier states without seeing a development environment.

Do not spend the opening on architecture. Show the barrier, then the transformation. Technical details appear only after the product has already worked.

## 2. Prepared environment

- Packaged Linux amd64 application image.
- Fresh `ppp-data` volume created by the rehearsal reset command.
- Fake provider for camera blocking and timing rehearsals.
- Codex provider for the final take only after LIVE-03, LIVE-04, and LIVE-05 pass 3/3.
- Browser at 1440x900 with 100% zoom.
- Shared-password sign-in completed before the product story; if shown, obscure
  the credential in the recording.
- Projects starts empty for the take. Create one named project and open its
  literal blank canvas before Prompt A.
- No terminal visible after launch.
- Enter sends each prepared conversation turn; Shift+Enter remains available
  for intentional multiline prompts.
- Notifications, bookmarks bar, and unrelated tabs hidden.
- Screen recording at 30fps or higher.

Before each run:

```bash
bb demo-reset
bb demo-preflight
```

`demo-reset` removes only containers and volumes labeled `ppp.demo=true`; it never touches normal sessions or OAuth. `demo-preflight` verifies Docker, Compose, Playwright, and the packaged rehearsal files before recording.

## 3. Exact prompts

### Prompt A: change the control surface

```text
Make this sidebar a focused floating panel. Keep Projects navigation, the project selector, conversation, and composer, but give it more breathing room and make it feel like a calm product workspace.
```

Expected outcome:

- Sidebar becomes a rounded floating panel.
- Projects navigation, project selector, `+`, conversation, and composer still work.
- Canvas does not refresh.

### Prompt B: build the product

```text
Build a product showcase with Gallery, Submit, and Leaderboard sections. Start with six realistic project ideas. Let a person vote as either public or judge, store projects and votes in SQLite, and calculate the leaderboard on the server. Submitting a project and voting should work immediately and survive a browser reload.
```

Expected outcome:

- Three sections are available.
- Six seed projects render.
- Public/judge selector and vote buttons work.
- Submit creates a persistent project.
- Leaderboard is server-calculated.
- Browser reload retains votes and projects.

### Prompt C: change a business rule

```text
Change the scoring so a judge vote is worth 3 points and a public vote is worth 1 point. Keep ties deterministic, and mark the top 3 like a podium on the leaderboard.
```

Expected outcome:

- Existing votes are re-scored without data loss.
- Server response reports judge weight 3 and public weight 1.
- Top three are visibly marked.
- UI and server rule activate together without refresh.

### Restore prompts

```text
Restore the checkpoint before the scoring change.
```

```text
Restore the checkpoint with weighted judge votes and the top 3 podium.
```

Expected outcome:

- First restore removes weighted presentation/rule and restores corresponding data state.
- Second restore returns to the later checkpoint.
- Both restores become new history events.

## 4. Shot and narration plan

| Time | Picture | English subtitle / narration |
|---:|---|---|
| 0:00-0:12 | Brief sign-in cut, Projects, create `Hackathon workspace`, then literal white screen with handle. | `Product managers do not fail at prompting. They get blocked by installs, Git, folders, builds, and authentication.` |
| 0:12-0:23 | Default sidebar and one sentence typed. | `PPP moves that machinery behind the kind of browser workspace people already know.` |
| 0:23-0:39 | Send Prompt A. Show four progress words and floating result. | `The conversation is part of the product, so it can redesign itself without a refresh.` |
| 0:39-1:08 | Send Prompt B. Product appears. Move through Gallery, Submit, Leaderboard. | `Now I ask for a real product: three views, six seed projects, persistent voting, and server-side ranking.` |
| 1:08-1:27 | Vote as public and judge, reload browser, open leaderboard. | `These are not mock cards. Votes are stored in this session's SQLite database and remain after reload.` |
| 1:27-1:50 | Send Prompt C. Show reordered scores and podium. | `One conversation changes the business rule and its presentation atomically. Judge votes now count for three.` |
| 1:50-2:08 | Return to Projects, create a second blank project, then reopen the first. | `Work is organized like a familiar SaaS file. A new project is blank, and the original product and data are still here.` |
| 2:08-2:28 | Restore pre-score checkpoint, then latest checkpoint. | `Source, runtime behavior, and data move together. Every checkpoint can be revisited without losing later history.` |
| 2:28-2:43 | Brief filesystem history/source capture prepared in advance, not a code UI inside PPP. | `The user never handles files, but the handoff is real CLJ, CLJS, CSS, SQL, tests, and an append-only change record.` |
| 2:43-2:55 | Trigger broken-sidebar fixture, use shortcut and Safe Mode. | `A fixed recovery host remains outside generated code. A failed interface cannot lock the user out.` |
| 2:55-3:00 | Blank-to-gallery split or product title. | `Where product conversations become running software.` |

Target is 165-175 seconds in rehearsal. Cut pauses before accelerating narration. Final exported duration must remain below 180 seconds.

## 5. Camera rules

- Keep pointer movement deliberate and slow enough to follow.
- Do not show a terminal during the product story.
- Do not zoom into code while claiming the nontechnical experience.
- Use progress naturally; do not edit out all model wait and imply instant generation.
- If generation exceeds the shot budget, use an honest time compression card.
- Do not expose shared passwords, cookies, OAuth files, local paths containing personal information, or provider diagnostics.
- Do not claim public multi-tenancy, collaboration, source promotion, or deployed scale.

## 6. English subtitle file

The planned English subtitles are in `artifacts/demo/ppp-demo.en.srt`. Subtitle review checks:

- every spoken sentence is represented;
- terminology is consistent with the visible UI;
- no subtitle claims a permanent Deep Space 1 code patch;
- reading speed is reasonable;
- line breaks do not hide controls;
- speaker language may remain Korean.

## 7. Rehearsal gate

Record three fresh packaged rehearsals in a row:

| Run | Provider | Prompt A | Prompt B | persistence | Prompt C | projects | restore | Safe Mode | result |
|---|---|---|---|---|---|---|---|---|---|
| 1 | fake | pass | pass | pass | pass | pass | pass | pass | pass |
| 2 | fake | pass | pass | pass | pass | pass | pass | pass | pass |
| 3 | fake | pass | pass | pass | pass | pass | pass | pass | pass |

Evidence: `artifacts/demo/20260716-004803/report.edn`. Every run used a newly
created packaged-container data volume and Codex-home volume. The exact story used
the prompts above, submitted every conversation turn with Enter, and required no
terminal intervention after the browser opened.

Any manual repair, reload required to apply a change, terminal intervention, stale data, or unhandled failure resets the consecutive count.

The automated rehearsal must use Enter, not a Send-button-only shortcut, so the
recorded interaction matches the owner-facing chat contract.

## 8. Capture checklist

- [ ] Blank first frame.
- [ ] Default sidebar.
- [ ] Floating sidebar.
- [ ] Gallery with six seeds.
- [ ] Public and judge vote persistence after reload.
- [ ] Submit flow.
- [ ] Weighted leaderboard and top-three podium.
- [ ] Blank second project and restored first project.
- [ ] Old and new checkpoint restore.
- [ ] Safe Mode.
- [ ] Source/history handoff evidence with secrets redacted.
- [ ] Final frame with product name and tagline.
