import { defineConfig } from "@playwright/test";

const capture = process.env.PPP_DEMO_LIVE_CAPTURE === "1";

export default defineConfig({
  testDir: ".",
  outputDir: process.env.PPP_DEMO_LIVE_PLAYWRIGHT_OUTPUT
    || "test-results-demo-story",
  timeout: 3_600_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["line"], ["html", {
    outputFolder: "playwright-report-demo-story",
    open: "never"
  }]],
  use: {
    baseURL: process.env.PPP_LIVE_BASE_URL || "http://127.0.0.1:8798",
    browserName: "chromium",
    actionTimeout: 30_000,
    navigationTimeout: 45_000,
    viewport: { width: 1440, height: 900 },
    video: capture
      ? { mode: "on", size: { width: 1440, height: 900 } }
      : "off",
    trace: "retain-on-failure",
    screenshot: "only-on-failure"
  }
});
