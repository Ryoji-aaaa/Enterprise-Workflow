import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { expect, test, type Browser, type Page } from "@playwright/test";

const keycloakUrl = process.env.KEYCLOAK_URL ?? "http://localhost:8180";
const applicantEmail = requiredEnvironment("DEV_EXPENSE_USER_EMAIL");
const managerEmail = requiredEnvironment("DEV_EXPENSE_MANAGER_EMAIL");
const outsiderEmail = requiredEnvironment("DEV_EXPENSE_OUTSIDER_EMAIL");
const expensePassword = requiredEnvironment("DEV_EXPENSE_PASSWORD");
const receiptPdf = readFileSync(resolve("fixtures/receipt.pdf"));
const receiptPng = Buffer.from(
  readFileSync(resolve("fixtures/receipt.png"), "utf8").trim(),
  "base64",
);

type ExpenseDetail = {
  id: string;
  status: string;
  pendingStepId: string | null;
};

type ExpenseAttachment = {
  id: string;
  originalFileName: string;
  contentType: string;
  fileSize: number;
};

function requiredEnvironment(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Required environment variable ${name} is not set.`);
  return value;
}

async function login(page: Page, email: string): Promise<void> {
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
  await page.locator("#password").fill(expensePassword);
  await page.locator("#kc-login").click();
  await expect(page).toHaveURL(/\/top$/);
}

async function expensePage(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await login(page, email);
  return page;
}

function expensePayload(title: string) {
  return {
    category: "TRANSPORTATION",
    title,
    purpose: "Playwright添付ファイルシナリオ",
    expenseDate: "2026-08-02",
    remarks: "E2E attachment",
    items: [{
      expenseDate: "2026-08-02",
      description: "電車往復",
      amount: 1234,
      origin: "東京",
      destination: "横浜",
      transportationType: "TRAIN",
    }],
  };
}

async function listAttachments(page: Page, applicationId: string) {
  const response = await page.request.get(
    `/api/backend/expense-applications/${applicationId}/attachments`,
  );
  expect(response.status()).toBe(200);
  return (await response.json()) as ExpenseAttachment[];
}

test("Azurite経由で経費証憑を登録・閲覧・差戻し後に差し替えられる", async ({ browser }) => {
  test.setTimeout(90_000);
  const applicant = await expensePage(browser, applicantEmail);
  const manager = await expensePage(browser, managerEmail);
  const outsider = await expensePage(browser, outsiderEmail);
  try {
    const createdResponse = await applicant.request.post(
      "/api/backend/expense-applications",
      { data: expensePayload(`E2E証憑申請-${Date.now()}`) },
    );
    expect(createdResponse.status()).toBe(201);
    const draft = (await createdResponse.json()) as ExpenseDetail;

    await applicant.goto(`/expenses/${draft.id}`);
    const fileInput = applicant.getByLabel(/ファイルを追加/);
    await fileInput.setInputFiles(resolve("fixtures/receipt.pdf"));
    await expect(applicant.getByText("receipt.pdf", { exact: true })).toBeVisible();
    await fileInput.setInputFiles({
      name: "receipt.png",
      mimeType: "image/png",
      buffer: receiptPng,
    });
    const pngCard = applicant.locator("li").filter({ hasText: "receipt.png" });
    await expect(pngCard).toContainText("PNG");
    await expect(pngCard).toContainText(/\d+ B/);
    await expect(pngCard.getByRole("img", { name: "receipt.pngのプレビュー" }))
      .toBeVisible();

    await fileInput.setInputFiles({
      name: "invalid.pdf",
      mimeType: "application/pdf",
      buffer: readFileSync(resolve("fixtures/invalid.pdf")),
    });
    await expect(applicant.getByText(
      "ファイル内容と拡張子が一致しません。",
      { exact: true },
    )).toBeVisible();

    const initialAttachments = await listAttachments(applicant, draft.id);
    expect(initialAttachments.map(({ originalFileName }) => originalFileName))
      .toEqual(["receipt.pdf", "receipt.png"]);
    const pdf = initialAttachments.find(({ contentType }) => contentType === "application/pdf");
    const png = initialAttachments.find(({ contentType }) => contentType === "image/png");
    expect(pdf).toBeDefined();
    expect(png).toBeDefined();

    const pdfContent = await applicant.request.get(
      `/api/backend/expense-applications/${draft.id}/attachments/${pdf?.id}/content`,
    );
    expect(pdfContent.status()).toBe(200);
    expect(pdfContent.headers()["content-type"]).toContain("application/pdf");
    expect(pdfContent.headers()["content-disposition"]).toContain("inline");
    expect(await pdfContent.body()).toEqual(receiptPdf);
    const pngContent = await applicant.request.get(
      `/api/backend/expense-applications/${draft.id}/attachments/${png?.id}/content`,
    );
    expect(pngContent.status()).toBe(200);
    expect(await pngContent.body()).toEqual(receiptPng);

    const pdfCard = applicant.locator("li").filter({ hasText: "receipt.pdf" });
    applicant.once("dialog", (dialog) => dialog.accept());
    await pdfCard.getByRole("button", { name: "削除" }).click();
    await expect(applicant.getByText("receipt.pdf", { exact: true })).toHaveCount(0);

    const reuploaded = await applicant.request.post(
      `/api/backend/expense-applications/${draft.id}/attachments`,
      { multipart: { file: {
        name: "receipt-resubmitted.pdf",
        mimeType: "application/pdf",
        buffer: receiptPdf,
      } } },
    );
    expect(reuploaded.status()).toBe(201);
    const reuploadedAttachment = (await reuploaded.json()) as ExpenseAttachment;

    const submittedResponse = await applicant.request.post(
      `/api/backend/expense-applications/${draft.id}/submit`,
    );
    expect(submittedResponse.status()).toBe(200);
    const submitted = (await submittedResponse.json()) as ExpenseDetail;
    expect(submitted.status).toBe("PENDING_APPROVAL");
    await applicant.reload();
    await expect(applicant.getByLabel(/ファイルを追加/)).toHaveCount(0);
    await expect(applicant.getByRole("button", { name: "削除" })).toHaveCount(0);

    await manager.goto(`/approvals/${draft.id}`);
    await expect(manager.getByText("receipt-resubmitted.pdf", { exact: true })).toBeVisible();
    const managerDownload = await manager.request.get(
      `/api/backend/expense-applications/${draft.id}/attachments/${reuploadedAttachment.id}/content?download=true`,
    );
    expect(managerDownload.status()).toBe(200);
    expect(managerDownload.headers()["content-disposition"]).toContain("attachment");
    expect(await managerDownload.body()).toEqual(receiptPdf);

    const outsiderRead = await outsider.request.get(
      `/api/backend/expense-applications/${draft.id}/attachments/${reuploadedAttachment.id}/content`,
    );
    expect(outsiderRead.status()).toBe(404);

    const returnedResponse = await manager.request.post(
      `/api/backend/expense-approvals/${submitted.pendingStepId}/return`,
      { data: { comment: "証憑を差し替えてください" } },
    );
    expect(returnedResponse.status()).toBe(200);
    expect(((await returnedResponse.json()) as ExpenseDetail).status).toBe("RETURNED");

    await applicant.reload();
    const returnedPdfCard = applicant.locator("li").filter({
      hasText: "receipt-resubmitted.pdf",
    });
    applicant.once("dialog", (dialog) => dialog.accept());
    await returnedPdfCard.getByRole("button", { name: "削除" }).click();
    await expect(returnedPdfCard).toHaveCount(0);
    await applicant.getByLabel(/ファイルを追加/).setInputFiles({
      name: "returned-receipt.pdf",
      mimeType: "application/pdf",
      buffer: receiptPdf,
    });
    await expect(applicant.getByText("returned-receipt.pdf", { exact: true })).toBeVisible();

    const resubmittedResponse = await applicant.request.post(
      `/api/backend/expense-applications/${draft.id}/resubmit`,
    );
    expect(resubmittedResponse.status()).toBe(200);
    expect(((await resubmittedResponse.json()) as ExpenseDetail).status)
      .toBe("PENDING_APPROVAL");
    await applicant.reload();
    await expect(applicant.getByLabel(/ファイルを追加/)).toHaveCount(0);
    await expect(applicant.getByRole("button", { name: "削除" })).toHaveCount(0);
  } finally {
    await Promise.all([
      applicant.context().close(),
      manager.context().close(),
      outsider.context().close(),
    ]);
  }
});
