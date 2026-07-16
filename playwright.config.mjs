import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["line"], ["html", { open: "never" }]],
  use: {
    baseURL: process.env.PPP_E2E_BASE_URL || "http://127.0.0.1:8797",
    browserName: "chromium",
    trace: "retain-on-failure",
    screenshot: "only-on-failure"
  }
});
