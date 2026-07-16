import { test, expect } from "@playwright/test";
import {
  activeFrameChannel,
  createFreshSession,
  openConversation,
  productFrame,
  sendTurn
} from "./support.mjs";

function rgbLightness(value) {
  const channels = value.match(/[\d.]+/g)?.slice(0, 3).map(Number) || [];
  return channels.length === 3
    ? (Math.max(...channels) + Math.min(...channels)) / 2
    : 255;
}

test("a CSS-only AI change stages with a populated conversation and stays editable", async ({ page }) => {
  const browserErrors = [];
  page.on("pageerror", error => browserErrors.push(error.message));

  await page.goto("/?test-runtime=1#access=ppp-local");
  await openConversation(page);
  await createFreshSession(page);
  await expect.poll(
    () => page.evaluate(() => window.__PPP_TEST__.snapshot().version)
  ).toBe(0);

  await sendTurn(page, "Apply a dark theme");
  await expect(productFrame(page).getByText("The running workspace now uses a dark theme."))
    .toBeVisible({ timeout: 15_000 });
  expect(await page.evaluate(() => window.__PPP_TEST__.snapshot().version)).toBe(1);
  expect(browserErrors).toEqual([]);
  const acceptedChannel = await activeFrameChannel(page);

  const sidebar = productFrame(page).getByRole("complementary", { name: "Product conversation" });
  const background = await sidebar.evaluate(node => getComputedStyle(node).backgroundColor);
  expect(rgbLightness(background), background).toBeLessThan(160);

  await sendTurn(page, "[[fake:css-only-floating]]");
  await expect(productFrame(page).getByText("The dark sidebar is now a floating panel."))
    .toBeVisible({ timeout: 15_000 });
  expect(await page.evaluate(() => window.__PPP_TEST__.snapshot().version)).toBe(2);
  const floating = await sidebar.evaluate(node => {
    const style = getComputedStyle(node);
    const rect = node.getBoundingClientRect();
    return {
      position: style.position,
      radius: parseFloat(style.borderTopLeftRadius),
      top: rect.top,
      right: window.innerWidth - rect.right,
    };
  });
  expect(floating.position).toBe("fixed");
  expect(floating.radius).toBeGreaterThan(0);
  expect(floating.top).toBeGreaterThan(0);
  expect(floating.right).toBeGreaterThan(0);
  const floatingChannel = await activeFrameChannel(page);
  expect(floatingChannel).not.toBe(acceptedChannel);

  await sendTurn(page, "[[fake:client-render-error]]");
  await expect(productFrame(page).getByText(
    /could not be drawn.*Reason: Hidden render fixture/s
  ))
    .toBeVisible({ timeout: 25_000 });
  expect(await page.evaluate(() => window.__PPP_TEST__.snapshot().version)).toBe(2);
  expect(browserErrors).toEqual([]);
  expect(await activeFrameChannel(page)).toBe(floatingChannel);
  await expect(page.locator("iframe[data-ppp-runtime-frame]")).toHaveCount(1);

  const composer = productFrame(page).getByRole("textbox", { name: "Message" });
  await composer.fill("still editable");
  await expect(composer).toHaveValue("still editable");
  await expect(composer).toBeFocused();
});

test("AI-generated Tetris falls automatically, accepts keys, and survives rejection", async ({ page }) => {
  await page.goto("/?test-runtime=1#access=ppp-local");
  await openConversation(page);
  await createFreshSession(page);

  await sendTurn(page, "테트리스 구현해줘");
  await expect(productFrame(page).getByText(
    "Tetris is running with automatic falling and keyboard controls."
  )).toBeVisible({ timeout: 15_000 });
  await page.getByRole("button", { name: "Close product conversation" }).click();

  const game = productFrame(page);
  await expect(game.getByRole("main", { name: "Tetris game" })).toBeVisible();
  await expect(game.getByLabel("Tetris board")).toBeVisible();
  await expect(game.getByLabel("Falling tetromino").locator("span")).toHaveCount(4);

  const initialRow = Number(await game.getByLabel("Piece row").textContent());
  await expect.poll(async () => Number(await game.getByLabel("Piece row").textContent()))
    .toBeGreaterThan(initialRow);

  const initialColumn = Number(await game.getByLabel("Piece column").textContent());
  await game.getByRole("main", { name: "Tetris game" }).click();
  await page.keyboard.press("ArrowRight");
  await expect(game.getByLabel("Piece column")).toHaveText(String(initialColumn + 1));

  const rowBeforeRejection = Number(await game.getByLabel("Piece row").textContent());
  await openConversation(page);
  await sendTurn(page, "[[fake:client-render-error]]");
  await expect(productFrame(page).getByText(
    /could not be drawn.*Reason: Hidden render fixture/s
  )).toBeVisible({ timeout: 25_000 });
  await expect(game.getByRole("main", { name: "Tetris game" })).toBeVisible();
  await expect.poll(async () => Number(await game.getByLabel("Piece row").textContent()))
    .not.toBe(rowBeforeRejection);
  expect(await page.evaluate(() => window.__PPP_TEST__.snapshot().version)).toBe(1);
});

test("the immutable recovery sidebar can repair a saved product whose client cannot activate", async ({ page }) => {
  await page.goto("/?test-runtime=1#access=ppp-local");
  await openConversation(page);
  await createFreshSession(page);
  await expect.poll(
    () => page.evaluate(() => window.__PPP_TEST__.snapshot().version)
  ).toBe(0);

  await page.evaluate(() => window.__PPP_TEST__.simulateRuntimeFailure());
  const broken = await page.evaluate(() => window.__PPP_TEST__.snapshot());
  expect(broken.version).toBeNull();
  expect(broken["saved-version"]).toBe(0);
  await expect(page.getByText(/saved session is unchanged/i)).toBeVisible();

  await sendTurn(page, "Apply a dark theme", page);
  await expect(productFrame(page).getByText("The running workspace now uses a dark theme."))
    .toBeVisible({ timeout: 20_000 });
  await expect.poll(
    () => page.evaluate(() => window.__PPP_TEST__.snapshot().version)
  ).toBe(1);
});

test("conversation atomically builds and evolves a persistent full-stack product", async ({ page }) => {
  await page.goto("/?test-runtime=1#access=ppp-local");
  await openConversation(page);
  await createFreshSession(page);
  await expect.poll(
    () => page.evaluate(() => window.__PPP_TEST__.snapshot().version)
  ).toBe(0);

  await sendTurn(page, "Make the sidebar a floating panel");
  await expect(productFrame(page).getByText("The conversation is now a compact floating workspace."))
    .toBeVisible({ timeout: 15_000 });
  expect(await page.evaluate(() => window.__PPP_TEST__.snapshot().version)).toBe(1);

  await sendTurn(page, "Create a gallery with voting and a leaderboard");
  await expect(productFrame(page).getByText(
    "The gallery, submission flow, voting actions, and persistent leaderboard are running."
  )).toBeVisible({ timeout: 15_000 });
  await expect(productFrame(page).getByRole("heading", {
    name: "Products you can try, judge, and improve."
  })).toBeVisible();
  await expect(productFrame(page).getByRole("button", { name: "Vote" })).toHaveCount(6);
  expect(await page.evaluate(() => window.__PPP_TEST__.snapshot().version)).toBe(2);

  await productFrame(page).getByRole("button", { name: "Vote" }).first().click();
  await expect(productFrame(page).getByText("1 points").first()).toBeVisible();

  await page.reload();
  await expect(productFrame(page).getByText("Patchwork")).toBeVisible();
  await expect(productFrame(page).getByText("1 points").first()).toBeVisible();

  await openConversation(page);
  await sendTurn(page, "Make judge=3 points and show the top 3 podium");
  await expect(productFrame(page).getByText(/Judge votes now count for three points/))
    .toBeVisible({ timeout: 15_000 });
  await page.getByRole("button", { name: "Close product conversation" }).click();
  await productFrame(page).getByRole("button", { name: "Leaderboard" }).click();
  await expect(productFrame(page).getByText("Judge 3 points, public 1 point")).toBeVisible();
  await expect(productFrame(page).locator(".podium-place")).toHaveCount(3);
  expect(await page.evaluate(() => window.__PPP_TEST__.snapshot().version)).toBe(3);

  await openConversation(page);
  await expect(productFrame(page).getByRole("region", { name: "Checkpoints" })).toBeVisible();
  const channelBeforeFirstRestore = await activeFrameChannel(page);
  await productFrame(page).getByRole("button", {
    name: "Create the product gallery and voting workflow"
  }).click();
  await expect(productFrame(page).getByText("Checkpoint 2 is restored as a new version."))
    .toBeVisible({ timeout: 15_000 });
  expect(await page.evaluate(() => window.__PPP_TEST__.snapshot().version)).toBe(4);
  expect(await activeFrameChannel(page)).not.toBe(channelBeforeFirstRestore);
  await expect(page.locator("iframe[data-ppp-runtime-frame]")).toHaveCount(1);
  await page.getByRole("button", { name: "Close product conversation" }).click();
  await expect(productFrame(page).getByText("Judge 1 points, public 1 point")).toBeVisible();
  await expect(productFrame(page).locator(".podium-place")).toHaveCount(0);
  await expect(productFrame(page).getByText("0 points").first()).toBeVisible();

  await openConversation(page);
  const channelBeforeSecondRestore = await activeFrameChannel(page);
  await productFrame(page).getByRole("button", {
    name: "Weight judge votes and show the top three"
  }).click();
  await expect(productFrame(page).getByText("Checkpoint 3 is restored as a new version."))
    .toBeVisible({ timeout: 15_000 });
  expect(await page.evaluate(() => window.__PPP_TEST__.snapshot().version)).toBe(5);
  expect(await activeFrameChannel(page)).not.toBe(channelBeforeSecondRestore);
  await expect(page.locator("iframe[data-ppp-runtime-frame]")).toHaveCount(1);
  await page.getByRole("button", { name: "Close product conversation" }).click();
  await expect(productFrame(page).getByText("Judge 3 points, public 1 point")).toBeVisible();
  await expect(productFrame(page).locator(".podium-place")).toHaveCount(3);
});

test("requesting tab commits while follower tabs resync without refresh", async ({ page, context }) => {
  await page.goto("/?test-runtime=1#access=ppp-local");
  await openConversation(page);
  const sessionId = await createFreshSession(page);
  await expect.poll(
    () => page.evaluate(() => window.__PPP_TEST__.snapshot().version)
  ).toBe(0);

  const follower = await context.newPage();
  await follower.goto("/?test-runtime=1&session=" + sessionId);
  await expect.poll(
    () => follower.evaluate(() => window.__PPP_TEST__.snapshot().version)
  ).toBe(0);
  const followerChannelBeforeResync = await activeFrameChannel(follower);

  await sendTurn(page, "Make the sidebar a floating panel");
  await expect(productFrame(page).getByText("The conversation is now a compact floating workspace."))
    .toBeVisible({ timeout: 15_000 });
  await expect.poll(
    () => follower.evaluate(() => window.__PPP_TEST__.snapshot().version)
  ).toBe(1);
  await expect.poll(() => activeFrameChannel(follower)).not.toBe(followerChannelBeforeResync);
  await expect(follower.locator("iframe[data-ppp-runtime-frame]")).toHaveCount(1);

  await openConversation(follower);
  await sendTurn(follower, "Create a gallery with voting and a leaderboard");
  await expect(productFrame(follower).getByText(
    "The gallery, submission flow, voting actions, and persistent leaderboard are running."
  )).toBeVisible({ timeout: 15_000 });

  await expect.poll(
    () => page.evaluate(() => window.__PPP_TEST__.snapshot().version)
  ).toBe(2);
  await expect(productFrame(page).getByText("Patchwork")).toBeVisible();
  await expect(productFrame(page).getByRole("button", { name: "Vote" })).toHaveCount(6);
  await follower.close();
});
