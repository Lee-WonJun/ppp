import { test, expect } from "@playwright/test";
import {
  activeFrameChannel,
  createFreshSession,
  openConversation,
  productFrame
} from "./support.mjs";

function runtimeSource(label, { throwingSidebar = false, hostile = false } = {}) {
  const pageBody = `[:main.live-product {:aria-label "${label}"}
         [:h1 "${label}"]
         [:p {:aria-label "Preserved draft"} (or (:draft @api/page-state) "none")]]`;
  const sidebarBody = throwingSidebar
    ? `(throw (ex-info "hidden sidebar render failure" {}))`
    : `[:aside.live-sidebar {:aria-label "Generated conversation"}
         [:strong "Live workspace"]
         [:p (str (count sessions) " session")]
         [:label "Generated message" [:textarea {:aria-label "Generated message"}]]]`;

  return {
    "src/shared/runtime/domain.cljc": `(ns runtime.domain)\n(def compatible? true)`,
    "src/client/runtime/client.cljs": `
      (ns runtime.client (:require [runtime.api :as api]))
      (defn page [_context] ${pageBody})
      (api/register-page! :home page)`,
    "src/client/runtime/sidebar.cljs": `
      (ns runtime.sidebar (:require [runtime.api :as api]))
      (defn sidebar [{:keys [sessions]}]
        ${sidebarBody})
      (api/register-sidebar! sidebar)`,
    "styles/runtime.css": `
      :host { color: #171714; }
      .live-product { min-height: 100dvh; padding: 72px; background: ${hostile ? "#eef0ff" : "#f4f1e7"}; }
      .live-product h1 { margin: 0; font: 500 64px/1 Georgia, serif; }
      .live-sidebar { pointer-events: auto; position: fixed; inset: 20px 20px 20px auto;
        width: min(420px, calc(100vw - 40px)); padding: 72px 24px 24px; border: 1px solid #d9d7ce;
        border-radius: 18px; background: #fbfaf6; box-shadow: 0 24px 70px rgb(23 23 20 / 16%); }
      .live-sidebar label { display: grid; gap: 6px; margin-top: 24px; }
      .live-sidebar textarea { min-height: 80px; }
      ${hostile ? ".ppp-handle { display: none !important; }" : ""}
      @media (max-width: 640px) { .live-sidebar { inset: 0; width: 100vw; border: 0; border-radius: 0; } }
    `
  };
}

function browserPlatformSource() {
  return {
    "src/shared/runtime/domain.cljc": `(ns runtime.domain)\n(def compatible? true)`,
    "src/client/runtime/client.cljs": `
      (ns runtime.client (:require [runtime.api :as api]))

      (defn parent-document-readable? []
        (try
          (boolean (.-body (.-document js/parent)))
          (catch :default _ false)))

      (defn key-handler [event]
        (when (= "ArrowRight" (.-key event))
          (swap! api/page-state update :keyboard-moves (fnil inc 0))))

      (.addEventListener js/window "keydown" key-handler)

      (def tick-handle
        (js/setInterval
          #(swap! api/page-state update :automatic-ticks (fnil inc 0))
          80))

      (defn canvas-ref! [canvas]
        (when (and canvas (not (:canvas-ready @api/page-state)))
          (let [context (.getContext canvas "2d")]
            (set! (.-fillStyle context) "#00ff66")
            (.fillRect context 0 0 8 8)
            (swap! api/page-state assoc :canvas-ready true))))

      (defn page [_]
        [:main.platform-product {:aria-label "Browser platform product" :tab-index 0}
         [:output {:aria-label "Automatic ticks"}
          (str (or (:automatic-ticks @api/page-state) 0))]
         [:output {:aria-label "Keyboard moves"}
          (str (or (:keyboard-moves @api/page-state) 0))]
         [:output {:aria-label "Parent document readable"}
          (str (parent-document-readable?))]
         [:output {:aria-label "WebAssembly available"}
          (str (some? js/WebAssembly))]
         [:canvas {:aria-label "Programmable canvas"
                   :width 16 :height 16 :ref canvas-ref!}]])

      (api/register-page! :home page)`,
    "src/client/runtime/sidebar.cljs": `
      (ns runtime.sidebar (:require [runtime.api :as api]))
      (api/register-sidebar!
        (fn [_]
          [:aside.live-sidebar {:aria-label "Generated conversation"}
           [:strong "Browser platform workspace"]]))`,
    "styles/runtime.css": `
      :root { color: #f4f7f6; background: #07110d; }
      .platform-product { min-height: 100dvh; display: grid; place-content: center;
        gap: 12px; background: #07110d; color: #f4f7f6; }
      .platform-product output { display: block; }
      .platform-product canvas { width: 160px; height: 160px; border: 1px solid #00ff66; }
      .live-sidebar { position: fixed; inset: 20px 20px 20px auto; width: 360px;
        padding: 72px 24px 24px; background: #0c1d16; color: #f4f7f6; }
      @media (max-width: 640px) { .live-sidebar { inset: 0; width: 100vw; } }
    `
  };
}

async function stageAndActivate(page, version, source) {
  try {
    await page.evaluate(
      async ({ version, source }) => {
        await window.__PPP_TEST__.stage(version, source);
        window.__PPP_TEST__.activate(version);
      },
      { version, source }
    );
  } catch (error) {
    const diagnostic = await page.evaluate(() => window.__PPP_TEST__.snapshot()["debug-error"]);
    throw new Error(`runtime ${version} failed: ${JSON.stringify(diagnostic)}`, { cause: error });
  }
}

async function suppressSandboxAnimationFrames(page, delayFrameBundle = false) {
  let intercepted = 0;
  await page.route("**/frame-js/frame.js*", async route => {
    intercepted += 1;
    if (delayFrameBundle) {
      await new Promise(resolve => setTimeout(resolve, 6_500));
    }
    const response = await route.fetch();
    const headers = { ...response.headers() };
    delete headers["content-length"];
    delete headers["content-encoding"];
    const body = await response.text();
    await route.fulfill({
      status: response.status(),
      headers,
      contentType: "application/javascript",
      body: `self.requestAnimationFrame = function PPP_SUPPRESSED_RAF() { return 0; };\n${body}`
    });
  });
  return () => intercepted;
}

async function verifyAnimationIndependentColdSession(page, delayFrameBundle) {
  const intercepted = await suppressSandboxAnimationFrames(page, delayFrameBundle);

  await page.goto("/?test-runtime=1#access=ppp-local");
  const frame = await openConversation(page);
  await expect.poll(
    () => page.evaluate(() => {
      const snapshot = window.__PPP_TEST__?.snapshot();
      return Number.isInteger(snapshot?.version)
        && snapshot.version === snapshot["saved-version"];
    })
  ).toBe(true);
  expect((await page.evaluate(
    () => window.__PPP_TEST__.snapshot()
  ))["debug-error"]).toBeNull();

  await createFreshSession(page, frame);
  await expect.poll(
    () => page.evaluate(() => window.__PPP_TEST__?.snapshot().version)
  ).toBe(0);
  await expect(frame.getByRole("complementary", {
    name: "Product conversation"
  })).toBeVisible();

  const animationFrameImplementation = await frame.locator("body").evaluate(
    () => String(requestAnimationFrame)
  );
  expect(animationFrameImplementation).toContain("PPP_SUPPRESSED_RAF");

  // Remain on the exact fresh-session product beyond the former host deadline.
  await page.waitForTimeout(10_500);
  const snapshot = await page.evaluate(() => window.__PPP_TEST__.snapshot());
  expect(snapshot.version).toBe(0);
  expect(snapshot["saved-version"]).toBe(0);
  expect(snapshot["debug-error"]).toBeNull();
  await expect(page.locator("iframe[data-ppp-runtime-frame]")).toHaveCount(1);
  expect(intercepted()).toBeGreaterThanOrEqual(2);

  await page.getByRole("button", {
    name: "Close product conversation"
  }).click();
  await expect(frame.getByRole("complementary", {
    name: "Product conversation"
  })).toHaveCount(0);
  await page.getByRole("button", {
    name: "Open product conversation"
  }).click();
  await expect(frame.getByRole("complementary", {
    name: "Product conversation"
  })).toBeVisible();
}

const koreanImeCandidates = [
  "ㄱ", "가", "간", "간ㄷ", "간다", "간단", "간단ㅎ", "간단하", "간단한"
];

async function verifyKoreanImeComposer(page, delayFreshFrame) {
  let frameBundleRequests = 0;
  const turnRequests = [];

  await page.route("**/frame-js/frame.js*", async route => {
    frameBundleRequests += 1;
    if (delayFreshFrame && frameBundleRequests === 2) {
      await new Promise(resolve => setTimeout(resolve, 6_500));
    }
    await route.continue();
  });
  await page.route("**/api/sessions/*/turns", async route => {
    turnRequests.push(route.request().postDataJSON());
    await route.fulfill({
      status: 202,
      contentType: "application/json",
      body: JSON.stringify({ jobId: "ime-job", requestId: "ime-request" })
    });
  });

  await page.goto("/?test-runtime=1#access=ppp-local");
  const frame = await openConversation(page);
  await createFreshSession(page, frame);
  await expect.poll(
    () => page.evaluate(() => window.__PPP_TEST__?.snapshot().version)
  ).toBe(0);

  const composer = productFrame(page).getByRole("textbox", { name: "Message" });
  await composer.focus();
  const cdp = await page.context().newCDPSession(page);

  for (const candidate of koreanImeCandidates) {
    await cdp.send("Input.imeSetComposition", {
      text: candidate,
      selectionStart: candidate.length,
      selectionEnd: candidate.length
    });
    // Let the frame -> parent -> frame echo complete before checking the next
    // candidate. Before PPP-018, that echo rewrote the live value with an
    // older candidate and caused the IME to start a new composition.
    await page.waitForTimeout(80);
    expect(await composer.inputValue()).toBe(candidate);
    await expect(composer).toBeFocused();
  }

  const confirmationPrevented = await composer.evaluate(node => {
    const event = new KeyboardEvent("keydown", {
      key: "Enter",
      code: "Enter",
      keyCode: 229,
      isComposing: true,
      bubbles: true,
      cancelable: true
    });
    node.dispatchEvent(event);
    return event.defaultPrevented;
  });
  expect(confirmationPrevented).toBe(false);
  expect(turnRequests).toHaveLength(0);

  await cdp.send("Input.insertText", { text: "간단한" });
  await page.waitForTimeout(80);
  expect(await composer.inputValue()).toBe("간단한");
  await expect(composer).toBeFocused();

  await page.keyboard.press("Enter");
  await expect.poll(() => turnRequests.length).toBe(1);
  expect(turnRequests[0].prompt).toBe("간단한");
  await expect(composer).toHaveValue("");

  await page.keyboard.press("Enter");
  expect(turnRequests).toHaveLength(1);
  if (delayFreshFrame) {
    expect(frameBundleRequests).toBeGreaterThanOrEqual(2);
  }
}

for (const scenario of [
  { name: "fresh context one", delayFrameBundle: false },
  { name: "fresh context two", delayFrameBundle: false },
  { name: "delayed cold frame", delayFrameBundle: true }
]) {
  test(`a hidden product commits without animation frames — ${scenario.name}`,
    async ({ page }) => {
      test.setTimeout(45_000);
      await verifyAnimationIndependentColdSession(page, scenario.delayFrameBundle);
    });
}

for (const scenario of [
  { name: "fresh context one", delayFreshFrame: false },
  { name: "fresh context two", delayFreshFrame: false },
  { name: "delayed fresh frame", delayFreshFrame: true }
]) {
  test(`Korean IME composition remains intact — ${scenario.name}`,
    async ({ page }) => {
      test.setTimeout(45_000);
      await verifyKoreanImeComposer(page, scenario.delayFreshFrame);
    });
}

test("a cold new session waits for the product frame to finish loading", async ({ page }) => {
  test.setTimeout(45_000);
  let delayedFrameBundles = 0;

  await page.route("**/frame-js/frame.js*", async route => {
    delayedFrameBundles += 1;
    await new Promise(resolve => setTimeout(resolve, 6_500));
    await route.continue();
  });

  await page.goto("/?test-runtime=1#access=ppp-local");
  await openConversation(page);
  expect(await page.evaluate(
    () => window.__PPP_TEST__.snapshot()["debug-error"]
  )).toBeNull();

  await createFreshSession(page);
  await expect.poll(
    () => page.evaluate(() => window.__PPP_TEST__?.snapshot().version)
  ).toBe(0);
  expect(await page.evaluate(
    () => window.__PPP_TEST__.snapshot()["debug-error"]
  )).toBeNull();
  expect(delayedFrameBundles).toBeGreaterThanOrEqual(2);
});

test("generated frontend is programmable inside an isolated disposable frame", async ({ page }) => {
  const browserErrors = [];
  page.on("pageerror", error => browserErrors.push(error.message));

  await page.goto("/?test-runtime=1#access=ppp-local");
  await openConversation(page);
  await createFreshSession(page);
  await page.getByRole("button", { name: "Close product conversation" }).click();
  await expect.poll(
    () => page.evaluate(() => window.__PPP_TEST__?.snapshot().version)
  ).toBe(0);

  const urlBefore = page.url();
  await stageAndActivate(page, 1, runtimeSource("First live product", { hostile: true }));
  await page.evaluate(() => {
    window.__PPP_TEST__.setState({ draft: "kept across replacement" });
    window.__PPP_TEST__.openSidebar();
  });

  await expect(productFrame(page).getByRole("main", { name: "First live product" })).toBeVisible();
  await expect(productFrame(page).getByLabel("Preserved draft")).toHaveText("kept across replacement");
  await expect(productFrame(page).getByRole("complementary", { name: "Generated conversation" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Close product conversation" })).toBeVisible();
  expect(page.url()).toBe(urlBefore);
  const firstRuntimeChannel = await activeFrameChannel(page);
  await page.screenshot({ path: "artifacts/evidence/ppp-013-sandbox-frame.png" });

  await stageAndActivate(page, 2, runtimeSource("Second live product"));
  await expect(productFrame(page).getByRole("main", { name: "Second live product" })).toBeVisible();
  await expect(productFrame(page).getByLabel("Preserved draft")).toHaveText("kept across replacement");
  expect(await page.evaluate(() => window.__PPP_TEST__.snapshot().version)).toBe(2);
  const secondRuntimeChannel = await activeFrameChannel(page);
  expect(secondRuntimeChannel).not.toBe(firstRuntimeChannel);
  await expect(page.locator("iframe[data-ppp-runtime-frame]")).toHaveCount(1);

  const rejected = await page.evaluate(async source => {
    try {
      await window.__PPP_TEST__.stage(3, source);
      return false;
    } catch (_error) {
      return true;
    }
  }, runtimeSource("Broken product", { throwingSidebar: true }));
  expect(rejected).toBe(true);
  await expect(productFrame(page).getByRole("main", { name: "Second live product" })).toBeVisible();
  expect(await page.evaluate(() => window.__PPP_TEST__.snapshot().version)).toBe(2);
  expect(await activeFrameChannel(page)).toBe(secondRuntimeChannel);
  await expect(page.locator("iframe[data-ppp-runtime-frame]")).toHaveCount(1);

  browserErrors.length = 0;
  await stageAndActivate(page, 3, browserPlatformSource());
  const platform = productFrame(page);
  await expect(platform.getByRole("main", { name: "Browser platform product" })).toBeVisible();
  await expect.poll(async () => Number(await platform.getByLabel("Automatic ticks").textContent()))
    .toBeGreaterThan(1);
  await expect(platform.getByLabel("Parent document readable")).toHaveText("false");
  await expect(platform.getByLabel("WebAssembly available")).toHaveText("true");

  const frameSandbox = await page.locator(
    "iframe[data-ppp-runtime-frame]:not(.ppp-runtime-frame-staged)"
  ).getAttribute("sandbox");
  expect(frameSandbox).toContain("allow-scripts");
  expect(frameSandbox).not.toContain("allow-same-origin");

  const pixel = await platform.getByLabel("Programmable canvas").evaluate(canvas =>
    Array.from(canvas.getContext("2d").getImageData(1, 1, 1, 1).data)
  );
  expect(pixel[1]).toBeGreaterThan(200);

  await platform.getByRole("main", { name: "Browser platform product" }).click();
  await page.keyboard.press("ArrowRight");
  await expect(platform.getByLabel("Keyboard moves")).toHaveText("1");
  expect(browserErrors).toEqual([]);

  const frameChannelBeforeSafeMode = await page.locator(
    "iframe[data-ppp-runtime-frame]:not(.ppp-runtime-frame-staged)"
  ).getAttribute("data-ppp-runtime-channel");
  await page.keyboard.press("Control+Alt+Shift+P");
  await expect(page.getByRole("complementary", { name: "Safe Mode" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Exit Safe Mode" })).toBeEnabled();
  const frameChannelInSafeMode = await page.locator(
    "iframe[data-ppp-runtime-frame]:not(.ppp-runtime-frame-staged)"
  ).getAttribute("data-ppp-runtime-channel");
  expect(frameChannelInSafeMode).not.toBe(frameChannelBeforeSafeMode);
  await expect(page.locator("iframe[data-ppp-runtime-frame]")).toHaveCount(1);
  await expect(platform.getByRole("complementary", { name: "Generated conversation" })).toHaveCount(0);
  await expect(platform.getByRole("main", { name: "Browser platform product" })).toBeVisible();
  await expect(platform.getByLabel("Keyboard moves")).toHaveText("1");
  await page.screenshot({ path: "artifacts/evidence/ppp-013-safe-mode.png" });

  await page.getByRole("button", { name: "Exit Safe Mode" }).click();
  await expect(platform.getByRole("complementary", { name: "Generated conversation" })).toBeVisible();
  const frameChannelAfterSafeMode = await page.locator(
    "iframe[data-ppp-runtime-frame]:not(.ppp-runtime-frame-staged)"
  ).getAttribute("data-ppp-runtime-channel");
  expect(frameChannelAfterSafeMode).not.toBe(frameChannelInSafeMode);
  await expect(page.locator("iframe[data-ppp-runtime-frame]")).toHaveCount(1);

  const handleBox = await page.getByRole("button", { name: "Close product conversation" }).boundingBox();
  await page.mouse.move(handleBox.x + handleBox.width / 2, handleBox.y + handleBox.height / 2);
  await page.mouse.down();
  await page.waitForTimeout(750);
  await page.mouse.up();
  await expect(page.getByRole("complementary", { name: "Safe Mode" })).toBeVisible();
  await page.getByRole("button", { name: "Exit Safe Mode" }).click();

  await page.setViewportSize({ width: 390, height: 844 });
  const sidebarBox = await platform.getByRole("complementary", { name: "Generated conversation" }).boundingBox();
  expect(Math.round(sidebarBox.width)).toBe(390);

  await page.emulateMedia({ reducedMotion: "reduce" });
  const handle = page.getByRole("button", { name: "Close product conversation" });
  await handle.focus();
  await expect(handle).toBeFocused();
  expect(await handle.evaluate(node => getComputedStyle(node).zIndex)).toBe("100");
  expect(await handle.evaluate(node => parseFloat(getComputedStyle(node).transitionDuration)))
    .toBeLessThanOrEqual(0.01);
});
