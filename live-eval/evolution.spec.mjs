import { test, expect } from "@playwright/test";
import { readFileSync, writeFileSync } from "node:fs";

const observationPath = process.env.PPP_EVOLUTION_OBSERVATIONS;
const accessCode = process.env.PPP_EVOLUTION_ACCESS_CODE || "ppp-evolution-eval";
const resumeExisting = process.env.PPP_EVOLUTION_RESUME_FINAL === "1";

if (!observationPath) {
  throw new Error("PPP_EVOLUTION_OBSERVATIONS is required");
}

const prompts = {
  "EVOLVE-01": `Change only the visual styling to a genuinely dark theme. Keep the complete sidebar conversation interface and blank product canvas working. This is a client-only visual change: do not write server, shared-domain, test, or migration files.`,
  "EVOLVE-02": `Keep the dark theme and turn the sidebar into a floating panel with visible space around every edge and rounded corners. Preserve the session selector, new-session button, conversation, checkpoints, and Message composer. This is still client-only; do not change server, shared-domain, tests, or migrations.`,
  "EVOLVE-03": `Keep the dark floating sidebar and build a playable Tetris game on the product canvas. It must advance automatically from a browser timer and accept ArrowLeft, ArrowRight, and ArrowDown without a reload. Make the game root focusable with accessible name "Tetris game", and expose live values with accessible labels "Piece row" and "Piece column" so the running behavior can be verified. Keep all game logic in the browser sandbox; do not change server, shared-domain, tests, or migrations.`,
  "EVOLVE-04": `Keep Tetris fully playable and add a persistent player ranking panel beside or below it. Add fields with accessible labels "Player name" and "Raw score" and a button named "Save score". Store entries in SQLite through registered server actions. The server must return the complete ranking read model ordered by raw score descending with deterministic ties, and show each row as a player name plus "N points". Add a host-approved migration and rollback-only domain tests that prove save, reload, ordering, and response shape. Preserve the dark floating conversation sidebar.`,
  "EVOLVE-05": `Change the ranking business rule on the server so displayed points are raw score multiplied by 2. Existing stored entries must immediately use the new rule without rewriting their raw data. Keep deterministic ties, Tetris, the ranking form, all data, and the complete sidebar. Update the shared/server source and rollback-only domain tests, but do not add a migration because the schema is unchanged.`,
  "EVOLVE-06": `Replace only the Tetris game with an interactive Gomoku board while preserving the persistent ranking panel, its existing server actions, stored entries, the dark floating sidebar, and every unrelated feature. Remove all Tetris UI, timers, and listeners. Render a board with accessible name "Gomoku board", intersection buttons named like "Place stone row 1 column 1", and visible turn status that changes from "Black to move" to "White to move" after the first move. This replacement is client-only: do not change server, shared-domain, tests, or migrations.`,
  "EVOLVE-07": `Implement login and signup for this game. Preserve the playable Gomoku board, the existing ranking panel, stored entries, and the complete dark floating sidebar. Add real product accounts using the provided product-auth capabilities, not PPP workspace access. Start on a signup form with accessible fields "Display name", "Sign-in ID", and "Password", a button named "Create account", and a way to switch to a button named "Sign in". Store a product profile in a new migration, keep credentials in the host auth resource, and add rollback-only domain tests. After signup show "Signed in as <display name>" and a button named "Sign out". Make "Save score" require the signed-in product user, while keeping its existing accessible fields and persisted ranking behavior. Login must survive reload. Do not remove or replace the game.`,
  "EVOLVE-08": `Extend this same product with a complete resource workbench. Preserve the playable Gomoku board, the existing ranking and Ada's stored 20-point result, product signup/sign-in/sign-out, and the complete dark floating conversation sidebar. Do not replace those features. Add a file input with accessible name "Upload resource" that stores exact files through blob-put!, and render persisted objects in a region named "Stored resources" after reload. Add a textbox and button both named "Search resources" and render Unicode search matches in a region named "Resource search results" through search-upsert!/search-query. Add a button named "Process resource" that schedules a registered durable job; schedule-job! returns a public job map, so retain (:id scheduled-job) and pass only that string to job-status. Expose its eventual status in an output named "Resource status" without requiring another user action. Publish post-commit events for upload, job completion, and public import, register browser event handlers that reconstruct current state, and expose a numeric output named "Resource event count" so a second tab visibly updates. Register a public ingress named :resource-import that accepts exactly {:id bounded-slug :name string :text string}, adds that named searchable import record, publishes an event, and returns {:status 202 :body {:accepted true}}. Use the Kernel resource APIs, not generated blob/job/search tables, server threads, or browser-only persistence. Add rollback-only domain tests that invoke the scheduling action, job, and ingress handlers and assert durable read outcomes. No migration is needed for Kernel resources.`
};

const scenarioOrder = [
  "EVOLVE-01",
  "EVOLVE-02",
  "EVOLVE-03",
  "EVOLVE-04",
  "EVOLVE-05",
  "EVOLVE-06",
  "EVOLVE-07",
  "EVOLVE-08"
];
const maximumSemanticRepairs = 5;

function productFrame(page) {
  return page.frameLocator(
    "iframe[data-ppp-runtime-frame]:not(.ppp-runtime-frame-staged)"
  );
}

function gomokuBoard(page) {
  return productFrame(page).getByRole("grid", {
    name: "Gomoku board",
    exact: true
  });
}

async function snapshot(page) {
  return page.evaluate(() => window.__PPP_TEST__?.snapshot());
}

async function waitForRuntime(page, sessionId, version) {
  await expect.poll(async () => {
    const current = await snapshot(page);
    return current && current["session-id"] === sessionId
      && current.version === version
      && current.connection === "connected";
  }, { timeout: 45_000 }).toBe(true);
}

async function createFreshSession(page) {
  await page.goto(`/?test-runtime=1#access=${encodeURIComponent(accessCode)}`, {
    waitUntil: "domcontentloaded",
    timeout: 30_000
  });
  await expect.poll(async () => Boolean((await snapshot(page))?.["session-id"]), {
    timeout: 30_000
  }).toBe(true);
  const session = await page.evaluate(async () => {
    const bootstrapResponse = await fetch("/api/bootstrap", { credentials: "same-origin" });
    const bootstrap = await bootstrapResponse.json();
    const response = await fetch("/api/sessions", {
      method: "POST",
      credentials: "same-origin",
      headers: {
        "content-type": "application/json",
        "x-ppp-csrf": bootstrap["csrf-token"]
      },
      body: "{}"
    });
    if (!response.ok) throw new Error("Could not create an evolution session");
    return response.json();
  });
  await page.goto(`/?test-runtime=1&session=${encodeURIComponent(session.id)}`, {
    waitUntil: "domcontentloaded",
    timeout: 30_000
  });
  await waitForRuntime(page, session.id, 0);
  await page.evaluate(() => window.__PPP_TEST__.openSidebar());
  await expect(productFrame(page).getByRole("complementary", {
    name: "Product conversation"
  })).toBeVisible();
  return session.id;
}

async function submitTurn(page, prompt) {
  const before = await snapshot(page);
  const messageCount = before.messages.length;
  const started = Date.now();
  await page.evaluate(value => window.__PPP_TEST__.submitPrompt(value), prompt);
  await expect.poll(async () => {
    const current = await snapshot(page);
    return current.progress === null && current.messages.length >= messageCount + 2;
  }, { timeout: 720_000 }).toBe(true);
  const after = await snapshot(page);
  return { before, after, duration: Date.now() - started };
}

function brightness(color) {
  const values = String(color).match(/[\d.]+/g)?.slice(0, 3).map(Number) || [];
  return values.length === 3 ? values.reduce((sum, value) => sum + value, 0) / 3 : 255;
}

async function activeFrameCount(page) {
  return page.locator("iframe[data-ppp-runtime-frame]:not(.ppp-runtime-frame-staged)").count();
}

async function closeConversation(page) {
  const close = page.getByRole("button", { name: "Close product conversation" });
  if (await close.count()) {
    await close.click();
  }
}

function recordFor(scenario, turn, outcome, diagnostics = {}) {
  return {
    scenario,
    "before-version": turn.before.version,
    "after-version": turn.after.version,
    "duration-ms": turn.duration,
    "browser-outcome": Boolean(outcome),
    "client-stage-valid": !turn.after["debug-error"],
    diagnostics
  };
}

function writeObservations(sessionId, records) {
  writeFileSync(observationPath, JSON.stringify({
    "format-version": 1,
    "session-id": sessionId,
    records
  }, null, 2));
}

function readObservations() {
  return JSON.parse(readFileSync(observationPath, "utf8"));
}

async function appendAndRequire(
  page,
  sessionId,
  records,
  scenario,
  turn,
  outcome,
  diagnostics = {},
  expectedCommitCount = 1
) {
  const record = recordFor(scenario, turn, outcome, {
    ...diagnostics,
    "active-frame-count": await activeFrameCount(page)
  });
  records.push(record);
  writeObservations(sessionId, records);
  expect(record["after-version"] - record["before-version"],
    `${scenario} must commit its initial change and only bounded repairs`)
    .toBe(expectedCommitCount);
  expect(record["client-stage-valid"], `${scenario} must have no browser runtime error`)
    .toBe(true);
  expect(record["browser-outcome"], `${scenario} semantic outcome`).toBe(true);
  expect(record.diagnostics["active-frame-count"], `${scenario} must leave one active frame`)
    .toBe(1);
}

async function verifyGomokuReplacement(page) {
  await closeConversation(page);
  await expect(gomokuBoard(page)).toBeVisible();
  const blackTurn = productFrame(page).getByText("Black to move", {
    exact: false
  }).first();
  if (await blackTurn.isVisible()) {
    await productFrame(page).getByRole("button", {
      name: "Place stone row 1 column 1",
      exact: true
    }).click();
  }
  await expect(productFrame(page).getByText("White to move", {
    exact: false
  }).first()).toBeVisible();
  await expect(productFrame(page).getByLabel("Tetris game")).toHaveCount(0);
  await expect(productFrame(page).locator("body")).toContainText("Ada");
  await expect(productFrame(page).locator("body")).toContainText(/20\s+points?/i);
  return {
    "gomoku-move": true,
    "tetris-removed": true,
    "ranking-preserved": true
  };
}

async function verifyProductAccounts(page, sessionId, version) {
  await closeConversation(page);
  await expect(gomokuBoard(page)).toBeVisible();
  await expect(productFrame(page).locator("body")).toContainText("Ada");
  await expect(productFrame(page).locator("body")).toContainText(/20\s+points?/i);

  const displayName = productFrame(page).getByRole("textbox", {
    name: "Display name"
  });
  const identifier = productFrame(page).getByRole("textbox", {
    name: "Sign-in ID"
  });
  const password = productFrame(page).getByLabel("Password");
  await expect(displayName).toBeVisible();
  await expect(identifier).toBeVisible();
  await expect(password).toBeVisible();
  await expect(productFrame(page).getByRole("button", {
    name: "Create account",
    exact: true
  })).toBeVisible();

  await displayName.fill("Grace");
  await identifier.fill(`grace-player-${Date.now()}`);
  await password.fill("correct horse battery");
  await productFrame(page).getByRole("button", {
    name: "Create account",
    exact: true
  }).click();
  await expect(productFrame(page).locator("body")).toContainText(/Signed in as Grace/i);

  await page.reload({ waitUntil: "domcontentloaded", timeout: 30_000 });
  await waitForRuntime(page, sessionId, version);
  await expect(productFrame(page).locator("body")).toContainText(/Signed in as Grace/i,
    { timeout: 20_000 });
  await expect(gomokuBoard(page)).toBeVisible();
  await expect(productFrame(page).locator("body")).toContainText("Ada");

  await productFrame(page).getByLabel("Player name").fill("Grace");
  await productFrame(page).getByLabel("Raw score").fill("12");
  await productFrame(page).getByRole("button", { name: "Save score" }).click();
  await expect(productFrame(page).locator("body")).toContainText("Grace");
  await expect(productFrame(page).locator("body")).toContainText(/24\s+points?/i);
  await productFrame(page).getByRole("button", { name: "Sign out" }).click();
  await expect(productFrame(page).getByRole("button", {
    name: "Create account",
    exact: true
  })).toBeVisible();

  return {
    "signup-applied": true,
    "authenticated-reload": true,
    "protected-score-saved": true,
    "logout-applied": true,
    "gomoku-preserved": true,
    "ranking-preserved": true
  };
}

async function verifyResourceWorkbench(page, context, request, sessionId, version) {
  await closeConversation(page);
  await expect(gomokuBoard(page)).toBeVisible();
  await expect(productFrame(page).locator("body")).toContainText("Ada");
  await expect(productFrame(page).locator("body")).toContainText(/20\s+points?/i);
  await expect(productFrame(page).getByRole("button", {
    name: "Create account",
    exact: true
  })).toBeVisible();

  const follower = await context.newPage();
  await follower.goto(`/?test-runtime=1&session=${encodeURIComponent(sessionId)}`, {
    waitUntil: "domcontentloaded",
    timeout: 30_000
  });
  await waitForRuntime(follower, sessionId, version);

  const eventBefore = Number(
    await productFrame(follower).getByLabel("Resource event count").textContent()
  );
  const resourceName = `evolution-한글-${Date.now()}.txt`;
  await productFrame(page).getByLabel("Upload resource").setInputFiles({
    name: resourceName,
    mimeType: "text/plain",
    buffer: Buffer.from("Codex resource evolution 한글", "utf8")
  });
  await expect(productFrame(page).getByRole("region", { name: "Stored resources" }))
    .toContainText(resourceName, { timeout: 20_000 });
  await expect.poll(async () => Number(
    await productFrame(follower).getByLabel("Resource event count").textContent()
  ), { timeout: 20_000 }).toBeGreaterThan(eventBefore);
  await expect(productFrame(follower).getByRole("region", { name: "Stored resources" }))
    .toContainText(resourceName, { timeout: 20_000 });

  await page.reload({ waitUntil: "domcontentloaded", timeout: 30_000 });
  await waitForRuntime(page, sessionId, version);
  await expect(productFrame(page).getByRole("region", { name: "Stored resources" }))
    .toContainText(resourceName, { timeout: 20_000 });
  await productFrame(page).getByLabel("Search resources").fill("한글");
  await productFrame(page).getByRole("button", {
    name: "Search resources",
    exact: true
  }).click();
  await expect(productFrame(page).getByRole("region", {
    name: "Resource search results"
  })).toContainText(resourceName, { timeout: 20_000 });

  const resourceRow = productFrame(page).getByRole("region", {
    name: "Stored resources"
  }).locator("article").filter({ hasText: resourceName });
  await resourceRow.getByRole("button", { name: "Process resource" }).click();
  await expect(productFrame(page).getByLabel("Resource status"))
    .toHaveText(/processed|completed/i, { timeout: 30_000 });

  const ingressEventBefore = Number(
    await productFrame(follower).getByLabel("Resource event count").textContent()
  );
  const importName = `live evolution import ${Date.now()}`;
  const ingress = await request.post(
    `/public/sessions/${encodeURIComponent(sessionId)}/resource-import`,
    {
      data: {
        id: `live-evolution-import-${Date.now()}`,
        name: importName,
        text: "public resource 한글"
      }
    }
  );
  expect(ingress.status()).toBe(202);
  expect(await ingress.json()).toMatchObject({ accepted: true });
  await expect.poll(async () => Number(
    await productFrame(follower).getByLabel("Resource event count").textContent()
  ), { timeout: 20_000 }).toBeGreaterThan(ingressEventBefore);
  await productFrame(follower).getByRole("textbox", {
    name: "Search resources",
    exact: true
  }).fill(importName);
  await productFrame(follower).getByRole("button", {
    name: "Search resources",
    exact: true
  }).click();
  await expect(productFrame(follower).getByRole("region", {
    name: "Resource search results"
  })).toContainText(importName, { timeout: 20_000 });

  await expect(gomokuBoard(page)).toBeVisible();
  await expect(productFrame(page).locator("body")).toContainText("Ada");
  await expect(productFrame(page).locator("body")).toContainText(/20\s+points?/i);
  await follower.close();

  return {
    "blob-reload-persisted": true,
    "unicode-search-matched": true,
    "background-job-completed": true,
    "second-tab-event-delivered": true,
    "public-ingress-accepted": true,
    "gomoku-preserved": true,
    "ranking-preserved": true,
    "accounts-preserved": true
  };
}

async function verifyLateScenario(scenario, page, context, request, sessionId, version) {
  switch (scenario) {
    case "EVOLVE-06":
      return verifyGomokuReplacement(page);
    case "EVOLVE-07":
      return verifyProductAccounts(page, sessionId, version);
    case "EVOLVE-08":
      return verifyResourceWorkbench(page, context, request, sessionId, version);
    default:
      throw new Error(`Cannot resume unsupported scenario ${scenario}`);
  }
}

function boundedFailureFeedback(error) {
  return String(error?.message || error || "Unknown browser outcome failure")
    .replace(/\s+/g, " ")
    .slice(0, 1400);
}

async function verifyLateScenarioWithRepair(
  scenario,
  initialTurn,
  page,
  context,
  request,
  sessionId
) {
  const before = initialTurn.before;
  let after = initialTurn.after;
  let duration = initialTurn.duration;
  const initialCommitCount = after.version - before.version;
  if (initialCommitCount < 1
      || initialCommitCount > maximumSemanticRepairs + 1) {
    throw new Error(
      `${scenario} has an invalid committed version span ${initialCommitCount}`
    );
  }
  let repairCount = initialCommitCount - 1;

  while (true) {
    try {
      const diagnostics = await verifyLateScenario(
        scenario,
        page,
        context,
        request,
        sessionId,
        after.version
      );
      return {
        turn: { before, after, duration },
        diagnostics: {
          ...diagnostics,
          "semantic-repair-count": repairCount
        },
        commitCount: 1 + repairCount
      };
    } catch (error) {
      if (repairCount >= maximumSemanticRepairs) throw error;
      const feedback = boundedFailureFeedback(error);
      const repairTurn = await submitTurn(
        page,
        `The saved ${scenario} change failed real browser outcome verification. `
        + "Repair the current product now; do not merely explain the failure, "
        + "remove existing features, or add a migration unless the original request required one. "
        + `Original required outcome: ${prompts[scenario]} `
        + `Bounded browser feedback: ${feedback}`
      );
      expect(repairTurn.before.version,
        `${scenario} repair must start from the failed committed version`)
        .toBe(after.version);
      expect(repairTurn.after.version,
        `${scenario} repair must commit one corrected version`)
        .toBe(after.version + 1);
      after = repairTurn.after;
      duration += repairTurn.duration;
      repairCount += 1;
    }
  }
}

test("real Codex evolves one product across client and server runtime boundaries", async ({ page, context, request }) => {
  test.setTimeout(3_600_000);

  if (resumeExisting) {
    const observations = readObservations();
    const sessionId = observations["session-id"];
    const records = observations.records;
    const recordedVersion = records.at(-1)?.["after-version"] ?? 0;
    await page.goto(
      `/?test-runtime=1&session=${encodeURIComponent(sessionId)}#access=${encodeURIComponent(accessCode)}`,
      { waitUntil: "domcontentloaded", timeout: 30_000 }
    );
    await expect.poll(async () => {
      const current = await snapshot(page);
      return current?.["session-id"] === sessionId
        && current.connection === "connected"
        && Number.isInteger(current.version);
    }, { timeout: 45_000 }).toBe(true);
    let current = await snapshot(page);
    if (records.length < 5
        || current.version < recordedVersion
        || current.version > recordedVersion + maximumSemanticRepairs + 1) {
      throw new Error(
        `Evolution resume cannot reconcile recorded version ${recordedVersion} `
        + `with active version ${current.version}`
      );
    }

    // A change may already be committed when a semantic browser assertion
    // fails. Re-verify that exact version and record it before asking Codex for
    // another change; never generate a duplicate turn merely to repair the
    // evaluator.
    if (current.version > recordedVersion) {
      const scenario = scenarioOrder[records.length];
      const verified = await verifyLateScenarioWithRepair(
        scenario,
        { before: { version: recordedVersion }, after: current, duration: 0 },
        page,
        context,
        request,
        sessionId
      );
      await appendAndRequire(
        page,
        sessionId,
        records,
        scenario,
        verified.turn,
        true,
        verified.diagnostics,
        verified.commitCount
      );
      current = verified.turn.after;
    }

    while (records.length < scenarioOrder.length) {
      const scenario = scenarioOrder[records.length];
      const turn = await submitTurn(page, prompts[scenario]);
      const verified = await verifyLateScenarioWithRepair(
        scenario,
        turn,
        page,
        context,
        request,
        sessionId
      );
      await appendAndRequire(
        page,
        sessionId,
        records,
        scenario,
        verified.turn,
        true,
        verified.diagnostics,
        verified.commitCount
      );
      current = verified.turn.after;
    }
    return;
  }

  const sessionId = await createFreshSession(page);
  const records = [];

  let turn = await submitTurn(page, prompts["EVOLVE-01"]);
  const dark = await productFrame(page).getByRole("complementary", {
    name: "Product conversation"
  }).evaluate(element => {
    const style = getComputedStyle(element);
    return { background: style.backgroundColor, color: style.color };
  });
  await appendAndRequire(page, sessionId, records, "EVOLVE-01", turn,
    brightness(dark.background) < 100 && brightness(dark.color) > 130,
    { "dark-surface": true });

  turn = await submitTurn(page, prompts["EVOLVE-02"]);
  const floating = await productFrame(page).getByRole("complementary", {
    name: "Product conversation"
  }).evaluate(element => {
    const rectangle = element.getBoundingClientRect();
    const style = getComputedStyle(element);
    return rectangle.left > 7 && rectangle.top > 7
      && innerWidth - rectangle.right > 7
      && innerHeight - rectangle.bottom > 7
      && parseFloat(style.borderRadius) > 0;
  });
  await appendAndRequire(page, sessionId, records, "EVOLVE-02", turn, floating,
    { floating });

  turn = await submitTurn(page, prompts["EVOLVE-03"]);
  await closeConversation(page);
  const game = productFrame(page).getByLabel("Tetris game");
  const row = productFrame(page).getByLabel("Piece row");
  const column = productFrame(page).getByLabel("Piece column");
  await expect(game).toBeVisible();
  const rowBefore = await row.textContent();
  await expect.poll(async () => row.textContent(), { timeout: 8_000 })
    .not.toBe(rowBefore);
  const columnBefore = await column.textContent();
  await game.press("ArrowLeft");
  await expect.poll(async () => column.textContent(), { timeout: 3_000 })
    .not.toBe(columnBefore);
  await appendAndRequire(page, sessionId, records, "EVOLVE-03", turn, true,
    { "timer-advanced": true, "keyboard-moved": true });

  turn = await submitTurn(page, prompts["EVOLVE-04"]);
  await closeConversation(page);
  await productFrame(page).getByLabel("Player name").fill("Ada");
  await productFrame(page).getByLabel("Raw score").fill("10");
  await productFrame(page).getByRole("button", { name: "Save score" }).click();
  const productBody = productFrame(page).locator("body");
  await expect(productBody).toContainText("Ada", { timeout: 20_000 });
  await expect(productBody).toContainText(/10\s+points?/i, { timeout: 20_000 });
  await page.reload({ waitUntil: "domcontentloaded", timeout: 30_000 });
  await waitForRuntime(page, sessionId, turn.after.version);
  await expect(productFrame(page).locator("body")).toContainText("Ada", { timeout: 20_000 });
  await expect(productFrame(page).locator("body")).toContainText(/10\s+points?/i,
    { timeout: 20_000 });
  await appendAndRequire(page, sessionId, records, "EVOLVE-04", turn, true,
    { "sqlite-reload-persisted": true, "raw-points": 10 });

  turn = await submitTurn(page, prompts["EVOLVE-05"]);
  await expect(productFrame(page).locator("body")).toContainText("Ada", { timeout: 20_000 });
  await expect(productFrame(page).locator("body")).toContainText(/20\s+points?/i,
    { timeout: 20_000 });
  await appendAndRequire(page, sessionId, records, "EVOLVE-05", turn, true,
    { "server-rule-points": 20 });

  turn = await submitTurn(page, prompts["EVOLVE-06"]);
  let verified = await verifyLateScenarioWithRepair(
    "EVOLVE-06", turn, page, context, request, sessionId
  );
  await appendAndRequire(page, sessionId, records, "EVOLVE-06", verified.turn, true,
    verified.diagnostics, verified.commitCount);

  turn = await submitTurn(page, prompts["EVOLVE-07"]);
  verified = await verifyLateScenarioWithRepair(
    "EVOLVE-07", turn, page, context, request, sessionId
  );
  await appendAndRequire(page, sessionId, records, "EVOLVE-07", verified.turn, true,
    verified.diagnostics, verified.commitCount);

  turn = await submitTurn(page, prompts["EVOLVE-08"]);
  verified = await verifyLateScenarioWithRepair(
    "EVOLVE-08", turn, page, context, request, sessionId
  );
  await appendAndRequire(page, sessionId, records, "EVOLVE-08", verified.turn, true,
    verified.diagnostics, verified.commitCount);
});
