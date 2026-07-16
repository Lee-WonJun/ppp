import { expect } from "@playwright/test";

let projectSequence = 0;

export function productFrame(page) {
  return page.frameLocator(
    "iframe[data-ppp-runtime-frame]:not(.ppp-runtime-frame-staged)"
  );
}

export async function activeFrameChannel(page) {
  return page.locator(
    "iframe[data-ppp-runtime-frame]:not(.ppp-runtime-frame-staged)"
  ).getAttribute("data-ppp-runtime-channel");
}

export async function signIn(
  page,
  path = "/",
  password = process.env.PPP_E2E_PASSWORD || "ppp-local"
) {
  await page.goto(path);
  const signIn = page.getByRole("region", { name: "Workspace sign in" });
  await expect(signIn).toBeVisible();
  await signIn.getByLabel("Password").fill(password);
  await signIn.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByRole("region", { name: "Projects" })).toBeVisible();
}

export async function createProject(page, title = null) {
  const projects = page.getByRole("region", { name: "Projects" });
  await expect(projects).toBeVisible();
  const projectTitle = title || `Browser project ${++projectSequence}`;
  await projects.getByRole("button", { name: "New project" }).first().click();
  const form = page.getByRole("form", { name: "Create project" });
  await expect(form).toBeVisible();
  await form.getByLabel("Project name").fill(projectTitle);
  await form.getByRole("button", { name: "Create project" }).click();
  await expect(page.getByRole("button", {
    name: "Open product conversation"
  })).toBeVisible();
  const sessionId = new URL(page.url()).searchParams.get("session");
  expect(sessionId).toMatch(/^[0-9a-f-]{36}$/);
  return sessionId;
}

export async function openConversation(page) {
  await page.locator(
    ".ppp-login-shell, .ppp-projects-shell, .ppp-handle"
  ).first().waitFor({ state: "visible" });
  if (await page.getByRole("region", { name: "Workspace sign in" }).isVisible()) {
    throw new Error("The browser is not signed in to the shared workspace");
  }
  if (await page.getByRole("region", { name: "Projects" }).isVisible()) {
    await createProject(page);
  }
  const button = page.getByRole("button", {
    name: "Open product conversation"
  });
  await expect(button).toBeVisible();
  await button.click();
  await expect(
    productFrame(page).getByRole("complementary", {
      name: "Product conversation"
    })
  ).toBeVisible();
  return productFrame(page);
}

export async function sendTurn(page, prompt, scope = productFrame(page)) {
  const composer = scope.getByRole("textbox", { name: "Message" });
  await composer.fill(prompt);
  await scope.getByRole("button", { name: "Send" }).click();
}

export async function createFreshSession(page, scope = productFrame(page)) {
  if (!(await page.getByRole("region", { name: "Projects" }).isVisible())) {
    await scope.getByRole("button", { name: "New session" }).click();
  }
  await expect(page.getByRole("region", { name: "Projects" })).toBeVisible();
  const sessionId = await createProject(page);
  await openConversation(page);
  return sessionId;
}
