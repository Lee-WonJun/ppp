import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: ".",
  timeout: 3_600_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["line"], ["html", {
    outputFolder: "playwright-report-evolution",
    open: "never"
  }]],
  use: {
    baseURL: process.env.PPP_LIVE_BASE_URL || "http://127.0.0.1:8797",
    browserName: "chromium",
    actionTimeout: 30_000,
    navigationTimeout: 45_000,
    trace: "retain-on-failure",
    screenshot: "only-on-failure"
  }
});
