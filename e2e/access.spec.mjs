import { test, expect } from "@playwright/test";
import {
  createProject,
  openConversation,
  productFrame,
  sendTurn,
  signIn
} from "./support.mjs";

const baseURL = process.env.PPP_E2E_BASE_URL || "http://127.0.0.1:8797";
const sharedPassword = process.env.PPP_E2E_PASSWORD || "ppp-local";

test("one shared password opens the common Projects workspace", async ({ page, browser }) => {
  await page.goto("/");
  const signInView = page.getByRole("region", { name: "Workspace sign in" });
  await expect(signInView).toBeVisible();
  await signInView.getByLabel("Password").fill("wrong password");
  await signInView.getByRole("button", { name: "Continue" }).click();
  await expect(signInView.getByRole("alert")).toBeVisible();

  await signInView.getByLabel("Password").fill(sharedPassword);
  await signInView.getByRole("button", { name: "Continue" }).click();

  const projects = page.getByRole("region", { name: "Projects" });
  await expect(projects).toBeVisible();
  await expect(projects.getByRole("heading", { name: "No projects yet" }))
    .toBeVisible();
  expect(new URL(page.url()).searchParams.has("session")).toBe(false);
  await expect(page.getByRole("button", { name: "Open product conversation" }))
    .toHaveCount(0);

  const title = "Shared judge gallery";
  const sessionId = await createProject(page, title);
  await expect(productFrame(page).getByRole("main", {
    name: "Blank product canvas"
  })).toBeAttached();
  await expect(productFrame(page).getByRole("main", {
    name: "Blank product canvas"
  })).toHaveText("");
  await page.screenshot({ path: "artifacts/evidence/ppp-022-blank-project.png" });

  const frame = await openConversation(page);
  const message = frame.getByRole("textbox", { name: "Message" });
  await message.click();
  await message.pressSequentially("abc", { delay: 50 });
  await expect(message).toHaveValue("abc");
  await expect(message).toBeFocused();
  await message.fill("");
  await page.screenshot({ path: "artifacts/evidence/ppp-022-conversation.png" });

  const isolated = await browser.newContext({ baseURL });
  try {
    const secondJudge = await isolated.newPage();
    await signIn(secondJudge);
    const sharedRow = secondJudge.getByRole("article").filter({ hasText: title });
    await expect(sharedRow).toBeVisible();
    await sharedRow.getByRole("button", { name: "Open" }).click();
    await expect(secondJudge).toHaveURL(new RegExp(`session=${sessionId}`));
    await expect(productFrame(secondJudge).getByRole("main", {
      name: "Blank product canvas"
    })).toBeAttached();
  } finally {
    await isolated.close();
  }

  await frame.getByRole("button", { name: "All projects" }).click();
  await expect(page.getByRole("region", { name: "Projects" })).toContainText(title);
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByRole("region", { name: "Workspace sign in" })).toBeVisible();
  expect(new URL(page.url()).searchParams.has("session")).toBe(false);
});

test("development access fragment is exchanged once and removed", async ({ page }) => {
  test.skip(process.env.PPP_E2E_APP_ENV === "production");
  await page.goto("/#access=ppp-local");
  await expect(page.getByRole("region", { name: "Projects" })).toBeVisible();
  expect(page.url()).not.toContain("#access=");
});

test("capacity exhaustion is visible but does not replace the saved product", async ({ page }) => {
  await page.route("**/api/bootstrap", async route => {
    const response = await route.fetch();
    const body = await response.json();
    body["change-capacity"] = { "available?": false };
    await route.fulfill({ response, json: body });
  });
  await signIn(page, "/?test-runtime=1");
  const projects = page.getByRole("region", { name: "Projects" });
  await expect(projects).toContainText(
    "New changes are temporarily unavailable. Existing projects still work."
  );
  const sessionId = await createProject(page, "Capacity-safe project");
  const frame = await openConversation(page);
  await expect(frame.getByRole("complementary", {
    name: "Product conversation"
  })).toContainText(
    "New changes are temporarily unavailable. Saved products still work."
  );

  await page.route(/\/api\/sessions\/[^/]+\/turns$/, async route => {
    await route.fulfill({
      status: 429,
      headers: { "retry-after": "37" },
      contentType: "application/json",
      body: JSON.stringify({
        error: {
          code: "provider/capacity-exhausted",
          message: "New changes are temporarily unavailable. Try again later."
        }
      })
    });
  });
  await sendTurn(page, "Change the colors", frame);
  await expect(frame.getByText(
    "New changes are temporarily unavailable. Try again later."
  )).toBeVisible();
  await expect(productFrame(page).getByRole("main", {
    name: "Blank product canvas"
  })).toBeAttached();
  expect(new URL(page.url()).searchParams.get("session")).toBe(sessionId);
});
