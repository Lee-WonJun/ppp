import { test, expect } from "@playwright/test";
import {
  createFreshSession,
  openConversation,
  productFrame
} from "./support.mjs";

async function verifyComposer(page, delayFrameBundle) {
  if (delayFrameBundle) {
    await page.route("**/frame-js/frame.js*", async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 6_500));
      await route.continue();
    });
  }

  const turnRequests = [];
  page.on("request", (request) => {
    if (request.method() === "POST" && /\/turns$/.test(request.url())) {
      turnRequests.push(request);
    }
  });

  await page.goto("/#access=ppp-local");
  const frame = await openConversation(page);
  await createFreshSession(page, frame);
  const composer = frame.getByRole("textbox", { name: "Message" });

  await composer.fill("hello");
  await composer.press("Shift+Enter");
  await expect(composer).toHaveValue("hello\n");
  expect(turnRequests).toHaveLength(0);

  await composer.fill("hello");
  await composer.press("Enter");

  await expect.poll(() => turnRequests.length).toBe(1);
  await expect(frame.getByText("hello", { exact: true })).toBeVisible();
  await expect(composer).toHaveValue("");
  await expect(frame.getByRole("article")).toHaveCount(2);
  await expect(composer).toBeEnabled();

  await composer.fill("");
  await composer.press("Enter");
  await page.waitForTimeout(100);
  expect(turnRequests).toHaveLength(1);

  await expect(page.locator("iframe[data-ppp-runtime-frame]")).toHaveCount(1);
  await expect(productFrame(page).getByRole("textbox", { name: "Message" })).toBeVisible();
}

for (const scenario of [
  { name: "fresh context one", delayFrameBundle: false },
  { name: "fresh context two", delayFrameBundle: false },
  { name: "delayed cold frame", delayFrameBundle: true }
]) {
  test(`Enter sends once and Shift+Enter keeps a multiline draft — ${scenario.name}`,
    async ({ page }) => {
      await verifyComposer(page, scenario.delayFrameBundle);
    });
}
