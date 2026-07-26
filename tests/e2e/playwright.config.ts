import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./specs",
  fullyParallel: false,
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  timeout: 60_000,
  expect: {
    timeout: 15_000,
  },
  outputDir: "test-results/results",
  reporter: [
    ["line"],
    ["html", { outputFolder: "playwright-report/report", open: "never" }],
  ],
  use: {
    baseURL: process.env.BASE_URL ?? "http://localhost:3000",
    headless: true,
    locale: "ja-JP",
    timezoneId: "Asia/Tokyo",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
});
