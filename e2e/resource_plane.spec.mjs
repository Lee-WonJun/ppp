import { test, expect } from "@playwright/test";
import {
  createFreshSession,
  openConversation,
  productFrame,
  sendTurn
} from "./support.mjs";

async function closeConversation(page) {
  const button = page.getByRole("button", { name: "Close product conversation" });
  if (await button.count()) await button.click();
}

async function upload(page, name, content) {
  await productFrame(page).getByLabel("Upload object").setInputFiles({
    name,
    mimeType: "text/plain",
    buffer: Buffer.from(content, "utf8")
  });
  await expect(productFrame(page).getByRole("region", { name: "Stored objects" }))
    .toContainText(name);
}

async function search(page, query) {
  await productFrame(page).getByLabel("Search objects").fill(query);
  await productFrame(page).getByRole("button", { name: "Search", exact: true }).click();
}

test("the resource workbench persists, broadcasts, schedules, accepts public input, and restores atomically", async ({ page, context, request }) => {
  test.setTimeout(120_000);

  await page.goto("/?test-runtime=1#access=ppp-local");
  await openConversation(page);
  const sessionId = await createFreshSession(page);

  await sendTurn(page, "[[fake:resource-workbench]]");
  await expect(productFrame(page).getByText(
    "Durable objects, search, background work, public input, and live updates are running in this product."
  )).toBeVisible({ timeout: 25_000 });
  await closeConversation(page);
  await expect(productFrame(page).getByRole("main", { name: "Resource workbench" }))
    .toBeVisible();

  const follower = await context.newPage();
  await follower.goto(`/?test-runtime=1&session=${encodeURIComponent(sessionId)}`);
  await expect.poll(
    () => follower.evaluate(() => window.__PPP_TEST__?.snapshot().version)
  ).toBe(1);
  await expect(productFrame(follower).getByRole("main", { name: "Resource workbench" }))
    .toBeVisible();

  await upload(page, "alpha-한글.txt", "첫 번째 durable object");
  await expect(productFrame(follower).getByRole("region", { name: "Stored objects" }))
    .toContainText("alpha-한글.txt");
  await expect.poll(async () => Number(
    await productFrame(follower).getByLabel("Live event count").textContent()
  )).toBeGreaterThan(0);

  await productFrame(page).getByLabel("Upload object").setInputFiles({
    name: "too-large.bin",
    mimeType: "application/octet-stream",
    buffer: Buffer.alloc((4 * 1024 * 1024) + 1, 7)
  });
  await expect(productFrame(page).getByLabel("Resource status"))
    .toHaveText("That object is larger than this workspace allows.", { timeout: 20_000 });
  await expect(productFrame(page).getByRole("region", { name: "Stored objects" }))
    .not.toContainText("too-large.bin");
  await expect(productFrame(page).getByRole("region", { name: "Stored objects" }))
    .toContainText("alpha-한글.txt");

  await page.reload();
  await expect(productFrame(page).getByRole("region", { name: "Stored objects" }))
    .toContainText("alpha-한글.txt");
  await search(page, "한글");
  await expect(productFrame(page).getByRole("region", { name: "Search results" }))
    .toContainText("alpha-한글.txt");

  await productFrame(page).getByRole("region", { name: "Stored objects" })
    .getByText("alpha-한글.txt")
    .locator("xpath=ancestor::article")
    .getByRole("button", { name: "Process in background" })
    .click();
  await expect(productFrame(page).getByLabel("Resource status"))
    .toHaveText(/processed|completed/i, { timeout: 20_000 });

  const ingress = await request.post(
    `/public/sessions/${encodeURIComponent(sessionId)}/resource-import`,
    { data: { title: "브라우저 공개 입력" } }
  );
  expect(ingress.status()).toBe(202);
  expect(await ingress.json()).toMatchObject({ accepted: true });
  await expect(productFrame(follower).getByLabel("Resource status"))
    .toHaveText(/imported/i, { timeout: 15_000 });
  await search(page, "incoming");
  await expect(productFrame(page).getByRole("region", { name: "Search results" }))
    .toContainText("incoming webhook");

  await upload(page, "beta-예약.txt", "체크포인트 안에서 대기할 작업");
  await productFrame(page).getByRole("region", { name: "Stored objects" })
    .getByText("beta-예약.txt")
    .locator("xpath=ancestor::article")
    .getByRole("button", { name: "Process later" })
    .click();
  await expect(productFrame(page).getByLabel("Resource status"))
    .toHaveText(/pending|queued/i, { timeout: 10_000 });

  await openConversation(page);
  await sendTurn(page, "[[fake:resource-checkpoint]]");
  await expect(productFrame(page).getByText(
    "The current resource workspace is preserved as a checkpoint."
  )).toBeVisible({ timeout: 25_000 });
  await closeConversation(page);

  await upload(page, "gamma-after-checkpoint.txt", "복구하면 사라져야 하는 객체");
  await openConversation(page);
  await productFrame(page).getByRole("button", {
    name: "Preserve the resource workbench"
  }).click();
  await expect(productFrame(page).getByText("Checkpoint 2 is restored as a new version."))
    .toBeVisible({ timeout: 25_000 });
  await closeConversation(page);

  const objects = productFrame(page).getByRole("region", { name: "Stored objects" });
  await expect(objects).toContainText("alpha-한글.txt");
  await expect(objects).toContainText("beta-예약.txt");
  await expect(objects).not.toContainText("gamma-after-checkpoint.txt");
  await expect(productFrame(page).getByLabel("Resource status"))
    .toHaveText(/cancelled/i, { timeout: 15_000 });

  await page.reload();
  await expect(productFrame(page).getByRole("region", { name: "Stored objects" }))
    .toContainText("alpha-한글.txt");
  await expect(productFrame(page).getByRole("region", { name: "Stored objects" }))
    .toContainText("beta-예약.txt");
  await expect(productFrame(page).getByRole("region", { name: "Stored objects" }))
    .not.toContainText("gamma-after-checkpoint.txt");

  await follower.close();
});
