import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { expect, test, type Page } from "@playwright/test";

import { loadStagingPersona } from "../support/staging-persona";

const keycloakUrl = process.env.KEYCLOAK_URL ?? "http://localhost:8180";
const userEmail = requiredEnvironment("DEV_USER_EMAIL");
const userPassword = requiredEnvironment("DEV_USER_PASSWORD");
const seedUserPassword = requiredEnvironment("DEV_SEED_USER_PASSWORD");
const receiptPdf = readFileSync(resolve("fixtures/receipt.pdf"));
const receiptPng = Buffer.from(
  readFileSync(resolve("fixtures/receipt.png"), "utf8").trim(),
  "base64",
);

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

function attentionFilterSwitch(page: Page) {
  return page.getByRole("switch", { name: "表示フィルター" });
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

async function makeAutoEntryTaxRegistrationSourceLess(page: Page): Promise<void> {
  await page.route("**/api/backend/document-analyses/*/auto-entry-review", async (route) => {
    const response = await route.fetch();
    const review = await response.json() as {
      document: {
        issuerTaxRegistrationNumber: { sources: unknown[] };
      };
    };
    review.document.issuerTaxRegistrationNumber.sources = [];
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

  const applicant = await loadStagingPersona("STANDARD_APPLICANT");
  await login(page, applicant.email, seedUserPassword);
  await makeAutoEntryLineItemMissing(page);
  await page.goto("/expenses/auto-entry");
  await page.locator("#expense-auto-entry-file").setInputFiles(resolve("fixtures/receipt.pdf"));
  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", {
    timeout: 60_000,
  });

  const filterSwitch = attentionFilterSwitch(page);
  await expect(filterSwitch).not.toBeChecked();
  await expect(page.getByLabel("総請求額（円）", { exact: true })).toBeVisible();
  await filterSwitch.click();
  await expect(filterSwitch).toBeChecked();
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

test("AUTO_ENTRY画像Previewはsource polygonを原本上へoverlay表示する", async ({ page }) => {
  test.setTimeout(90_000);

  const applicant = await loadStagingPersona("STANDARD_APPLICANT");
  await login(page, applicant.email, seedUserPassword);
  await makeAutoEntryTaxRegistrationSourceLess(page);
  await page.goto("/expenses/auto-entry");

  const createAnalysisResponse = page.waitForResponse((response) =>
    response.url().includes("/api/backend/document-analyses")
      && response.request().method() === "POST",
  );
  await page.locator("#expense-auto-entry-file").setInputFiles({
    name: "receipt.png",
    mimeType: "image/png",
    buffer: receiptPng,
  });
  expect(await (await createAnalysisResponse).json()).toMatchObject({
    provider: "CONTENT_UNDERSTANDING",
    profile: "AUTO_ENTRY",
  });

  const preview = page.getByRole("region", { name: "receipt.pngのプレビュー" });
  const image = preview.getByRole("img", { name: "receipt.pngのプレビュー" });
  await expect(image).toBeVisible();
  const imageState = await image.evaluate((element: HTMLImageElement) => ({
    complete: element.complete,
    naturalHeight: element.naturalHeight,
    naturalWidth: element.naturalWidth,
  }));
  expect(imageState.complete).toBe(true);
  expect(imageState.naturalHeight).toBeGreaterThan(0);
  expect(imageState.naturalWidth).toBeGreaterThan(0);

  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", {
    timeout: 60_000,
  });

  const evidenceOverlay = preview.getByTestId("expense-auto-entry-source-overlay");
  const issuerEvidence = preview.locator(
    'polygon[data-field-path="document.issuerName"]',
  );
  await expect(evidenceOverlay).toBeVisible();
  await expect(evidenceOverlay).toHaveCSS("pointer-events", "none");
  await expect(issuerEvidence).toHaveCount(1);
  await expect(issuerEvidence).toHaveAttribute("data-page-number", "1");
  await expect(issuerEvidence).toHaveAttribute("data-source-index", "0");
  await expect(issuerEvidence).toHaveAttribute("data-active", "false");
  await expect(issuerEvidence).toHaveAttribute("points", /.+/);
  const points = await issuerEvidence.getAttribute("points");
  expect(points).not.toBeNull();
  expect(points ?? "").not.toMatch(/NaN|Infinity/);
  await expect(preview.locator(
    'polygon[data-field-path="document.lineItems[0].itemDescription"]',
  )).toHaveCount(1);
  await expect(preview.locator(
    'polygon[data-field-path="document.issuerTaxRegistrationNumber"]',
  )).toHaveCount(0);

  const filterSwitch = attentionFilterSwitch(page);
  await expect(filterSwitch).not.toBeChecked();
  const totalEvidence = preview.locator(
    'polygon[data-field-path="document.totalAmount"]',
  );
  const lineDescriptionEvidence = preview.locator(
    'polygon[data-field-path="document.lineItems[0].itemDescription"]',
  );
  const issuerName = page.getByLabel("請求社 / 発行元", { exact: true });
  const invoiceTotal = page.getByLabel("総請求額（円）", { exact: true });

  await issuerName.focus();
  await expect(issuerEvidence).toHaveAttribute("data-active", "true");
  await expect(totalEvidence).toHaveAttribute("data-active", "false");
  await expect(lineDescriptionEvidence).toHaveAttribute("data-active", "false");

  await invoiceTotal.focus();
  await expect(issuerEvidence).toHaveAttribute("data-active", "false");
  await expect(totalEvidence).toHaveAttribute("data-active", "true");

  await page.getByLabel("内容", { exact: true }).focus();
  await expect(totalEvidence).toHaveAttribute("data-active", "false");
  await expect(lineDescriptionEvidence).toHaveAttribute("data-active", "true");

  await page.getByLabel("インボイス登録番号", { exact: true }).focus();
  await expect(preview.locator('polygon[data-active="true"]')).toHaveCount(0);
  await expect(preview.locator("polygon")).not.toHaveCount(0);

  await invoiceTotal.focus();
  await expect(totalEvidence).toHaveAttribute("data-active", "true");
  await filterSwitch.click();
  await expect(filterSwitch).toBeChecked();
  await expect(invoiceTotal).toBeHidden();
  await expect(preview.locator('polygon[data-active="true"]')).toHaveCount(0);
  await expect(issuerEvidence).toHaveAttribute("data-active", "false");

  await page.getByRole("button", { name: "明細追加", exact: true }).click();
  await page.getByLabel("内容", { exact: true }).last().focus();
  await expect(preview.locator('polygon[data-active="true"]')).toHaveCount(0);

  await filterSwitch.click();
  await expect(filterSwitch).not.toBeChecked();
  await expect(invoiceTotal).toBeVisible();
  await issuerName.fill("編集後の発行元");
  await expect(issuerEvidence).toHaveAttribute("data-active", "true");
  await issuerName.blur();
  await expect(issuerEvidence).toHaveAttribute("data-active", "false");

  await page.getByLabel("内容", { exact: true }).first().focus();
  await expect(lineDescriptionEvidence).toHaveAttribute("data-active", "true");
  await page.getByRole("button", { name: "明細1を削除", exact: true }).click();
  await expect(lineDescriptionEvidence).toHaveCount(0);
  await expect(preview.locator('polygon[data-active="true"]')).toHaveCount(0);
});

test("通常経費フォームも申請結果不明時は再実行を止めて詳細確認へ誘導する", async ({ page }) => {
  test.setTimeout(90_000);

  const applicant = await loadStagingPersona("STANDARD_APPLICANT");
  await login(page, applicant.email, seedUserPassword);
  await page.goto("/expenses/new");
  await page.getByLabel("件名", { exact: true }).fill(`E2E通常申請結果不明-${Date.now()}`);
  await page.getByLabel("利用目的", { exact: true }).fill("結果不明時の再実行防止確認");
  await page.getByLabel("内容（片道／往復を含む）", { exact: true }).fill("電車移動");
  await page.getByLabel("金額（円）", { exact: true }).fill("1000");
  await page.getByLabel("交通手段", { exact: true }).fill("電車");
  await page.getByLabel("出発地", { exact: true }).fill("東京");
  await page.getByLabel("到着地", { exact: true }).fill("品川");

  let submitAttempts = 0;
  const expenseApiPath = "**/api/backend/expense-applications**";
  await page.route(expenseApiPath, async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    if (request.method() === "POST" && pathname.endsWith("/submit")) {
      submitAttempts += 1;
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({ code: "BACKEND_UNAVAILABLE" }),
      });
      return;
    }
    if (request.method() === "GET"
        && /^\/api\/backend\/expense-applications\/[0-9a-f-]{36}$/.test(pathname)) {
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({ code: "BACKEND_UNAVAILABLE" }),
      });
      return;
    }
    await route.continue();
  });

  const createResponse = page.waitForResponse((response) =>
    response.url().endsWith("/api/backend/expense-applications")
      && response.request().method() === "POST",
  );
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "申請", exact: true }).click();
  const created = await (await createResponse).json() as { id: string };

  await expect(page.getByText("申請結果を確認できませんでした。", { exact: false })).toBeVisible();
  await expect(page.getByRole("button", { name: "申請", exact: true })).toBeDisabled();
  await expect(page.getByRole("button", { name: "下書き保存", exact: true })).toBeDisabled();
  const detailLink = page.getByRole("link", { name: "申請詳細を確認", exact: true });
  await expect(detailLink).toHaveAttribute("href", `/expenses/${created.id}`);
  expect(submitAttempts).toBe(1);

  await page.unroute(expenseApiPath);
  await detailLink.click();
  await expect(page).toHaveURL(new RegExp(`/expenses/${created.id}$`));
  await expect(page.getByText("下書き", { exact: true }).first()).toBeVisible();
});

test("通常経費の申請再試行は最初に保存したDRAFTを再利用する", async ({ page }) => {
  test.setTimeout(90_000);

  const applicant = await loadStagingPersona("STANDARD_APPLICANT");
  await login(page, applicant.email, seedUserPassword);
  await page.goto("/expenses/new");
  await page.getByLabel("件名", { exact: true }).fill(`E2E通常申請再試行-${Date.now()}`);
  await page.getByLabel("利用目的", { exact: true }).fill("保存済みDRAFTの再利用確認");
  await page.getByLabel("内容（片道／往復を含む）", { exact: true }).fill("電車移動");
  await page.getByLabel("金額（円）", { exact: true }).fill("1000");
  await page.getByLabel("交通手段", { exact: true }).fill("電車");
  await page.getByLabel("出発地", { exact: true }).fill("東京");
  await page.getByLabel("到着地", { exact: true }).fill("品川");

  let createAttempts = 0;
  const submitApplicationIds: string[] = [];
  const updateApplicationIds: string[] = [];
  const expenseApiPath = "**/api/backend/expense-applications**";
  await page.route(expenseApiPath, async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    const applicationPathMatch = pathname.match(
      /^\/api\/backend\/expense-applications\/([0-9a-f-]{36})$/,
    );
    const submitPathMatch = pathname.match(
      /^\/api\/backend\/expense-applications\/([0-9a-f-]{36})\/submit$/,
    );

    if (request.method() === "POST" && pathname === "/api/backend/expense-applications") {
      createAttempts += 1;
    } else if (request.method() === "PUT" && applicationPathMatch) {
      updateApplicationIds.push(applicationPathMatch[1]);
    } else if (request.method() === "POST" && submitPathMatch) {
      submitApplicationIds.push(submitPathMatch[1]);
      if (submitApplicationIds.length === 1) {
        await route.fulfill({
          status: 503,
          contentType: "application/json",
          body: JSON.stringify({ code: "BACKEND_UNAVAILABLE" }),
        });
        return;
      }
    }
    await route.continue();
  });

  const createResponse = page.waitForResponse((response) =>
    response.url().endsWith("/api/backend/expense-applications")
      && response.request().method() === "POST",
  );
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "申請", exact: true }).click();
  const created = await (await createResponse).json() as { id: string };

  await expect(page.getByText("申請は完了していません。", { exact: false })).toBeVisible();
  await expect(page.getByRole("button", { name: "申請", exact: true })).toBeEnabled();

  const submitResponse = page.waitForResponse((response) =>
    response.url().endsWith(`/api/backend/expense-applications/${created.id}/submit`)
      && response.request().method() === "POST"
      && response.status() !== 503,
  );
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "申請", exact: true }).click();
  expect((await submitResponse).ok()).toBe(true);

  await expect(page).toHaveURL(new RegExp(`/expenses/${created.id}$`));
  expect(createAttempts).toBe(1);
  expect(updateApplicationIds).toEqual([created.id]);
  expect(submitApplicationIds).toEqual([created.id, created.id]);
  await page.unroute(expenseApiPath);
});

test("請求/注文書申請（自動入力）は保存・申請・差戻し・再編集・再申請できる", async ({ browser, page }) => {
  test.setTimeout(90_000);

  const [applicant, managerPersona] = await Promise.all([
    loadStagingPersona("STANDARD_APPLICANT"),
    loadStagingPersona("DEPARTMENT_MANAGER"),
  ]);
  await login(page, applicant.email, seedUserPassword);
  await page.getByRole("link", { name: "請求/注文書申請（自動入力）", exact: true }).click();
  await expect(page).toHaveURL(/\/expenses\/auto-entry$/);
  await expect(page.getByRole("heading", { name: "請求/注文書申請（自動入力）", exact: true })).toBeVisible();
  await expect(page.locator('[data-workspace-layout="content-oriented"]')).toHaveCSS("display", "block");
  await expect(page.getByRole("complementary", { name: "サイドメニュー" })).toHaveCount(0);
  await expect(page.getByRole("navigation", { name: "モバイルナビゲーション" })).toHaveCount(0);
  const menuTrigger = page.getByRole("button", { name: "メニューを開く", exact: true });
  await expect(menuTrigger).toBeVisible();
  await menuTrigger.click();
  const drawer = page.getByRole("dialog", { name: "ワークスペースメニュー" });
  await expect(drawer).toBeVisible();
  await expect(drawer.getByRole("link", { name: "請求/注文書申請（自動入力）", exact: true }))
    .toHaveAttribute("aria-current", "page");
  await page.keyboard.press("Escape");
  await expect(drawer).toBeHidden();
  await expect(menuTrigger).toBeFocused();

  await makeAutoEntryTaxRegistrationSourceLess(page);
  const createAnalysisResponse = page.waitForResponse((response) =>
    response.url().includes("/api/backend/document-analyses")
      && response.request().method() === "POST",
  );
  await page.locator("#expense-auto-entry-file").setInputFiles(resolve("fixtures/receipt.pdf"));
  expect(await (await createAnalysisResponse).json()).toMatchObject({
    provider: "CONTENT_UNDERSTANDING",
    profile: "AUTO_ENTRY",
  });
  await expect(page.getByRole("region", { name: "receipt.pdfのプレビュー" })
    .getByTestId("expense-auto-entry-pdf-page"))
    .toBeVisible();
  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", {
    timeout: 60_000,
  });

  const filterSwitch = attentionFilterSwitch(page);
  await expect(filterSwitch).not.toBeChecked();

  const evidenceOverlay = page.getByTestId("expense-auto-entry-source-overlay").first();
  const issuerEvidence = page.locator('polygon[data-field-path="document.issuerName"]');
  await expect(evidenceOverlay).toBeVisible();
  await expect(evidenceOverlay).toHaveCSS("pointer-events", "none");
  await expect(issuerEvidence).toHaveCount(1);
  await expect(issuerEvidence).toHaveAttribute("data-page-number", "1");
  await expect(issuerEvidence).toHaveAttribute("data-source-index", "0");
  await expect(page.locator(
    'polygon[data-field-path="document.lineItems[0].itemDescription"]',
  )).toHaveCount(1);
  await expect(page.locator(
    'polygon[data-field-path="document.issuerTaxRegistrationNumber"]',
  )).toHaveCount(0);

  await expect(page.getByLabel("内容", { exact: true })).toBeVisible();
  await expect(page.getByLabel("金額（円）", { exact: true })).toBeVisible();
  const workbenchTax = page.getByTestId("expense-auto-entry-tax-amount");
  await expect(workbenchTax.getByText("消費税（読取値）", { exact: false })).toContainText("1,000");
  await expect(workbenchTax.getByText("OK", { exact: true })).toBeVisible();
  await expect(issuerEvidence).toHaveCount(1);
  await expect(page.getByLabel("総請求額（円）", { exact: true })).toHaveValue("10500");
  await filterSwitch.click();
  await expect(filterSwitch).toBeChecked();
  await expect(issuerEvidence).toHaveCount(1);
  const workbenchAdjustments = page.getByTestId("expense-auto-entry-adjustments");
  await expect(workbenchAdjustments).toContainText("調整額（読取値）");
  await expect(workbenchAdjustments).toContainText("値引き（減算）");
  await expect(workbenchAdjustments).toContainText(/-[￥¥]500/);
  await expect(workbenchAdjustments.getByText("OK", { exact: true })).toBeVisible();
  await expect(page.getByText("請求書総額と申請金額の照合結果が一致しません", { exact: true }))
    .toHaveCount(0);
  await filterSwitch.click();
  await expect(filterSwitch).not.toBeChecked();
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
  await expect(page.getByRole("heading", { name: "自動入力の確認", exact: true })).toBeVisible();
  await expect(attentionFilterSwitch(page)).not.toBeChecked();
  await expect(page.getByText("文書を読み込む", { exact: true })).toHaveCount(0);
  await expect(page.getByLabel("現在の分析状態")).toHaveCount(0);
  await expect(page.getByRole("region", { name: "receipt.pdfのプレビュー" }).locator("iframe")).toBeVisible();
  await expect(page.getByTestId("expense-auto-entry-tax-amount")).toContainText("1,000");
  await expect(page.getByTestId("expense-auto-entry-adjustments")).toContainText(/-[￥¥]500/);
  await expect(page.getByText("請求書総額と申請金額の照合結果が一致しません", { exact: true }))
    .toHaveCount(0);

  await page.reload();
  await expect(page.getByRole("heading", { name: "自動入力の確認", exact: true })).toBeVisible();
  await expect(attentionFilterSwitch(page)).not.toBeChecked();
  await expect(page.getByRole("region", { name: "receipt.pdfのプレビュー" }).locator("iframe")).toHaveAttribute(
    "src",
    new RegExp(`/api/backend/expense-applications/${created.application.id}/attachments/[0-9a-f-]{36}/content$`),
  );
  await expect(page.getByTestId("expense-auto-entry-tax-amount")).toContainText("1,000");
  await expect(page.getByTestId("expense-auto-entry-adjustments")).toContainText(/-[￥¥]500/);
  await expect(page.getByText("請求書総額と申請金額の照合結果が一致しません", { exact: true }))
    .toHaveCount(0);

  const lineAmount = page.getByLabel("金額（円）", { exact: true });
  await lineAmount.fill("9998");
  await expect(page.getByText("請求書総額と申請金額の照合結果が一致しません", { exact: true }))
    .toBeVisible();
  await lineAmount.fill("10000");
  await expect(page.getByText("請求書総額と申請金額の照合結果が一致しません", { exact: true }))
    .toHaveCount(0);

  const issuerName = page.getByLabel("請求社 / 発行元", { exact: true });
  await issuerName.fill("最終編集済み発行元");
  await expect(page.getByText("修正済み", { exact: true }).first()).toBeVisible();
  const saveResponse = page.waitForResponse((candidate) =>
    candidate.url().endsWith(`/api/backend/expense-applications/${created.application.id}/auto-entry-draft`)
      && candidate.request().method() === "PUT",
  );
  await page.getByRole("button", { name: "下書き保存", exact: true }).click();
  expect((await saveResponse).ok()).toBeTruthy();
  await expect(page.getByText("保存しました", { exact: true })).toBeVisible();
  await expect(attentionFilterSwitch(page)).not.toBeChecked();

  await page.reload();
  await expect(page.getByLabel("請求社 / 発行元", { exact: true })).toHaveValue("最終編集済み発行元");
  await expect(attentionFilterSwitch(page)).not.toBeChecked();
  const submit = page.getByRole("button", { name: "申請", exact: true });
  await expect(submit).toBeEnabled();
  await page.getByLabel("件名", { exact: true }).fill("");
  await expect(submit).toBeDisabled();
  await page.getByLabel("件名", { exact: true }).fill(`自動入力E2E-${Date.now()}（最終確認）`);
  await expect(submit).toBeEnabled();
  const submitResponse = page.waitForResponse((candidate) =>
    candidate.url().endsWith(`/api/backend/expense-applications/${created.application.id}/submit`)
      && candidate.request().method() === "POST",
  );
  page.once("dialog", (dialog) => dialog.accept());
  await submit.click();
  expect((await submitResponse).ok()).toBeTruthy();
  await expect(page).toHaveURL(new RegExp(`/expenses/${created.application.id}$`));
  await expect(page.getByText("承認待ち", { exact: true }).first()).toBeVisible();

  const managerContext = await browser.newContext();
  const manager = await managerContext.newPage();
  try {
    await login(manager, managerPersona.email, seedUserPassword);
    await manager.goto(`/approvals/${created.application.id}`);
    await expect(manager.getByText("receipt.pdf", { exact: true })).toBeVisible();
    const attachmentsResponse = await manager.request.get(
      `/api/backend/expense-applications/${created.application.id}/attachments`,
    );
    expect(attachmentsResponse.status()).toBe(200);
    const attachments = await attachmentsResponse.json() as Array<{ id: string }>;
    expect(attachments).toHaveLength(1);
    const sourceResponse = await manager.request.get(
      `/api/backend/expense-applications/${created.application.id}/attachments/${attachments[0]?.id}/content`,
    );
    expect(sourceResponse.status()).toBe(200);
    expect(sourceResponse.headers()["content-type"]).toContain("application/pdf");

    const detailResponse = await manager.request.get(
      `/api/backend/expense-applications/${created.application.id}`,
    );
    expect(detailResponse.status()).toBe(200);
    const detail = await detailResponse.json() as { pendingStepId: string | null };
    expect(detail.pendingStepId).not.toBeNull();
    const returnedResponse = await manager.request.post(
      `/api/backend/expense-approvals/${detail.pendingStepId}/return`,
      { data: { comment: "自動入力内容を再確認してください" } },
    );
    expect(returnedResponse.status()).toBe(200);
  } finally {
    await managerContext.close();
  }

  await page.goto(`/expenses/${created.application.id}`);
  await expect(page.getByText("差戻し", { exact: true }).first()).toBeVisible();
  await page.getByRole("link", { name: "編集", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(
    `/expenses/auto-entry/confirm/${created.application.id}$`,
  ));
  await expect(page.getByRole("region", { name: "receipt.pdfのプレビュー" }).locator("iframe"))
    .toBeVisible();
  await expect(attentionFilterSwitch(page)).not.toBeChecked();
  await expect(page.getByLabel("請求社 / 発行元", { exact: true }))
    .toHaveValue("最終編集済み発行元");
  await page.getByLabel("請求社 / 発行元", { exact: true }).fill("差戻し後の発行元");

  let ambiguousResubmitAttempts = 0;
  const resubmitPath = `**/api/backend/expense-applications/${created.application.id}/resubmit`;
  await page.route(resubmitPath, async (route) => {
    ambiguousResubmitAttempts += 1;
    await route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({ code: "BACKEND_UNAVAILABLE" }),
    });
  });
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "再申請", exact: true }).click();
  await expect(page.getByText("申請結果の確認が必要です", { exact: true })).toBeVisible();
  await expect(page.getByText("再申請結果を確認できませんでした。", { exact: false })).toBeVisible();
  await expect(page.getByText("下書きは保存されていますが、申請できませんでした。", { exact: false }))
    .toHaveCount(0);
  await expect(page.getByRole("button", { name: "再申請", exact: true })).toBeDisabled();
  await expect(page.getByRole("link", { name: "申請詳細を確認", exact: true })).toBeVisible();
  expect(ambiguousResubmitAttempts).toBe(1);
  await page.unroute(resubmitPath);

  await page.getByRole("link", { name: "申請詳細を確認", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/expenses/${created.application.id}$`));
  await page.getByRole("link", { name: "編集", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(
    `/expenses/auto-entry/confirm/${created.application.id}$`,
  ));
  await expect(page.getByLabel("請求社 / 発行元", { exact: true })).toHaveValue("差戻し後の発行元");
  await expect(attentionFilterSwitch(page)).not.toBeChecked();

  const resubmitResponse = page.waitForResponse((candidate) =>
    candidate.url().endsWith(`/api/backend/expense-applications/${created.application.id}/resubmit`)
      && candidate.request().method() === "POST",
  );
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "再申請", exact: true }).click();
  expect((await resubmitResponse).ok()).toBeTruthy();
  await expect(page).toHaveURL(new RegExp(`/expenses/${created.application.id}$`));
  await expect(page.getByText("承認待ち", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("実行 2", { exact: false })).toBeVisible();
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
