import { test, expect } from "@playwright/test";
import {
  activeFrameChannel,
  openConversation,
  productFrame
} from "./support.mjs";

test("access link opens a literal blank workspace and persistent sessions", async ({ page }) => {
  await page.goto("/#access=ppp-local");

  const openConversationButton = page.getByRole("button", {
    name: "Open product conversation"
  });
  await expect(openConversationButton).toBeVisible();
  expect(page.url()).not.toContain("#access=");
  await expect(page.locator(".ppp-fallback-sidebar")).toHaveCount(0);
  await expect(page.locator("body")).toHaveText("");
  await page.screenshot({ path: "artifacts/evidence/ppp-002-blank.png" });

  const frame = await openConversation(page);
  await expect(frame.getByRole("combobox", { name: "Current session" })).toBeVisible();
  const message = frame.getByRole("textbox", { name: "Message" });
  await expect(message).toBeVisible();
  await message.click();
  await message.pressSequentially("abc", { delay: 50 });
  await expect(message).toHaveValue("abc");
  await expect(message).toBeFocused();
  await message.fill("");
  await page.screenshot({ path: "artifacts/evidence/ppp-002-sidebar.png" });

  const selector = frame.getByRole("combobox", { name: "Current session" });
  const before = await selector.locator("option").count();
  const channelBeforeSessionSwitch = await activeFrameChannel(page);
  await frame.getByRole("button", { name: "New session" }).click();
  await expect(selector.locator("option")).toHaveCount(before + 1);
  const selected = await selector.inputValue();
  await expect.poll(() => activeFrameChannel(page)).not.toBe(channelBeforeSessionSwitch);
  await expect(page.locator("iframe[data-ppp-runtime-frame]")).toHaveCount(1);

  await page.reload();
  await openConversation(page);
  await expect(
    productFrame(page).getByRole("combobox", { name: "Current session" })
  ).toHaveValue(selected);
});
