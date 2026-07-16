import { test, expect } from "@playwright/test";

const promptA = "Make this sidebar a focused floating panel. Keep the session selector, conversation, and composer, but give it more breathing room and make it feel like a calm product workspace.";
const promptB = "Build a product showcase with Gallery, Submit, and Leaderboard sections. Start with six realistic project ideas. Let a person vote as either public or judge, store projects and votes in SQLite, and calculate the leaderboard on the server. Submitting a project and voting should work immediately and survive a browser reload.";
const promptC = "Change the scoring so a judge vote is worth 3 points and a public vote is worth 1 point. Keep ties deterministic, and mark the top 3 like a podium on the leaderboard.";

function productFrame(page) {
  return page.frameLocator(
    "iframe[data-ppp-runtime-frame]:not(.ppp-runtime-frame-staged)"
  );
}

async function openConversation(page) {
  const handle = page.getByRole("button", { name: "Open product conversation" });
  if (await handle.isVisible()) {
    await handle.click();
  }
  await expect(
    productFrame(page).getByRole("complementary", { name: "Product conversation" })
  ).toBeVisible();
  return productFrame(page);
}

async function closeConversation(page) {
  const handle = page.getByRole("button", { name: "Close product conversation" });
  if (await handle.isVisible()) {
    await handle.click();
  }
}

async function sendTurn(page, prompt) {
  const frame = await openConversation(page);
  const composer = frame.getByRole("textbox", { name: "Message" });
  await composer.fill(prompt);
  await composer.press("Enter");
  return frame;
}

test("the exact packaged three-minute story works without terminal intervention", async ({ page }) => {
  const browserErrors = [];
  page.on("pageerror", error => browserErrors.push(error.message));

  await page.goto("/#access=ppp-local");
  await expect(page.getByRole("button", { name: "Open product conversation" })).toBeVisible();
  expect(page.url()).not.toContain("#access=");
  await expect(page.locator("body")).toHaveText("");

  let frame = await openConversation(page);
  const selector = frame.getByRole("combobox", { name: "Current session" });
  const originalSession = await selector.inputValue();
  await expect(frame.getByRole("textbox", { name: "Message" })).toBeEditable();

  await sendTurn(page, promptA);
  await expect(frame.getByText("The conversation is now a compact floating workspace."))
    .toBeVisible();
  const floatingBox = await frame
    .getByRole("complementary", { name: "Product conversation" })
    .boundingBox();
  expect(floatingBox.x).toBeGreaterThan(0);
  expect(floatingBox.y).toBeGreaterThan(0);
  expect(floatingBox.width).toBeLessThan(500);

  await sendTurn(page, promptB);
  await expect(frame.getByText(
    "The gallery, submission flow, voting actions, and persistent leaderboard are running."
  )).toBeVisible();
  await closeConversation(page);
  await expect(frame.getByRole("heading", {
    name: "Products you can try, judge, and improve."
  })).toBeVisible();
  await expect(frame.getByRole("button", { name: "Vote" })).toHaveCount(6);

  await frame.getByRole("button", { name: "Submit" }).click();
  await frame.getByRole("textbox", { name: "Project name" }).fill("Northstar");
  await frame.getByRole("textbox", { name: "What it does" }).fill(
    "Keeps a product team aligned around one observable outcome."
  );
  await frame.getByRole("button", { name: "Submit project" }).click();
  await expect(frame.getByRole("heading", { name: "Northstar" })).toBeVisible();
  await expect(frame.getByRole("button", { name: "Vote" })).toHaveCount(7);

  const voteAs = frame.getByRole("combobox", { name: "Vote as" });
  await voteAs.selectOption("public");
  await frame.getByRole("button", { name: "Vote" }).first().click();
  await expect(frame.getByText("1 points").first()).toBeVisible();
  await voteAs.selectOption("judge");
  await frame.getByRole("button", { name: "Vote" }).nth(1).click();
  await expect(frame.getByText("1 points").nth(1)).toBeVisible();

  await page.reload();
  frame = productFrame(page);
  await expect(frame.getByRole("heading", { name: "Northstar" })).toBeVisible();
  await expect(frame.getByRole("button", { name: "Vote" })).toHaveCount(7);
  await expect(frame.getByText("1 points")).toHaveCount(2);

  await sendTurn(page, promptC);
  await expect(frame.getByText(/Judge votes now count for three points/)).toBeVisible();
  await closeConversation(page);
  await frame.getByRole("button", { name: "Leaderboard" }).click();
  await expect(frame.getByText("Judge 3 points, public 1 point")).toBeVisible();
  await expect(frame.getByText("#1", { exact: true })).toBeVisible();
  await expect(frame.getByText("#2", { exact: true })).toBeVisible();
  await expect(frame.getByText("#3", { exact: true })).toBeVisible();
  await expect(frame.getByText("3 points").first()).toBeVisible();

  frame = await openConversation(page);
  const sessionsBefore = await selector.locator("option").count();
  await frame.getByRole("button", { name: "New session" }).click();
  await expect(selector.locator("option")).toHaveCount(sessionsBefore + 1);
  await expect(selector).not.toHaveValue(originalSession);
  await expect(frame.getByRole("button", { name: "Blank canvas" })).toBeVisible();
  await selector.selectOption(originalSession);
  await expect(frame.getByRole("heading", {
    name: "Products you can try, judge, and improve."
  })).toBeVisible();
  await expect(frame.getByRole("heading", { name: "Northstar" })).toBeVisible();

  await expect(frame.getByRole("region", { name: "Checkpoints" })).toBeVisible();
  await frame.getByRole("button", {
    name: "Create the product gallery and voting workflow"
  }).click();
  await expect(frame.getByText("Checkpoint 2 is restored as a new version.")).toBeVisible();
  await closeConversation(page);
  await frame.getByRole("button", { name: "Leaderboard" }).click();
  await expect(frame.getByText("Judge 1 points, public 1 point")).toBeVisible();
  await expect(frame.getByText("#1", { exact: true })).toHaveCount(0);
  await expect(frame.getByRole("heading", { name: "Northstar" })).toHaveCount(0);

  frame = await openConversation(page);
  await frame.getByRole("button", {
    name: "Weight judge votes and show the top three"
  }).click();
  await expect(frame.getByText("Checkpoint 3 is restored as a new version.")).toBeVisible();
  await closeConversation(page);
  await frame.getByRole("button", { name: "Leaderboard" }).click();
  await expect(frame.getByText("Judge 3 points, public 1 point")).toBeVisible();
  await expect(frame.getByRole("heading", { name: "Northstar" })).toBeVisible();
  await expect(frame.getByText("#1", { exact: true })).toBeVisible();
  await expect(frame.getByText("#2", { exact: true })).toBeVisible();
  await expect(frame.getByText("#3", { exact: true })).toBeVisible();

  await page.keyboard.press("Control+Alt+Shift+P");
  await expect(page.getByRole("complementary", { name: "Safe Mode" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Exit Safe Mode" })).toBeEnabled();
  await page.getByRole("button", { name: "Exit Safe Mode" }).click();
  await expect(page.getByRole("complementary", { name: "Safe Mode" })).toHaveCount(0);

  expect(browserErrors).toEqual([]);
  await expect(page.locator("iframe[data-ppp-runtime-frame]")).toHaveCount(1);
});
