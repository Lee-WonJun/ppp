import { test, expect } from "@playwright/test";
import {
  createFreshSession,
  openConversation,
  productFrame,
  sendTurn
} from "./support.mjs";

const ownerRequest = "이 게임에 로그인, 회원가입을 구현해줘";

async function delayOneFreshFrame(page, enabled) {
  let frameBundleRequests = 0;
  await page.route("**/frame-js/frame.js*", async route => {
    frameBundleRequests += 1;
    if (enabled && frameBundleRequests === 2) {
      await new Promise(resolve => setTimeout(resolve, 6_500));
    }
    await route.continue();
  });
  return () => frameBundleRequests;
}

async function installAccountProduct(page, delayFrameBundle) {
  const frameBundleRequests = await delayOneFreshFrame(page, delayFrameBundle);
  await page.goto("/?test-runtime=1#access=ppp-local");
  const conversation = await openConversation(page);
  const sessionId = await createFreshSession(page, conversation);

  await sendTurn(page, ownerRequest, conversation);
  await expect(conversation.getByText("Checkpoint 1")).toBeVisible({
    timeout: 30_000
  });
  await expect(
    conversation.getByText(/Signup, sign-in, sign-out/i)
  ).toBeVisible();

  await page.getByRole("button", {
    name: "Close product conversation"
  }).click();
  const product = productFrame(page);
  await expect(
    product.getByRole("main", { name: "Account playground" })
  ).toBeVisible();
  await expect(
    product.getByRole("heading", { name: "Create your account" })
  ).toBeVisible();

  if (delayFrameBundle) {
    expect(frameBundleRequests()).toBeGreaterThanOrEqual(2);
  }
  return sessionId;
}

async function verifyActiveFrameDiagnostics(page) {
  const product = productFrame(page);
  await product.getByRole("textbox", { name: "Display name" }).fill("Owner Test");
  await product.getByRole("textbox", { name: "Sign-in ID" }).fill("x");
  await product.getByLabel("Password").fill("correct horse battery");
  await product.getByRole("button", { name: "Create account" }).click();
  await expect(product.getByRole("alert")).toHaveText(
    "Use a valid sign-in identifier."
  );

  await page.evaluate(() => {
    window.dispatchEvent(new ErrorEvent("error", {
      message: "METAMASK_PARENT_SENTINEL"
    }));
  });

  await product.locator("body").evaluate(async () => {
    console.warn("FRAME_CONSOLE_SENTINEL");
    window.dispatchEvent(new ErrorEvent("error", {
      message: "FRAME_RUNTIME_SENTINEL"
    }));
    window.dispatchEvent(new PromiseRejectionEvent("unhandledrejection", {
      promise: Promise.resolve(),
      reason: new Error("FRAME_PROMISE_SENTINEL")
    }));
    await fetch("/missing-client-diagnostic").catch(() => null);
  });

  await expect.poll(async () => {
    const snapshot = await page.evaluate(() => window.__PPP_TEST__.snapshot());
    return snapshot["client-diagnostics"];
  }).toContainEqual({
    kind: "action",
    "action-id": "auth/register",
    code: "auth/identifier-invalid",
    status: 400,
    message: "Use a valid sign-in identifier."
  });

  const snapshot = await page.evaluate(() => window.__PPP_TEST__.snapshot());
  const diagnostics = snapshot["client-diagnostics"];
  expect(diagnostics).toContainEqual({
    kind: "console",
    level: "warn",
    message: "FRAME_CONSOLE_SENTINEL"
  });
  expect(diagnostics).toContainEqual({
    kind: "runtime",
    code: "runtime/window-error",
    message: "FRAME_RUNTIME_SENTINEL"
  });
  expect(diagnostics).toContainEqual({
    kind: "runtime",
    code: "runtime/unhandled-rejection",
    message: "FRAME_PROMISE_SENTINEL"
  });
  expect(diagnostics.some(item =>
    item.kind === "network"
      && item.method === "GET"
      && item.url.endsWith("/missing-client-diagnostic")
  )).toBe(true);
  expect(JSON.stringify(diagnostics)).not.toContain("METAMASK_PARENT_SENTINEL");
}

async function signUpAndVerifyPersistence(page, suffix) {
  const product = productFrame(page);
  const identifier = `owner-${suffix}`;

  await product.getByRole("textbox", { name: "Display name" }).fill("Owner Test");
  await product.getByRole("textbox", { name: "Sign-in ID" }).fill(identifier);
  await product.getByLabel("Password").fill("correct horse battery");
  await product.getByRole("button", { name: "Create account" }).click();

  await expect(product.getByText("Hello, Owner Test")).toBeVisible();
  await expect(product.getByLabel("Points")).toHaveText("0 points");

  const productCookies = (await page.context().cookies()).filter(
    cookie => cookie.name.startsWith("ppp_product_")
  );
  expect(productCookies).toHaveLength(1);
  expect(productCookies[0].httpOnly).toBe(true);
  expect(productCookies[0].sameSite).toBe("Strict");
  expect(productCookies[0].path).toMatch(/\/api\/sessions\/[0-9a-f-]+\/actions$/);

  const frameCookieAccess = await product.locator("body").evaluate(() => {
    try {
      return document.cookie;
    } catch (_error) {
      return "opaque-origin-blocked";
    }
  });
  expect(frameCookieAccess).toBe("opaque-origin-blocked");

  await product.getByRole("button", { name: "Earn a point" }).click();
  await expect(product.getByLabel("Points")).toHaveText("1 points");

  await page.reload();
  await expect(
    productFrame(page).getByRole("main", { name: "Account playground" })
  ).toBeVisible();
  await expect(productFrame(page).getByText("Hello, Owner Test")).toBeVisible();
  await expect(productFrame(page).getByLabel("Points")).toHaveText("1 points");

  await productFrame(page).getByRole("button", { name: "Sign out" }).click();
  await expect(
    productFrame(page).getByRole("heading", { name: "Create your account" })
  ).toBeVisible();
  await expect.poll(async () =>
    (await page.context().cookies()).some(
      cookie => cookie.name.startsWith("ppp_product_")
    )
  ).toBe(false);

  await productFrame(page).getByRole("button", {
    name: "I already have an account"
  }).click();
  await productFrame(page).getByRole("textbox", { name: "Sign-in ID" }).fill(identifier);
  await productFrame(page).getByLabel("Password").fill("wrong password");
  await productFrame(page).getByRole("button", { name: "Sign in" }).click();
  await expect(productFrame(page).getByRole("alert")).toHaveText(
    "Those sign-in details did not match."
  );

  await productFrame(page).getByLabel("Password").fill("correct horse battery");
  await productFrame(page).getByRole("button", { name: "Sign in" }).click();
  await expect(productFrame(page).getByText("Hello, Owner Test")).toBeVisible();
  await expect(productFrame(page).getByLabel("Points")).toHaveText("1 points");
}

for (const scenario of [
  { name: "fresh context one", delayFrameBundle: false },
  { name: "fresh context two", delayFrameBundle: false },
  { name: "delayed fresh frame", delayFrameBundle: true }
]) {
  test(`generated accounts work end to end - ${scenario.name}`,
    async ({ page, browser }) => {
      test.setTimeout(75_000);
      const sessionId = await installAccountProduct(
        page,
        scenario.delayFrameBundle
      );
      if (scenario.name === "fresh context one") {
        await verifyActiveFrameDiagnostics(page);
      }
      await signUpAndVerifyPersistence(
        page,
        scenario.name.replaceAll(" ", "-")
      );

      if (scenario.name === "fresh context one") {
        const isolated = await browser.newContext({
          baseURL: process.env.PPP_E2E_BASE_URL || "http://127.0.0.1:8797"
        });
        try {
          const isolatedPage = await isolated.newPage();
          await isolatedPage.goto(`/?session=${sessionId}#access=ppp-local`);
          await expect(
            productFrame(isolatedPage).getByRole("heading", {
              name: "Create your account"
            })
          ).toBeVisible();
          await expect(
            productFrame(isolatedPage).getByText("Hello, Owner Test")
          ).toHaveCount(0);
        } finally {
          await isolated.close();
        }
      }
    });
}
