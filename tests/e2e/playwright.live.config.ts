import { defineConfig } from "@playwright/test";

// Billed staging smoke must never retain documents or Azure results in standard
// Playwright diagnostics. Staging validation permits bounded retries.
export default defineConfig({
  testDir: "./specs",
  timeout: 23 * 60_000,
  expect: {
    timeout: 15_000,
  },
  retries: 2,
  workers: 1,
  reporter: [["line"]],
  use: {
    baseURL: process.env.BASE_URL,
    headless: true,
    locale: "ja-JP",
    timezoneId: "Asia/Tokyo",
    trace: "off",
    screenshot: "off",
    video: "off",
  },
});
