import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { expect, test, type Page } from "@playwright/test";

const keycloakUrl = process.env.KEYCLOAK_URL ?? "http://localhost:8180";
const userEmail = requiredEnvironment("DEV_USER_EMAIL");
const userPassword = requiredEnvironment("DEV_USER_PASSWORD");
const expenseUserEmail = requiredEnvironment("DEV_EXPENSE_USER_EMAIL");
const expenseUserPassword = requiredEnvironment("DEV_EXPENSE_PASSWORD");
const receiptPdf = readFileSync(resolve("fixtures/receipt.pdf"));

function requiredEnvironment(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Required environment variable ${name} is not set.`);
  return value;
}

async function login(
  page: Page,
  email = userEmail,
  password = userPassword,
): Promise<void> {
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
  await page.locator("#username").fill(email);
  await page.locator("#password").fill(password);
  await page.locator("#kc-login").click();
  await expect(page).toHaveURL(/\/top$/);
}

async function makeAutoEntryLineItemMissing(page: Page): Promise<void> {
  await page.route("**/api/backend/document-analyses/*/auto-entry-review", async (route) => {
    const response = await route.fetch();
    const review = await response.json() as {
      document: {
        lineItems: {
          value: Array<{
            itemDescription: Record<string, unknown>;
            lineAmount: Record<string, unknown>;
          }> | null;
        };
      };
    };
    const lineItem = review.document.lineItems.value?.[0];
    if (!lineItem) throw new Error("AUTO_ENTRY review must include a line item.");

    lineItem.itemDescription = {
      ...lineItem.itemDescription,
      confidence: null,
      findings: [],
      sources: [],
      status: "MISSING",
      value: null,
    };
    lineItem.lineAmount = {
      ...lineItem.lineAmount,
      confidence: null,
      findings: [],
      sources: [],
      status: "MISSING",
      value: null,
    };
    await route.fulfill({ response, json: review });
  });
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
  await expect(page.getByText("文書番号", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("未取得", { exact: true }).first()).toBeVisible();

  const lineItemsSection = page.getByRole("heading", { name: "明細", exact: true }).locator("..");
  const lineItemsTable = lineItemsSection.getByRole("table");
  await expect(lineItemsTable.getByRole("columnheader")).toHaveCount(10);
  await expect(lineItemsTable.getByRole("row")).toHaveCount(2);
  await expect(lineItemsTable.getByText("明細 1", { exact: false })).toHaveCount(0);
  await expect(lineItemsTable.getByText("明細 2", { exact: false })).toHaveCount(0);
  const lineItemDataRow = lineItemsTable.getByRole("row").filter({
    has: page.getByText("業務用備品", { exact: true }),
  });
  await expect(lineItemDataRow).toHaveCount(1);
  await expect(lineItemDataRow.getByRole("cell")).toHaveCount(10);
  await expect(lineItemDataRow.getByRole("cell").nth(2)).toContainText("業務用備品");
  await expect(lineItemDataRow.getByRole("cell").nth(2).getByText("OK", { exact: true })).toBeVisible();
  await expect(lineItemDataRow.getByRole("cell").nth(7)).toContainText("10");
  await expect(lineItemDataRow.getByRole("cell").nth(9)).toContainText("10,000");

  await page.reload();
  await expect(page).toHaveURL(new RegExp(`/content-understanding/auto-entry\\?analysis=${analysisId}$`));
  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", {
    timeout: 60_000,
  });
  await expect(page.getByRole("heading", { name: "自動入力結果", exact: true })).toBeVisible();
  await expect(page.getByText("未取得", { exact: true }).first()).toBeVisible();
  await expect(page.getByRole("button", { name: "分析を実行", exact: true })).toBeDisabled();

  await page.locator("#auto-entry-file-desktop").setInputFiles(resolve("fixtures/receipt.pdf"));
  await expect(page.getByRole("button", { name: "分析を実行", exact: true })).toBeEnabled();
});

test("要確認のみではMISSINGの自動入力明細を最後まで修正できる", async ({ page }) => {
  test.setTimeout(90_000);

  await login(page, expenseUserEmail, expenseUserPassword);
  await makeAutoEntryLineItemMissing(page);
  await page.goto("/expenses/auto-entry");
  await page.locator("#expense-auto-entry-file").setInputFiles(resolve("fixtures/receipt.pdf"));
  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", {
    timeout: 60_000,
  });

  const description = page.getByLabel("内容", { exact: true });
  const amount = page.getByLabel("金額（円）", { exact: true });
  await expect(description).toBeVisible();
  await expect(amount).toBeVisible();
  await description.pressSequentially("業務用備品");
  await expect(description).toHaveValue("業務用備品");
  await amount.pressSequentially("1200");
  await expect(amount).toHaveValue("1200");
  await expect(page.getByText("修正済み", { exact: true })).toHaveCount(2);
});

test("請求/注文書申請（自動入力）はBFF経由でFormal Expense下書きを作成する", async ({ page }) => {
  test.setTimeout(90_000);

  await login(page, expenseUserEmail, expenseUserPassword);
  await page.getByRole("link", { name: "請求/注文書申請（自動入力）", exact: true }).click();
  await expect(page).toHaveURL(/\/expenses\/auto-entry$/);
  await expect(page.getByRole("heading", { name: "請求/注文書申請（自動入力）", exact: true })).toBeVisible();

  const createAnalysisResponse = page.waitForResponse((response) =>
    response.url().includes("/api/backend/document-analyses")
      && response.request().method() === "POST",
  );
  await page.locator("#expense-auto-entry-file").setInputFiles(resolve("fixtures/receipt.pdf"));
  expect(await (await createAnalysisResponse).json()).toMatchObject({
    provider: "CONTENT_UNDERSTANDING",
    profile: "AUTO_ENTRY",
  });
  await expect(page.getByRole("region", { name: "receipt.pdfのプレビュー" }).locator("iframe"))
    .toBeVisible();
  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", {
    timeout: 60_000,
  });

  await expect(page.getByLabel("内容", { exact: true })).toBeVisible();
  await expect(page.getByLabel("金額（円）", { exact: true })).toBeVisible();
  await page.locator("select").first().selectOption("MEAL");
  await expect(page.getByLabel("店舗名", { exact: true })).toBeVisible();
  await expect(page.getByLabel("参加者", { exact: true })).toBeVisible();
  await page.locator("select").first().selectOption("OTHER");

  const originalConfirmation = page.getByLabel("原本を確認しました", { exact: true });
  await originalConfirmation.check();
  await expect(originalConfirmation).toBeVisible();
  await expect(originalConfirmation).toBeChecked();
  await originalConfirmation.uncheck();
  await expect(originalConfirmation).toBeVisible();
  await expect(originalConfirmation).not.toBeChecked();

  await page.getByLabel("件名", { exact: true }).fill(`自動入力E2E-${Date.now()}`);
  await page.getByLabel("利用目的", { exact: true }).fill("請求書に基づく業務用備品の精算");
  const handoffResponse = page.waitForResponse((response) =>
    response.url().endsWith("/api/backend/expense-applications/from-auto-entry")
      && response.request().method() === "POST",
  );
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "決定", exact: true }).click();

  const response = await handoffResponse;
  expect([200, 201]).toContain(response.status());
  const payload = response.request().postDataJSON() as Record<string, unknown>;
  expect(Object.keys(payload).sort()).toEqual([
    "analysisId", "application", "confirmedFieldPaths", "document",
  ]);
  expect(JSON.stringify(payload)).not.toContain("confidence");
  expect(JSON.stringify(payload)).not.toContain("findings");
  expect(JSON.stringify(payload)).not.toContain("sources");
  expect(JSON.stringify(payload)).not.toContain("polygon");
  expect(JSON.stringify(payload)).not.toContain("resolution");

  const created = await response.json() as { application: { id: string } };
  await expect(page).toHaveURL(new RegExp(
    `/expenses/auto-entry/confirm/${created.application.id}$`,
  ));
});

test("AUTO_ENTRYはRecent analysesから同じJob、Review、source previewを復元する", async ({ page }) => {
  test.setTimeout(90_000);

  await login(page);
  await page.goto("/content-understanding/auto-entry");
  await expect(page.getByRole("heading", { name: "自動入力", exact: true })).toBeVisible();

  await page.locator("#auto-entry-file-desktop").setInputFiles({
    name: "auto-entry-recent.pdf",
    mimeType: "application/pdf",
    buffer: receiptPdf,
  });
  await page.getByRole("button", { name: "分析を実行", exact: true }).click();
  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", {
    timeout: 60_000,
  });
  const analysisId = new URL(page.url()).searchParams.get("analysis");
  expect(analysisId).toMatch(/^[0-9a-f-]{36}$/);

  await page.goto("/content-understanding");
  await expect(page.getByRole("heading", { name: "Content Understanding", exact: true })).toBeVisible();
  await page.goto("/content-understanding/auto-entry");
  const recentAnalysis = page.getByTestId("document-analysis-file-pane")
    .getByRole("button", { name: /auto-entry-recent\.pdf/ });
  await expect(recentAnalysis).toBeVisible();
  await recentAnalysis.click();

  await expect(page).toHaveURL(new RegExp(`/content-understanding/auto-entry\\?analysis=${analysisId}$`));
  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", {
    timeout: 60_000,
  });
  await expect(page.getByRole("heading", { name: "自動入力結果", exact: true })).toBeVisible();
  await expect(page.getByText("文書番号", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("未取得", { exact: true }).first()).toBeVisible();
  await expect(page.locator("iframe[title='auto-entry-recent.pdfのPDFプレビュー']"))
    .toHaveAttribute("src", `/api/backend/document-analyses/${analysisId}/source?profile=AUTO_ENTRY`);
  await expect(page.getByRole("button", { name: "分析を実行", exact: true })).toBeDisabled();
});

test("AUTO_ENTRYはmobileでFile、Preview、Resultを切り替えてReviewを表示する", async ({ page }) => {
  test.setTimeout(90_000);
  await page.setViewportSize({ width: 390, height: 844 });

  await login(page);
  await page.goto("/content-understanding/auto-entry");
  await expect(page.getByRole("tab", { name: "File", exact: true })).toHaveAttribute("aria-selected", "true");

  await page.locator("#auto-entry-file-mobile").setInputFiles({
    name: "auto-entry-mobile.pdf",
    mimeType: "application/pdf",
    buffer: receiptPdf,
  });
  await expect(page.getByRole("tab", { name: "Preview", exact: true })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("region", { name: "auto-entry-mobile.pdfのプレビュー" })
    .locator("iframe")).toBeVisible();

  await page.getByRole("button", { name: "分析を実行", exact: true }).click();
  await expect(page.getByRole("tab", { name: "Result", exact: true })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", { timeout: 60_000 });
  const mobileReview = page.getByTestId("auto-entry-review-panel").last();
  await expect(mobileReview.getByRole("heading", { name: "自動入力結果", exact: true })).toBeVisible();
  await expect(mobileReview.getByText("OK", { exact: true }).first()).toBeVisible();
  await expect(mobileReview.getByText("要確認", { exact: true }).first()).toBeVisible();
  await expect(mobileReview.getByText("未取得", { exact: true }).first()).toBeVisible();

  const lineItemsTable = mobileReview.getByRole("heading", { name: "明細", exact: true })
    .locator("..").getByRole("table");
  await expect(lineItemsTable).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});
