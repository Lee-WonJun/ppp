import { test, expect } from "@playwright/test";

function productFrame(page) {
  return page.frameLocator(
    "iframe[data-ppp-runtime-frame]:not(.ppp-runtime-frame-staged)"
  );
}

async function snapshot(page) {
  return page.evaluate(() => window.__PPP_TEST__?.snapshot());
}

test("real Codex creates a live automatically falling Tetris product", async ({ page }) => {
  test.setTimeout(360_000);
  const accessCode = process.env.PPP_LIVE_ACCESS_CODE || "ppp-local";
  const reuseSessionId = process.env.PPP_LIVE_SESSION_ID;

  await page.goto(`/?test-runtime=1#access=${encodeURIComponent(accessCode)}`);
  await expect(page.getByRole("button", { name: "Open product conversation" })).toBeVisible();
  if (reuseSessionId) {
    await page.goto(`/?test-runtime=1&session=${encodeURIComponent(reuseSessionId)}`);
    await expect.poll(async () => (await snapshot(page))?.version, {
      timeout: 30_000
    }).toBeGreaterThan(0);
  } else {
    await page.getByRole("button", { name: "Open product conversation" }).click();
    await expect(productFrame(page).getByRole("complementary", {
      name: "Product conversation"
    })).toBeVisible();

    const selector = productFrame(page).getByRole("combobox", { name: "Current session" });
    const previousSession = await selector.inputValue();
    await productFrame(page).getByRole("button", { name: "New session" }).click();
    await expect.poll(() => selector.inputValue()).not.toBe(previousSession);

    const before = await snapshot(page);
    const composer = productFrame(page).getByRole("textbox", { name: "Message" });
    await composer.fill(
      "테트리스 게임을 구현해줘. 블록은 타이머로 자동 낙하하고, 방향키로 좌우 이동과 아래 이동이 되어야 해. 실제 플레이 화면을 만들어줘."
    );
    await productFrame(page).getByRole("button", { name: "Send" }).click();

    await expect.poll(async () => {
      const current = await snapshot(page);
      return current?.progress === null && current?.messages.length >= before.messages.length + 2;
    }, { timeout: 320_000 }).toBe(true);
  }

  const after = await snapshot(page);
  expect(after.version, JSON.stringify(after["debug-error"])).toBe(1);
  expect(after["debug-error"] ?? null).toBeNull();

  const runtime = await page.evaluate(async sessionId => {
    const response = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/runtime`, {
      credentials: "same-origin"
    });
    if (!response.ok) throw new Error(`Runtime request failed: ${response.status}`);
    return response.json();
  }, after["session-id"]);
  const clientSource = runtime.files
    .filter(file => file.path.startsWith("src/client/"))
    .map(file => file.content)
    .join("\n");

  expect(clientSource).toMatch(/start-interval!|setInterval|requestAnimationFrame|setTimeout/);
  expect(clientSource).toMatch(/keydown|ArrowLeft|ArrowRight/);

  const closeConversation = page.getByRole("button", { name: "Close product conversation" });
  if (await closeConversation.count()) await closeConversation.click();
  const body = productFrame(page).locator("body");
  const firstFrame = await body.screenshot();
  await page.waitForTimeout(900);
  const secondFrame = await body.screenshot();
  expect(secondFrame.equals(firstFrame)).toBe(false);
  await page.screenshot({ path: "artifacts/evidence/live-codex-tetris.png" });
});
