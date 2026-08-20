import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { expect, test, type Locator, type Page } from "@playwright/test";

import { loadStagingPersona } from "../support/staging-persona";

const keycloakUrl = process.env.KEYCLOAK_URL ?? "http://localhost:8180";
const userEmail = requiredEnvironment("DEV_USER_EMAIL");
const userPassword = requiredEnvironment("DEV_USER_PASSWORD");
const seedUserPassword = requiredEnvironment("DEV_SEED_USER_PASSWORD");
const receiptPdf = readFileSync(resolve("fixtures/receipt.pdf"));

function createTwoPagePdf(): Buffer {
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> /Contents 5 0 R >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> /Contents 6 0 R >>",
    "<< /Length 0 >>\nstream\n\nendstream",
    "<< /Length 0 >>\nstream\n\nendstream",
  ];
  const offsets: number[] = [];
  let content = "%PDF-1.4\n";
  objects.forEach((object, index) => {
    offsets.push(Buffer.byteLength(content, "ascii"));
    content += `${index + 1} 0 obj\n${object}\nendobj\n`;
  });
  const xrefOffset = Buffer.byteLength(content, "ascii");
  content += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  content += offsets.map((offset) => `${String(offset).padStart(10, "0")} 00000 n \n`).join("");
  content += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\n`;
  content += `startxref\n${xrefOffset}\n%%EOF\n`;
  return Buffer.from(content, "ascii");
}

const twoPageReceiptPdf = createTwoPagePdf();

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

async function expectEvidenceInsidePreview(
  evidence: Locator,
  margin = 24,
): Promise<void> {
  await expect.poll(async () => evidence.evaluate((element, visibilityMargin) => {
    const preview = element.closest<HTMLElement>(
      '[data-testid="expense-auto-entry-preview-content"]',
    );
    if (!preview) return false;

    const previewBounds = preview.getBoundingClientRect();
    const evidenceBounds = element.getBoundingClientRect();
    const visibleLeft = previewBounds.left + preview.clientLeft + visibilityMargin;
    const visibleTop = previewBounds.top + preview.clientTop + visibilityMargin;
    const visibleRight = previewBounds.left + preview.clientLeft
      + preview.clientWidth - visibilityMargin;
    const visibleBottom = previewBounds.top + preview.clientTop
      + preview.clientHeight - visibilityMargin;
    return evidenceBounds.left >= visibleLeft - 1
      && evidenceBounds.top >= visibleTop - 1
      && evidenceBounds.right <= visibleRight + 1
      && evidenceBounds.bottom <= visibleBottom + 1;
  }, margin)).toBe(true);
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

async function makeAutoEntryIssuerEvidenceUseSecondPage(page: Page): Promise<void> {
  await page.route("**/api/backend/document-analyses/*/auto-entry-review", async (route) => {
    const response = await route.fetch();
    const review = await response.json() as {
      pages: Array<{
        pageNumber: number;
        width: number;
        height: number;
        unit: string;
        angleDegrees: number | null;
      }>;
      document: {
        issuerName: {
          sources: Array<{
            pageNumber: number;
            polygon: Array<{ x: number; y: number }>;
          }>;
        };
      };
    };
    const firstPage = review.pages[0];
    if (!firstPage) throw new Error("AUTO_ENTRY review must include a page.");
    review.pages = [firstPage, { ...firstPage, pageNumber: 2 }];
    review.document.issuerName.sources = review.document.issuerName.sources.map((source) => ({
      ...source,
      pageNumber: 2,
    }));
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
  const description = page.getByRole("textbox", { name: "内容", exact: true });
  const amount = page.getByRole("spinbutton", { name: "金額（円）", exact: true });
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

  await page.setViewportSize({ width: 1280, height: 800 });
  const applicant = await loadStagingPersona("STANDARD_APPLICANT");
  await login(page, applicant.email, seedUserPassword);
  await makeAutoEntryTaxRegistrationSourceLess(page);
  await page.goto("/expenses/auto-entry");

  const zoomValue = page.getByTestId("expense-auto-entry-zoom-value");
  const zoomOut = page.getByRole("button", { name: "プレビューを縮小", exact: true });
  const zoomIn = page.getByRole("button", { name: "プレビューを拡大", exact: true });
  await expect(zoomValue).toHaveText("100%");
  await expect(zoomOut).toBeDisabled();
  await expect(zoomIn).toBeDisabled();
  const emptyPreview = page.getByTestId("expense-auto-entry-preview-content");
  await expect(emptyPreview).toHaveAttribute("data-pan-available", "false");

  const sampleImageResponse = await page.request.get(
    "/poc/expense-auto-entry/invoice-sample-01.png",
  );
  expect(sampleImageResponse.ok()).toBe(true);
  const sampleImage = await sampleImageResponse.body();

  const createAnalysisResponse = page.waitForResponse((response) =>
    response.url().includes("/api/backend/document-analyses")
      && response.request().method() === "POST",
  );
  await page.locator("#expense-auto-entry-file").setInputFiles({
    name: "receipt.png",
    mimeType: "image/png",
    buffer: sampleImage,
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

  const imageBeforeZoom = await image.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return { height: rect.height, width: rect.width };
  });
  const issuerPolygonBeforeZoom = await issuerEvidence.evaluate((element: SVGPolygonElement) => {
    const bounds = element.getBBox();
    return { height: bounds.height, width: bounds.width };
  });
  await zoomIn.click();
  await expect(zoomValue).toHaveText("125%");
  await expect.poll(async () => image.evaluate((element) => (
    element.getBoundingClientRect().width
  ))).toBeCloseTo(imageBeforeZoom.width * 1.25, 2);
  const imageAfterZoom = await image.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return { height: rect.height, width: rect.width };
  });
  const issuerPolygonAfterZoom = await issuerEvidence.evaluate((element: SVGPolygonElement) => {
    const bounds = element.getBBox();
    return { height: bounds.height, width: bounds.width };
  });
  expect(imageAfterZoom.width / imageBeforeZoom.width).toBeCloseTo(1.25, 2);
  expect(imageAfterZoom.height / imageBeforeZoom.height).toBeCloseTo(1.25, 2);
  expect(issuerPolygonAfterZoom.width / issuerPolygonBeforeZoom.width).toBeCloseTo(1.25, 2);
  expect(issuerPolygonAfterZoom.height / issuerPolygonBeforeZoom.height).toBeCloseTo(1.25, 2);
  expect(await issuerEvidence.getAttribute("points")).not.toMatch(/NaN|Infinity/);

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
  const lineDescription = page.getByRole("textbox", { name: "内容", exact: true }).first();

  const previewCard = page.getByTestId("expense-auto-entry-preview-card");
  const previewCardBeforePageScroll = await previewCard.boundingBox();
  if (!previewCardBeforePageScroll) throw new Error("AUTO_ENTRY preview card bounds are required.");
  await lineDescription.scrollIntoViewIfNeeded();
  expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
  const stickyPreviewBounds = await previewCard.boundingBox();
  const viewport = page.viewportSize();
  if (!stickyPreviewBounds || !viewport) {
    throw new Error("AUTO_ENTRY sticky preview bounds and viewport are required.");
  }
  expect(stickyPreviewBounds.y).toBeGreaterThanOrEqual(64);
  expect(stickyPreviewBounds.y + stickyPreviewBounds.height)
    .toBeLessThanOrEqual(viewport.height + 1);
  expect(stickyPreviewBounds.height).toBeCloseTo(576, 0);
  expect(stickyPreviewBounds.y).toBeLessThan(previewCardBeforePageScroll.y);
  await lineDescription.focus();
  await expect(lineDescriptionEvidence).toHaveAttribute("data-active", "true");
  await expectEvidenceInsidePreview(lineDescriptionEvidence);

  await page.setViewportSize({ width: 1024, height: 600 });
  await lineDescription.scrollIntoViewIfNeeded();
  await expect.poll(async () => (await previewCard.boundingBox())?.height ?? 0)
    .toBeCloseTo(504, 0);
  const lowHeightPreviewBounds = await previewCard.boundingBox();
  if (!lowHeightPreviewBounds) {
    throw new Error("AUTO_ENTRY low-height preview bounds are required.");
  }
  expect(lowHeightPreviewBounds.y).toBeGreaterThanOrEqual(64);
  expect(lowHeightPreviewBounds.y + lowHeightPreviewBounds.height).toBeLessThanOrEqual(601);

  await invoiceTotal.focus();
  await expect(totalEvidence).toHaveAttribute("data-active", "true");
  const lowHeightScrollBeforeFocus = await lineDescriptionEvidence.evaluate((element) => {
    const container = element.closest<HTMLElement>(
      '[data-testid="expense-auto-entry-preview-content"]',
    );
    if (!container) throw new Error("AUTO_ENTRY image preview container is required.");
    const containerBounds = container.getBoundingClientRect();
    const evidenceBounds = element.getBoundingClientRect();
    const evidenceCenterX = evidenceBounds.left - containerBounds.left
      + container.scrollLeft + evidenceBounds.width / 2;
    const evidenceCenterY = evidenceBounds.top - containerBounds.top
      + container.scrollTop + evidenceBounds.height / 2;
    container.scrollLeft = evidenceCenterX < container.scrollWidth / 2
      ? container.scrollWidth - container.clientWidth
      : 0;
    container.scrollTop = evidenceCenterY < container.scrollHeight / 2
      ? container.scrollHeight - container.clientHeight
      : 0;
    return { left: container.scrollLeft, top: container.scrollTop };
  });
  const lineDescriptionIsOutsideLowHeightPreview = await lineDescriptionEvidence.evaluate(
    (element) => {
      const container = element.closest<HTMLElement>(
        '[data-testid="expense-auto-entry-preview-content"]',
      );
      if (!container) return false;
      const containerBounds = container.getBoundingClientRect();
      const evidenceBounds = element.getBoundingClientRect();
      return evidenceBounds.right < containerBounds.left + 24
        || evidenceBounds.left > containerBounds.left + container.clientWidth - 24
        || evidenceBounds.bottom < containerBounds.top + 24
        || evidenceBounds.top > containerBounds.top + container.clientHeight - 24;
    },
  );
  expect(lineDescriptionIsOutsideLowHeightPreview).toBe(true);
  const browserScrollBeforeLowHeightFocus = await page.evaluate(() => window.scrollY);
  await lineDescription.evaluate(
    (element: HTMLInputElement) => element.focus({ preventScroll: true }),
  );
  await expect(lineDescriptionEvidence).toHaveAttribute("data-active", "true");
  await expectEvidenceInsidePreview(lineDescriptionEvidence);
  const lowHeightScrollAfterFocus = await preview.evaluate((element) => ({
    left: element.scrollLeft,
    top: element.scrollTop,
  }));
  expect(lowHeightScrollAfterFocus).not.toEqual(lowHeightScrollBeforeFocus);
  expect(await page.evaluate(() => window.scrollY)).toBe(browserScrollBeforeLowHeightFocus);

  await page.setViewportSize({ width: 1280, height: 800 });
  await page.evaluate(() => window.scrollTo(0, 0));

  await issuerName.focus();
  await expect(issuerEvidence).toHaveAttribute("data-active", "true");
  await expect(totalEvidence).toHaveAttribute("data-active", "false");
  await expect(lineDescriptionEvidence).toHaveAttribute("data-active", "false");

  await invoiceTotal.focus();
  await expect(issuerEvidence).toHaveAttribute("data-active", "false");
  await expect(totalEvidence).toHaveAttribute("data-active", "true");

  await lineDescription.focus();
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
  await page.getByRole("textbox", { name: "内容", exact: true }).last().focus();
  await expect(preview.locator('polygon[data-active="true"]')).toHaveCount(0);

  await filterSwitch.click();
  await expect(filterSwitch).not.toBeChecked();
  await expect(invoiceTotal).toBeVisible();
  await issuerName.fill("編集後の発行元");
  await expect(issuerEvidence).toHaveAttribute("data-active", "true");
  await issuerName.blur();
  await expect(issuerEvidence).toHaveAttribute("data-active", "false");

  await page.getByRole("textbox", { name: "内容", exact: true }).first().focus();
  await expect(lineDescriptionEvidence).toHaveAttribute("data-active", "true");
  await page.getByRole("button", { name: "明細1を削除", exact: true }).click();
  await expect(lineDescriptionEvidence).toHaveCount(0);
  await expect(preview.locator('polygon[data-active="true"]')).toHaveCount(0);

  await expect(zoomValue).toHaveText("125%");
  await zoomOut.click();
  await zoomOut.click();
  await zoomOut.click();
  await expect(zoomValue).toHaveText("50%");
  await expect(zoomOut).toBeDisabled();
  await expect(preview).toHaveAttribute("data-pan-available", "false");
  for (let step = 0; step < 10; step += 1) await zoomIn.click();
  await expect(zoomValue).toHaveText("300%");
  await expect(zoomIn).toBeDisabled();
  await expect(preview).toHaveAttribute("data-pan-available", "true");
  await expect(preview).toHaveCSS("cursor", "grab");

  await issuerName.focus();
  await expect(issuerEvidence).toHaveAttribute("data-active", "true");
  const imagePositionBeforePan = await image.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return { x: bounds.x, y: bounds.y };
  });
  const polygonPositionBeforePan = await issuerEvidence.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return { x: bounds.x, y: bounds.y };
  });
  const previewBounds = await preview.boundingBox();
  if (!previewBounds) throw new Error("AUTO_ENTRY image preview bounds are required.");
  const dragStart = {
    x: previewBounds.x + previewBounds.width * 0.7,
    y: previewBounds.y + previewBounds.height * 0.7,
  };
  await page.mouse.move(dragStart.x, dragStart.y);
  await page.mouse.down();
  await expect(preview).toHaveAttribute("data-panning", "true");
  await expect(preview).toHaveCSS("cursor", "grabbing");
  await page.mouse.move(dragStart.x - 100, dragStart.y - 100, { steps: 5 });
  await page.mouse.up();
  await expect(preview).toHaveAttribute("data-panning", "false");
  const imageScrollAfterPan = await preview.evaluate((element) => ({
    left: element.scrollLeft,
    top: element.scrollTop,
  }));
  expect(imageScrollAfterPan.left).toBeGreaterThan(0);
  expect(imageScrollAfterPan.top).toBeGreaterThan(0);
  const imagePositionAfterPan = await image.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return { x: bounds.x, y: bounds.y };
  });
  const polygonPositionAfterPan = await issuerEvidence.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return { x: bounds.x, y: bounds.y };
  });
  expect(polygonPositionAfterPan.x - imagePositionAfterPan.x)
    .toBeCloseTo(polygonPositionBeforePan.x - imagePositionBeforePan.x, 1);
  expect(polygonPositionAfterPan.y - imagePositionAfterPan.y)
    .toBeCloseTo(polygonPositionBeforePan.y - imagePositionBeforePan.y, 1);
  await page.mouse.wheel(0, 80);
  await expect.poll(async () => preview.evaluate((element) => element.scrollTop))
    .toBeGreaterThan(imageScrollAfterPan.top);

  await issuerName.blur();
  await preview.evaluate((element) => {
    element.scrollLeft = element.scrollWidth - element.clientWidth;
    element.scrollTop = element.scrollHeight - element.clientHeight;
  });
  const imageScrollBeforeFollow = await preview.evaluate((element) => ({
    left: element.scrollLeft,
    top: element.scrollTop,
  }));
  const browserScrollBeforeImageFollow = await page.evaluate(() => window.scrollY);
  await issuerName.evaluate((element: HTMLInputElement) => element.focus({ preventScroll: true }));
  await expect(issuerEvidence).toHaveAttribute("data-active", "true");
  await expectEvidenceInsidePreview(issuerEvidence);
  const imageScrollAfterFollow = await preview.evaluate((element) => ({
    left: element.scrollLeft,
    top: element.scrollTop,
  }));
  expect(imageScrollAfterFollow.left).toBeLessThan(imageScrollBeforeFollow.left);
  expect(imageScrollAfterFollow.top).toBeLessThan(imageScrollBeforeFollow.top);
  expect(await page.evaluate(() => window.scrollY)).toBe(browserScrollBeforeImageFollow);
  const imagePositionAfterFollow = await image.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return { x: bounds.x, y: bounds.y };
  });
  const polygonPositionAfterFollow = await issuerEvidence.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return { x: bounds.x, y: bounds.y };
  });
  expect(polygonPositionAfterFollow.x - imagePositionAfterFollow.x)
    .toBeCloseTo(polygonPositionBeforePan.x - imagePositionBeforePan.x, 1);
  expect(polygonPositionAfterFollow.y - imagePositionAfterFollow.y)
    .toBeCloseTo(polygonPositionBeforePan.y - imagePositionBeforePan.y, 1);

  await issuerName.blur();
  const visibleScrollBeforeRefocus = await preview.evaluate((element) => ({
    left: element.scrollLeft,
    top: element.scrollTop,
  }));
  await issuerName.evaluate((element: HTMLInputElement) => element.focus({ preventScroll: true }));
  await expect(issuerEvidence).toHaveAttribute("data-active", "true");
  await expectEvidenceInsidePreview(issuerEvidence);
  await expect.poll(async () => preview.evaluate((element) => ({
    left: element.scrollLeft,
    top: element.scrollTop,
  }))).toEqual(visibleScrollBeforeRefocus);

  const replacementAnalysisResponse = page.waitForResponse((response) =>
    response.url().includes("/api/backend/document-analyses")
      && response.request().method() === "POST",
  );
  await page.locator("#expense-auto-entry-file").setInputFiles({
    name: "replacement.pdf",
    mimeType: "application/pdf",
    buffer: receiptPdf,
  });
  expect((await replacementAnalysisResponse).ok()).toBe(true);
  await expect(zoomValue).toHaveText("100%");
  await expect(zoomOut).toBeEnabled();
  await expect(zoomIn).toBeEnabled();
  const replacementPreview = page.getByRole("region", {
    name: "replacement.pdfのプレビュー",
  });
  await expect.poll(async () => replacementPreview.evaluate((element) => ({
    left: element.scrollLeft,
    top: element.scrollTop,
  }))).toEqual({ left: 0, top: 0 });

  await page.setViewportSize({ width: 390, height: 844 });
  await expect(previewCard).toHaveCSS("position", "static");
  const mobilePreviewBounds = await previewCard.boundingBox();
  const mobileEditorBounds = await page.getByTestId("expense-auto-entry-editor-card").boundingBox();
  if (!mobilePreviewBounds || !mobileEditorBounds) {
    throw new Error("AUTO_ENTRY mobile preview and editor bounds are required.");
  }
  expect(mobilePreviewBounds.y + mobilePreviewBounds.height)
    .toBeLessThanOrEqual(mobileEditorBounds.y);
});

test("AUTO_ENTRY PDF Previewは全pageとsource overlayを同じ倍率で再描画する", async ({ page }) => {
  test.setTimeout(90_000);

  await page.addInitScript(() => {
    Object.defineProperty(window, "devicePixelRatio", { configurable: true, get: () => 10 });
  });
  const applicant = await loadStagingPersona("STANDARD_APPLICANT");
  await login(page, applicant.email, seedUserPassword);
  await makeAutoEntryIssuerEvidenceUseSecondPage(page);
  await page.goto("/expenses/auto-entry");

  const createAnalysisResponse = page.waitForResponse((response) =>
    response.url().includes("/api/backend/document-analyses")
      && response.request().method() === "POST",
  );
  await page.locator("#expense-auto-entry-file").setInputFiles({
    name: "two-page-receipt.pdf",
    mimeType: "application/pdf",
    buffer: twoPageReceiptPdf,
  });
  expect((await createAnalysisResponse).ok()).toBe(true);

  const preview = page.getByRole("region", { name: "two-page-receipt.pdfのプレビュー" });
  const pdfPages = preview.getByTestId("expense-auto-entry-pdf-page");
  await expect(pdfPages).toHaveCount(2);
  await expect(pdfPages.nth(1)).toBeVisible();
  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", {
    timeout: 60_000,
  });

  const zoomValue = page.getByTestId("expense-auto-entry-zoom-value");
  const zoomOut = page.getByRole("button", { name: "プレビューを縮小", exact: true });
  const zoomIn = page.getByRole("button", { name: "プレビューを拡大", exact: true });
  const issuerEvidence = preview.locator(
    'polygon[data-field-path="document.issuerName"][data-page-number="2"]',
  );
  await expect(zoomValue).toHaveText("100%");
  await expect(issuerEvidence).toHaveCount(1);

  const pageWidthsBeforeZoom = await pdfPages.evaluateAll((elements) => (
    elements.map((element) => element.getBoundingClientRect().width)
  ));
  const canvasWidthsBeforeZoom = await pdfPages.locator("canvas").evaluateAll((elements) => (
    elements.map((element) => element.getBoundingClientRect().width)
  ));
  const issuerPolygonBeforeZoom = await issuerEvidence.evaluate((element: SVGPolygonElement) => {
    const bounds = element.getBBox();
    return { height: bounds.height, width: bounds.width };
  });

  await zoomIn.click();
  await expect(zoomValue).toHaveText("125%");
  await expect.poll(async () => pdfPages.nth(0).evaluate((element) => (
    element.getBoundingClientRect().width
  ))).toBeCloseTo((pageWidthsBeforeZoom[0] ?? 0) * 1.25, 1);
  const pageWidthsAfterZoom = await pdfPages.evaluateAll((elements) => (
    elements.map((element) => element.getBoundingClientRect().width)
  ));
  const canvasWidthsAfterZoom = await pdfPages.locator("canvas").evaluateAll((elements) => (
    elements.map((element) => element.getBoundingClientRect().width)
  ));
  const issuerPolygonAfterZoom = await issuerEvidence.evaluate((element: SVGPolygonElement) => {
    const bounds = element.getBBox();
    return { height: bounds.height, width: bounds.width };
  });
  expect(pageWidthsAfterZoom).toHaveLength(2);
  expect(canvasWidthsAfterZoom).toHaveLength(2);
  pageWidthsAfterZoom.forEach((width, index) => {
    expect(width / (pageWidthsBeforeZoom[index] ?? 1)).toBeCloseTo(1.25, 1);
  });
  canvasWidthsAfterZoom.forEach((width, index) => {
    expect(width / (canvasWidthsBeforeZoom[index] ?? 1)).toBeCloseTo(1.25, 1);
  });
  expect(pageWidthsAfterZoom[0]).toBeCloseTo(pageWidthsAfterZoom[1] ?? 0, 1);
  expect(issuerPolygonAfterZoom.width / issuerPolygonBeforeZoom.width).toBeCloseTo(1.25, 1);
  expect(issuerPolygonAfterZoom.height / issuerPolygonBeforeZoom.height).toBeCloseTo(1.25, 1);
  expect((await issuerEvidence.getAttribute("points")) ?? "").not.toMatch(/NaN|Infinity/);

  await zoomIn.click();
  await zoomIn.click();
  await zoomIn.click();
  await expect(zoomValue).toHaveText("200%");
  await expect.poll(async () => pdfPages.nth(0).evaluate((element) => (
    element.getBoundingClientRect().width
  ))).toBeCloseTo((pageWidthsBeforeZoom[0] ?? 0) * 2, 1);
  const scrollExtent = await preview.evaluate((element) => ({
    clientHeight: element.clientHeight,
    clientWidth: element.clientWidth,
    scrollHeight: element.scrollHeight,
    scrollWidth: element.scrollWidth,
  }));
  expect(scrollExtent.scrollWidth).toBeGreaterThan(scrollExtent.clientWidth);
  expect(scrollExtent.scrollHeight).toBeGreaterThan(scrollExtent.clientHeight);
  await expect(preview).toHaveAttribute("data-pan-available", "true");

  const totalEvidence = preview.locator(
    'polygon[data-field-path="document.totalAmount"][data-page-number="1"]',
  );
  const lineDescriptionEvidence = preview.locator(
    'polygon[data-field-path="document.lineItems[0].itemDescription"][data-page-number="1"]',
  );
  await expect(totalEvidence).toHaveCount(1);
  await expect(lineDescriptionEvidence).toHaveCount(1);
  await page.getByLabel("総請求額（円）", { exact: true }).focus();
  await expect(totalEvidence).toHaveAttribute("data-active", "true");
  const samePagePan = await lineDescriptionEvidence.evaluate((element) => {
    const container = element.closest<HTMLElement>(
      '[data-testid="expense-auto-entry-preview-content"]',
    );
    if (!container) throw new Error("AUTO_ENTRY PDF preview container is required.");
    const containerBounds = container.getBoundingClientRect();
    const evidenceBounds = element.getBoundingClientRect();
    const evidenceCenterX = evidenceBounds.left - containerBounds.left
      + container.scrollLeft + evidenceBounds.width / 2;
    const evidenceCenterY = evidenceBounds.top - containerBounds.top
      + container.scrollTop + evidenceBounds.height / 2;
    container.scrollLeft = evidenceCenterX < container.scrollWidth / 2
      ? container.scrollWidth - container.clientWidth
      : 0;
    container.scrollTop = Math.max(0, Math.min(
      container.scrollHeight - container.clientHeight,
      evidenceCenterY - container.clientHeight / 2,
    ));
    return { left: container.scrollLeft, top: container.scrollTop };
  });
  const lineDescriptionIsOutsideHorizontally = await lineDescriptionEvidence.evaluate((element) => {
    const container = element.closest<HTMLElement>(
      '[data-testid="expense-auto-entry-preview-content"]',
    );
    if (!container) return false;
    const containerBounds = container.getBoundingClientRect();
    const evidenceBounds = element.getBoundingClientRect();
    return evidenceBounds.right < containerBounds.left + 24
      || evidenceBounds.left > containerBounds.left + container.clientWidth - 24;
  });
  expect(lineDescriptionIsOutsideHorizontally).toBe(true);
  const browserScrollBeforeSamePageFocus = await page.evaluate(() => window.scrollY);
  await page.getByRole("textbox", { name: "内容", exact: true }).first()
    .evaluate((element: HTMLInputElement) => element.focus({ preventScroll: true }));
  await expect(lineDescriptionEvidence).toHaveAttribute("data-active", "true");
  await expectEvidenceInsidePreview(lineDescriptionEvidence);
  const samePageFollow = await preview.evaluate((element) => ({
    left: element.scrollLeft,
    top: element.scrollTop,
  }));
  expect(samePageFollow.left).not.toBe(samePagePan.left);
  expect(samePageFollow.top).toBeCloseTo(samePagePan.top, 0);
  expect(await page.evaluate(() => window.scrollY)).toBe(browserScrollBeforeSamePageFocus);

  const secondPage = pdfPages.nth(1);
  const pdfPagePositionBeforePan = await secondPage.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return { x: bounds.x, y: bounds.y };
  });
  const pdfPolygonPositionBeforePan = await issuerEvidence.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return { x: bounds.x, y: bounds.y };
  });
  const pdfPreviewBounds = await preview.boundingBox();
  if (!pdfPreviewBounds) throw new Error("AUTO_ENTRY PDF preview bounds are required.");
  const pdfDragStart = {
    x: pdfPreviewBounds.x + pdfPreviewBounds.width * 0.7,
    y: pdfPreviewBounds.y + pdfPreviewBounds.height * 0.7,
  };
  await page.mouse.move(pdfDragStart.x, pdfDragStart.y);
  await page.mouse.down();
  await expect(preview).toHaveCSS("cursor", "grabbing");
  await page.mouse.move(pdfDragStart.x - 100, pdfDragStart.y - 100, { steps: 5 });
  await page.mouse.up();
  const pdfScrollAfterPan = await preview.evaluate((element) => ({
    left: element.scrollLeft,
    top: element.scrollTop,
  }));
  expect(pdfScrollAfterPan.left).toBeGreaterThan(0);
  expect(pdfScrollAfterPan.top).toBeGreaterThan(0);
  const pdfPagePositionAfterPan = await secondPage.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return { x: bounds.x, y: bounds.y };
  });
  const pdfPolygonPositionAfterPan = await issuerEvidence.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return { x: bounds.x, y: bounds.y };
  });
  expect(pdfPolygonPositionAfterPan.x - pdfPagePositionAfterPan.x)
    .toBeCloseTo(pdfPolygonPositionBeforePan.x - pdfPagePositionBeforePan.x, 1);
  expect(pdfPolygonPositionAfterPan.y - pdfPagePositionAfterPan.y)
    .toBeCloseTo(pdfPolygonPositionBeforePan.y - pdfPagePositionBeforePan.y, 1);

  await zoomIn.click();
  await zoomIn.click();
  await zoomIn.click();
  await zoomIn.click();
  await expect(zoomValue).toHaveText("300%");
  const canvasPixelsAtMaximumZoom = await pdfPages.locator("canvas").evaluateAll((elements) => (
    elements.map((element) => {
      const canvas = element as HTMLCanvasElement;
      return canvas.width * canvas.height;
    })
  ));
  canvasPixelsAtMaximumZoom.forEach((pixelCount) => {
    expect(pixelCount).toBeLessThanOrEqual(16_000_000);
  });
  const issuerName = page.getByLabel("請求社 / 発行元", { exact: true });
  const browserScrollBeforeFocus = await page.evaluate(() => window.scrollY);
  const editorTopBeforeFocus = (await issuerName.boundingBox())?.y;
  const previewScrollBeforeFocus = await preview.evaluate((element) => element.scrollTop);
  await issuerName.evaluate((element: HTMLInputElement) => element.focus({ preventScroll: true }));
  await expect(issuerEvidence).toHaveAttribute("data-active", "true");
  await expect.poll(async () => preview.evaluate((element) => element.scrollTop))
    .toBeGreaterThan(previewScrollBeforeFocus);
  expect(await page.evaluate(() => window.scrollY)).toBe(browserScrollBeforeFocus);
  expect((await issuerName.boundingBox())?.y).toBe(editorTopBeforeFocus);

  await zoomOut.click();
  await expect(zoomValue).toHaveText("275%");
  await expect.poll(async () => pdfPages.nth(0).evaluate((element) => (
    element.getBoundingClientRect().width
  ))).toBeCloseTo((pageWidthsBeforeZoom[0] ?? 0) * 2.75, 1);
  await expect.poll(async () => preview.evaluate((element) => {
    const secondPageElement = element.querySelector<HTMLElement>(
      '[data-testid="expense-auto-entry-pdf-page"][data-page-number="2"]',
    );
    if (!secondPageElement) return false;
    const containerBounds = element.getBoundingClientRect();
    const pageBounds = secondPageElement.getBoundingClientRect();
    return pageBounds.top < containerBounds.bottom && pageBounds.bottom > containerBounds.top;
  })).toBe(true);
  expect(await page.evaluate(() => window.scrollY)).toBe(browserScrollBeforeFocus);
  expect((await issuerName.boundingBox())?.y).toBe(editorTopBeforeFocus);
  await zoomOut.click();
  await zoomOut.click();
  await zoomOut.click();
  await zoomOut.click();
  await zoomOut.click();
  await zoomOut.click();
  await zoomOut.click();
  await expect(zoomValue).toHaveText("100%");
});

test("通常経費フォームも申請結果不明時は再実行を止めて詳細確認へ誘導する", async ({ page }) => {
  test.setTimeout(90_000);

  const applicant = await loadStagingPersona("STANDARD_APPLICANT");
  await login(page, applicant.email, seedUserPassword);
  await page.goto("/expenses/new");
  await page.getByRole("textbox", { name: "件名", exact: true }).fill(`E2E通常申請結果不明-${Date.now()}`);
  await page.getByRole("textbox", { name: "利用目的", exact: true }).fill("結果不明時の再実行防止確認");
  await page.getByRole("textbox", { name: "内容（片道／往復を含む）", exact: true }).fill("電車移動");
  await page.getByRole("spinbutton", { name: "金額（円）", exact: true }).fill("1000");
  await page.getByRole("textbox", { name: "交通手段", exact: true }).fill("電車");
  await page.getByRole("textbox", { name: "出発地", exact: true }).fill("東京");
  await page.getByRole("textbox", { name: "到着地", exact: true }).fill("品川");

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
  await page.getByRole("textbox", { name: "件名", exact: true }).fill(`E2E通常申請再試行-${Date.now()}`);
  await page.getByRole("textbox", { name: "利用目的", exact: true }).fill("保存済みDRAFTの再利用確認");
  await page.getByRole("textbox", { name: "内容（片道／往復を含む）", exact: true }).fill("電車移動");
  await page.getByRole("spinbutton", { name: "金額（円）", exact: true }).fill("1000");
  await page.getByRole("textbox", { name: "交通手段", exact: true }).fill("電車");
  await page.getByRole("textbox", { name: "出発地", exact: true }).fill("東京");
  await page.getByRole("textbox", { name: "到着地", exact: true }).fill("品川");

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

  await expect(page.getByRole("textbox", { name: "内容", exact: true })).toBeVisible();
  await expect(page.getByRole("spinbutton", { name: "金額（円）", exact: true })).toBeVisible();
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
  await expect(page.getByRole("textbox", { name: "店舗名", exact: true })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "参加者", exact: true })).toBeVisible();
  await page.locator("select").first().selectOption("OTHER");

  const originalConfirmation = page.getByRole("checkbox", {
    name: "原本を確認しました",
    exact: true,
  });
  await originalConfirmation.check();
  await expect(originalConfirmation).toBeVisible();
  await expect(originalConfirmation).toBeChecked();
  await originalConfirmation.uncheck();
  await expect(originalConfirmation).toBeVisible();
  await expect(originalConfirmation).not.toBeChecked();

  await page.getByRole("textbox", { name: "件名", exact: true }).fill(`自動入力E2E-${Date.now()}`);
  await page.getByRole("textbox", { name: "利用目的", exact: true }).fill("請求書に基づく業務用備品の精算");
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

  const lineAmount = page.getByRole("spinbutton", { name: "金額（円）", exact: true });
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
  await page.getByRole("textbox", { name: "件名", exact: true }).fill("");
  await expect(submit).toBeDisabled();
  await page.getByRole("textbox", { name: "件名", exact: true }).fill(`自動入力E2E-${Date.now()}（最終確認）`);
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
    const workflowResponse = await manager.request.get(
      `/api/backend/workflow/subjects/EXPENSE_APPLICATION/${created.application.id}/latest`,
    );
    expect(workflowResponse.status()).toBe(200);
    const workflow = await workflowResponse.json() as {
      steps: Array<{ stepId: string; status: string }>;
    };
    const pendingStepId = workflow.steps.find((step) => step.status === "PENDING")?.stepId;
    expect(pendingStepId).toBeDefined();
    await manager.goto(`/approvals/${pendingStepId}`);
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

    const returnedResponse = await manager.request.post(
      `/api/backend/workflow/tasks/${pendingStepId}/return`,
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
