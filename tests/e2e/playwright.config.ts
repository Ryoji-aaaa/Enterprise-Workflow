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
  outputDir: process.env.PLAYWRIGHT_OUTPUT_DIR ?? "test-results/results",
  reporter: [
    ["line"],
    ["junit", { outputFile: process.env.PLAYWRIGHT_JUNIT_OUTPUT ?? "test-results/junit.xml" }],
    ["json", { outputFile: process.env.PLAYWRIGHT_JSON_OUTPUT ?? "test-results/report.json" }],
    ["html", {
      outputFolder: process.env.PLAYWRIGHT_HTML_OUTPUT ?? "playwright-report/report",
      open: "never",
    }],
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
