import { test, expect } from "@playwright/test";

function productFrame(page) {
  return page.frameLocator(
    "iframe[data-ppp-runtime-frame]:not(.ppp-runtime-frame-staged)"
  );
}

function rgbLightness(value) {
  const channels = value.match(/[\d.]+/g)?.slice(0, 3).map(Number) || [];
  return channels.length === 3
    ? (Math.max(...channels) + Math.min(...channels)) / 2
    : 255;
}

async function snapshot(page) {
  return page.evaluate(() => window.__PPP_TEST__.snapshot());
}

test("real Codex applies a dark theme through browser staging", async ({ page }) => {
  const browserErrors = [];
  page.on("pageerror", error => browserErrors.push(error.message));

  const accessCode = process.env.PPP_LIVE_ACCESS_CODE || "ppp-live-smoke";
  await page.goto(`/?test-runtime=1#access=${encodeURIComponent(accessCode)}`);
  await page.getByRole("button", { name: "Open product conversation" }).click();
  await expect(productFrame(page).getByRole("complementary", {
    name: "Product conversation"
  })).toBeVisible();
  await expect.poll(async () => (await snapshot(page)).version).toBe(0);

  const before = await snapshot(page);
  const composer = productFrame(page).getByRole("textbox", { name: "Message" });
  await composer.fill(
    "다크테마로 바꿔줘. 사이드바와 빈 캔버스를 모두 어둡게 만들고, 세션 선택·대화·체크포인트·입력 기능은 그대로 유지해."
  );
  await productFrame(page).getByRole("button", { name: "Send" }).click();

  await expect.poll(async () => {
    const current = await snapshot(page);
    return current.progress === null && current.messages.length >= before.messages.length + 2;
  }, { timeout: 200_000 }).toBe(true);

  const after = await snapshot(page);
  expect(after.version, JSON.stringify(after.debugError)).toBe(1);
  expect(after.debugError ?? null).toBeNull();
  expect(browserErrors).toEqual([]);

  const sidebar = productFrame(page).getByRole("complementary", {
    name: "Product conversation"
  });
  await expect(sidebar).toBeVisible();
  await expect(productFrame(page).getByRole("combobox", {
    name: "Current session"
  })).toBeVisible();
  await expect(productFrame(page).getByRole("textbox", { name: "Message" })).toBeVisible();

  const sidebarColor = await sidebar.evaluate(node => getComputedStyle(node).backgroundColor);
  expect(rgbLightness(sidebarColor), sidebarColor).toBeLessThan(160);
  await page.screenshot({ path: "artifacts/evidence/live-dark-theme.png" });
});
