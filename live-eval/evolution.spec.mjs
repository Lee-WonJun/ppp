import { test, expect } from "@playwright/test";
import { writeFileSync } from "node:fs";

const observationPath = process.env.PPP_EVOLUTION_OBSERVATIONS;
const accessCode = process.env.PPP_EVOLUTION_ACCESS_CODE || "ppp-evolution-eval";

if (!observationPath) {
  throw new Error("PPP_EVOLUTION_OBSERVATIONS is required");
}

const prompts = {
  "EVOLVE-01": `Change only the visual styling to a genuinely dark theme. Keep the complete sidebar conversation interface and blank product canvas working. This is a client-only visual change: do not write server, shared-domain, test, or migration files.`,
  "EVOLVE-02": `Keep the dark theme and turn the sidebar into a floating panel with visible space around every edge and rounded corners. Preserve the session selector, new-session button, conversation, checkpoints, and Message composer. This is still client-only; do not change server, shared-domain, tests, or migrations.`,
  "EVOLVE-03": `Keep the dark floating sidebar and build a playable Tetris game on the product canvas. It must advance automatically from a browser timer and accept ArrowLeft, ArrowRight, and ArrowDown without a reload. Make the game root focusable with accessible name "Tetris game", and expose live values with accessible labels "Piece row" and "Piece column" so the running behavior can be verified. Keep all game logic in the browser sandbox; do not change server, shared-domain, tests, or migrations.`,
  "EVOLVE-04": `Keep Tetris fully playable and add a persistent player ranking panel beside or below it. Add fields with accessible labels "Player name" and "Raw score" and a button named "Save score". Store entries in SQLite through registered server actions. The server must return the complete ranking read model ordered by raw score descending with deterministic ties, and show each row as a player name plus "N points". Add a host-approved migration and rollback-only domain tests that prove save, reload, ordering, and response shape. Preserve the dark floating conversation sidebar.`,
  "EVOLVE-05": `Change the ranking business rule on the server so displayed points are raw score multiplied by 2. Existing stored entries must immediately use the new rule without rewriting their raw data. Keep deterministic ties, Tetris, the ranking form, all data, and the complete sidebar. Update the shared/server source and rollback-only domain tests, but do not add a migration because the schema is unchanged.`,
  "EVOLVE-06": `Replace only the Tetris game with an interactive Gomoku board while preserving the persistent ranking panel, its existing server actions, stored entries, the dark floating sidebar, and every unrelated feature. Remove all Tetris UI, timers, and listeners. Render a board with accessible name "Gomoku board", intersection buttons named like "Place stone row 1 column 1", and visible turn status that changes from "Black to move" to "White to move" after the first move. This replacement is client-only: do not change server, shared-domain, tests, or migrations.`
};

function productFrame(page) {
  return page.frameLocator(
    "iframe[data-ppp-runtime-frame]:not(.ppp-runtime-frame-staged)"
  );
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

async function appendAndRequire(page, sessionId, records, scenario, turn, outcome, diagnostics = {}) {
  const record = recordFor(scenario, turn, outcome, {
    ...diagnostics,
    "active-frame-count": await activeFrameCount(page)
  });
  records.push(record);
  writeObservations(sessionId, records);
  expect(record["after-version"], `${scenario} must commit exactly one version`)
    .toBe(record["before-version"] + 1);
  expect(record["client-stage-valid"], `${scenario} must have no browser runtime error`)
    .toBe(true);
  expect(record["browser-outcome"], `${scenario} semantic outcome`).toBe(true);
  expect(record.diagnostics["active-frame-count"], `${scenario} must leave one active frame`)
    .toBe(1);
}

test("real Codex evolves one product across client and server runtime boundaries", async ({ page }) => {
  test.setTimeout(3_600_000);
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
  await expect(productFrame(page).getByLabel("Gomoku board")).toBeVisible();
  await expect(productFrame(page).getByText("Black to move", { exact: false })).toBeVisible();
  await productFrame(page).getByRole("button", {
    name: "Place stone row 1 column 1",
    exact: true
  }).click();
  await expect(productFrame(page).getByText("White to move", { exact: false })).toBeVisible();
  await expect(productFrame(page).getByLabel("Tetris game")).toHaveCount(0);
  await expect(productFrame(page).locator("body")).toContainText("Ada");
  await expect(productFrame(page).locator("body")).toContainText(/20\s+points?/i);
  await appendAndRequire(page, sessionId, records, "EVOLVE-06", turn, true,
    { "gomoku-move": true, "tetris-removed": true, "ranking-preserved": true });
});
