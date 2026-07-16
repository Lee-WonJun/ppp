import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: ".",
  timeout: 240_000,
  expect: { timeout: 200_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["line"], ["html", { outputFolder: "playwright-report-live", open: "never" }]],
  use: {
    baseURL: process.env.PPP_LIVE_BASE_URL || "http://127.0.0.1:8798",
    browserName: "chromium",
    trace: "retain-on-failure",
    screenshot: "only-on-failure"
  }
});
