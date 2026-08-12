import { resolve } from "node:path";

import { expect, test, type Page } from "@playwright/test";

const keycloakUrl = process.env.KEYCLOAK_URL ?? "http://localhost:8180";
const userEmail = requiredEnvironment("DEV_USER_EMAIL");
const userPassword = requiredEnvironment("DEV_USER_PASSWORD");

function requiredEnvironment(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Required environment variable ${name} is not set.`);
  return value;
}

async function login(page: Page): Promise<void> {
  await page.goto("/login");
  for (let attempt = 1; attempt <= 2; attempt += 1) {
    const signInResponse = page.waitForResponse((response) =>
      response.url().includes("/api/auth/sign-in/oauth2"),
    );
    await page.getByRole("button", { name: "ログイン", exact: true }).click();
    const response = await signInResponse;
    if (response.ok()) break;

    expect(response.status()).toBe(429);
    expect(attempt).toBeLessThan(2);
    const retryAfterSeconds = Number(response.headers()["x-retry-after"]);
    expect(retryAfterSeconds).toBeGreaterThan(0);
    await page.waitForTimeout(retryAfterSeconds * 1_000 + 100);
  }
  await expect(page).toHaveURL(new RegExp(
    `^${keycloakUrl.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}/realms/workflow/`,
  ));
  await page.locator("#username").fill(userEmail);
  await page.locator("#password").fill(userPassword);
  await page.locator("#kc-login").click();
  await expect(page).toHaveURL(/\/top$/);
}

test("AUTO_ENTRY基本画面はFake ProviderのReviewを表示してreload復元できる", async ({ page }) => {
  test.setTimeout(90_000);

  await login(page);
  await page.getByRole("link", { name: "Content Understanding", exact: true }).click();
  await expect(page).toHaveURL(/\/content-understanding$/);
  await expect(page.getByRole("heading", { name: "Content Understanding", exact: true })).toBeVisible();

  await page.getByRole("link", { name: "自動入力を開く", exact: true }).click();
  await expect(page).toHaveURL(/\/content-understanding\/auto-entry$/);
  await expect(page.getByRole("heading", { name: "自動入力", exact: true })).toBeVisible();

  await page.locator("#auto-entry-file-desktop").setInputFiles(resolve("fixtures/receipt.pdf"));
  const createResponse = page.waitForResponse((response) =>
    response.url().includes("/api/backend/document-analyses")
      && response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "分析を実行", exact: true }).click();
  expect(await (await createResponse).json()).toMatchObject({
    provider: "CONTENT_UNDERSTANDING",
    profile: "AUTO_ENTRY",
  });

  await expect(page).toHaveURL(/\/content-understanding\/auto-entry\?analysis=[0-9a-f-]{36}$/);
  const analysisId = new URL(page.url()).searchParams.get("analysis");
  expect(analysisId).toMatch(/^[0-9a-f-]{36}$/);
  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", {
    timeout: 60_000,
  });
  await expect(page.getByRole("heading", { name: "自動入力結果", exact: true })).toBeVisible();
  await expect(page.getByText("未取得", { exact: true }).first()).toBeVisible();

  await page.reload();
  await expect(page).toHaveURL(new RegExp(`/content-understanding/auto-entry\\?analysis=${analysisId}$`));
  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", {
    timeout: 60_000,
  });
  await expect(page.getByRole("heading", { name: "自動入力結果", exact: true })).toBeVisible();
  await expect(page.getByText("未取得", { exact: true }).first()).toBeVisible();
});
