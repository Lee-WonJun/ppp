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

  const signedIn = productFrame(page).getByText(/Signed in as Player One/i).first();
  if (await signedIn.isVisible().catch(() => false)) {
    await signedIn.scrollIntoViewIfNeeded();
    await pause(2_000);
    await productFrame(page).getByRole("button", {
      name: "Sign out",
      exact: true
    }).click();
  }
  await productFrame(page).getByRole("button", {
    name: "Have an account? Sign in",
    exact: true
  }).click();
  const identifier = productFrame(page).getByRole("textbox", {
    name: "Sign-in ID",
    exact: true
  });
  const password = productFrame(page).getByLabel("Password", { exact: true });
  await identifier.scrollIntoViewIfNeeded();
  await identifier.pressSequentially(`player-${sessionId.slice(0, 8)}`, {
    delay: 28
  });
  await password.pressSequentially("arcade password", { delay: 22 });
  await productFrame(page).getByRole("button", {
    name: "Sign in",
    exact: true
  }).click();
  await expect(signedIn).toBeVisible({ timeout: 20_000 });
  await signedIn.scrollIntoViewIfNeeded();
  await pause(3_000);

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
    name: "Play Snake",
    exact: true
  }).click();
  const snake = productFrame(page).getByLabel("Snake game", { exact: true });
  const snakeRow = productFrame(page).getByLabel("Snake head row", {
    exact: true
  });
  const snakeColumn = productFrame(page).getByLabel("Snake head column", {
    exact: true
  });
  await expect(snake).toBeVisible();
  const snakePosition = async () =>
    `${await snakeRow.textContent()}:${await snakeColumn.textContent()}`;
  const snakePositionBefore = await snakePosition();
  await expect.poll(snakePosition, { timeout: 8_000 })
    .not.toBe(snakePositionBefore);
  await snake.focus();
  await snake.press("ArrowLeft");
  await pause(3_000);

  await productFrame(page).getByRole("button", {
    name: "Back to games",
    exact: true
  }).click();
  await expect(library).toBeVisible();
  await pause(1_500);

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
  await expect(productFrame(page).getByText(/Signed in as Player One/i).first())
    .toBeVisible();
});
