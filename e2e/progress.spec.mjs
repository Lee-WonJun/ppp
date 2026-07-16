import { test, expect } from "@playwright/test";
import {
  createFreshSession,
  openConversation,
  productFrame
} from "./support.mjs";

const phases = [
  ["generating", "Generating. Thinking through your request"],
  ["validating", "Validating. Checking the proposed product"],
  ["applying", "Applying. Updating the live product"],
  ["applied", "Applied. Your product is ready"]
];

async function setProgress(page, phase) {
  await page.evaluate((value) => window.__PPP_TEST__.setProgress(value), phase);
}

async function progressDecoration(status) {
  return status.evaluate((element) => {
    const decoration = [...element.querySelectorAll('[aria-hidden="true"]')]
      .find((candidate) => candidate.textContent === "...");
    if (!decoration) {
      throw new Error("The visual progress ellipsis is missing");
    }

    const candidates = [decoration, ...decoration.querySelectorAll("*")];
    const animated = candidates.find(
      (candidate) => getComputedStyle(candidate).animationName !== "none"
    );
    const walker = document.createTreeWalker(decoration, NodeFilter.SHOW_TEXT);
    const textNode = walker.nextNode();
    const textRange = document.createRange();
    textRange.selectNodeContents(textNode);

    return {
      animationName: animated
        ? getComputedStyle(animated).animationName
        : "none",
      animatedWidth: animated ? animated.getBoundingClientRect().width : null,
      ariaHidden: decoration.getAttribute("aria-hidden"),
      text: decoration.textContent,
      textWidth: textRange.getBoundingClientRect().width
    };
  });
}

test("live work status follows real phases in one accessible animated line", async ({ page }) => {
  await page.goto("/?test-runtime=1#access=ppp-local");
  const frame = await openConversation(page);
  await createFreshSession(page, frame);

  await page.route(/\/api\/sessions\/[^/]+\/turns$/, async (route) => {
    await route.fulfill({
      status: 202,
      contentType: "application/json",
      body: JSON.stringify({ jobId: "test-job", requestId: "test-request" })
    });
  });

  const composer = productFrame(page).getByRole("textbox", { name: "Message" });
  await composer.fill("Make the workspace calmer");
  await composer.press("Enter");

  const generating = productFrame(page).getByRole("status", {
    name: phases[0][1]
  });
  await expect(generating).toBeVisible();
  await expect(generating).toContainText(
    "Generating... · Thinking through your request"
  );

  const lineLayout = await generating.evaluate((element) => {
    const style = getComputedStyle(element);
    return {
      clientHeight: element.clientHeight,
      height: element.getBoundingClientRect().height,
      overflow: style.overflow,
      scrollHeight: element.scrollHeight,
      whiteSpace: style.whiteSpace
    };
  });
  expect(lineLayout.whiteSpace).toBe("nowrap");
  expect(lineLayout.overflow).toBe("hidden");
  expect(lineLayout.height).toBeGreaterThan(0);
  expect(lineLayout.scrollHeight).toBeLessThanOrEqual(lineLayout.clientHeight + 1);

  const decoration = await progressDecoration(generating);
  expect(decoration.ariaHidden).toBe("true");
  expect(decoration.text).toBe("...");
  expect(decoration.animationName).toBe("runtime-progress-dots");

  const sampledWidths = [];
  for (let index = 0; index < 16; index += 1) {
    sampledWidths.push(Math.round((await progressDecoration(generating)).animatedWidth));
    await page.waitForTimeout(90);
  }
  expect(new Set(sampledWidths).size).toBeGreaterThanOrEqual(3);
  expect(Math.min(...sampledWidths)).toBeLessThan(Math.max(...sampledWidths));

  for (const [phase, accessibleName] of phases.slice(1)) {
    await setProgress(page, phase);
    await expect(productFrame(page).getByRole("status", { name: accessibleName }))
      .toBeVisible();
  }

  await setProgress(page, "unknown-phase");
  await expect(productFrame(page).getByRole("status", {
    name: "Working. Keeping your product moving"
  })).toBeVisible();

  await setProgress(page, "applying");
  await page.evaluate(() => window.__PPP_TEST__.simulateRuntimeFailure());
  const fallbackStatus = page.getByRole("status", {
    name: "Applying. Updating the live product"
  });
  await expect(fallbackStatus).toBeVisible();
  await expect(fallbackStatus).toContainText(
    "Applying... · Updating the live product"
  );
});

test("reduced motion keeps a static ellipsis and the same semantic status", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/?test-runtime=1#access=ppp-local");
  const frame = await openConversation(page);
  await createFreshSession(page, frame);
  await setProgress(page, "generating");

  const status = productFrame(page).getByRole("status", {
    name: "Generating. Thinking through your request"
  });
  await expect(status).toBeVisible();
  const decoration = await progressDecoration(status);
  expect(decoration.ariaHidden).toBe("true");
  expect(decoration.text).toBe("...");
  expect(decoration.animationName).toBe("none");
  expect(decoration.textWidth).toBeGreaterThan(0);
});
