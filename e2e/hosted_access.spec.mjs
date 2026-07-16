import { test, expect } from "@playwright/test";
import {
  createProject,
  productFrame,
  signIn
} from "./support.mjs";

const phase = process.env.PPP_HOSTED_ACCESS_PHASE;
const title = "Hosted restart proof";
const baseURL = process.env.PPP_E2E_BASE_URL || "http://localhost:8799";

test("production shared access remains common across contexts and restart", async ({
  page,
  browser
}) => {
  test.skip(!phase, "This spec runs only through bb hosted-access-e2e");
  expect(["before-restart", "after-restart"]).toContain(phase);

  if (phase === "before-restart") {
    await page.goto("/#access=fragment-must-not-authenticate");
    await expect(page.getByRole("region", { name: "Workspace sign in" }))
      .toBeVisible();
    expect(page.url()).not.toContain("#access=");

    await signIn(page);
    const cookies = await page.context().cookies();
    const accessCookie = cookies.find(cookie => cookie.name === "ppp_access");
    expect(accessCookie).toMatchObject({
      httpOnly: true,
      secure: true,
      sameSite: "Strict"
    });

    const sessionId = await createProject(page, title);
    await expect(productFrame(page).getByRole("main", {
      name: "Blank product canvas"
    })).toBeAttached();

    const secondContext = await browser.newContext({ baseURL });
    try {
      const secondJudge = await secondContext.newPage();
      await signIn(secondJudge);
      const sharedProject = secondJudge.getByRole("article").filter({
        hasText: title
      });
      await expect(sharedProject).toBeVisible();
      await sharedProject.getByRole("button", { name: "Open" }).click();
      await expect(secondJudge).toHaveURL(new RegExp(`session=${sessionId}`));
      await expect(productFrame(secondJudge).getByRole("main", {
        name: "Blank product canvas"
      })).toBeAttached();
    } finally {
      await secondContext.close();
    }
  } else {
    await signIn(page);
    const persisted = page.getByRole("article").filter({ hasText: title });
    await expect(persisted).toBeVisible();
    await persisted.getByRole("button", { name: "Open" }).click();
    await expect(productFrame(page).getByRole("main", {
      name: "Blank product canvas"
    })).toBeAttached();
    await page.getByRole("button", { name: "Open product conversation" }).click();
    await expect(productFrame(page).getByRole("combobox", {
      name: "Current session"
    })).toHaveValue(/^[0-9a-f-]{36}$/);
  }
});
