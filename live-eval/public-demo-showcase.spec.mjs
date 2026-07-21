import { expect, test } from "@playwright/test";

const publicOrigin = "https://ppp.openai.slopbook.org";
const publicPassword = process.env.PPP_DEMO_PUBLIC_PASSWORD;
const sessionId = process.env.PPP_DEMO_PUBLIC_SESSION_ID;

function productFrame(page) {
  return page.frameLocator(
    "iframe[data-ppp-runtime-frame]:not(.ppp-runtime-frame-staged)"
  );
}

async function pause(milliseconds) {
  await new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function openPublicSession(page) {
  expect(test.info().project.use.baseURL).toBe(publicOrigin);
  expect(publicPassword).toBeTruthy();
  expect(sessionId).toMatch(/^[0-9a-f-]{36}$/);

  await page.goto(`/?test-runtime=1&session=${encodeURIComponent(sessionId)}`, {
    waitUntil: "domcontentloaded",
    timeout: 30_000
  });
  const signIn = page.getByRole("region", { name: "Workspace sign in" });
  if (await signIn.isVisible().catch(() => false)) {
    await signIn.getByLabel("Password").fill(publicPassword);
    await signIn.getByRole("button", { name: "Continue" }).click();
  }
  await expect(page.getByRole("button", {
    name: "Open product conversation"
  })).toBeVisible({ timeout: 45_000 });
  await expect(productFrame(page).locator("body")).toBeVisible({ timeout: 45_000 });
}

async function scrollToControl(control) {
  await control.scrollIntoViewIfNeeded();
  await pause(700);
}

test("show the final public product outcomes clearly", async ({ page }) => {
  test.setTimeout(120_000);
  await openPublicSession(page);

  const backToGames = productFrame(page).getByRole("button", {
    name: "Back to games",
    exact: true
  });
  if (await backToGames.isVisible().catch(() => false)) {
    await backToGames.click();
  }

  const library = productFrame(page).getByRole("heading", {
    name: "Game library",
    exact: true
  });
  await expect(library).toBeVisible();
  await library.scrollIntoViewIfNeeded();
  await pause(3_000);

  await productFrame(page).getByRole("button", {
    name: "Play Tetris",
    exact: true
  }).click();
  const tetris = productFrame(page).getByLabel("Tetris game", { exact: true });
  const pieceRow = productFrame(page).getByLabel("Piece row", { exact: true });
  await expect(tetris).toBeVisible();
  const rowBefore = await pieceRow.textContent();
  await expect.poll(() => pieceRow.textContent(), { timeout: 8_000 })
    .not.toBe(rowBefore);
  await tetris.focus();
  await tetris.press("ArrowLeft");
  await pause(4_000);

  await productFrame(page).getByRole("button", {
    name: "Back to games",
    exact: true
  }).click();
  await expect(library).toBeVisible();
  await pause(2_000);
  await productFrame(page).getByRole("button", {
    name: "Play Snake",
    exact: true
  }).click();

  const boostButton = productFrame(page).getByRole("button", {
    name: "Boost score",
    exact: true
  });
  const scoreRule = productFrame(page).getByLabel("Score rule", { exact: true });
  const boostedScore = productFrame(page).getByLabel("Boosted score", {
    exact: true
  });
  await expect(scoreRule).toContainText(/triple server rule/i);
  await scrollToControl(boostButton);
  await boostButton.click();
  await expect(boostedScore).not.toBeEmpty({ timeout: 20_000 });
  await scrollToControl(boostedScore);
  await pause(5_000);
});
