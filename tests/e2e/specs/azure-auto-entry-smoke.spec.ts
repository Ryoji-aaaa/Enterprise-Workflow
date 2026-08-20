import { mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";

import { expect, test, type APIResponse, type Page } from "@playwright/test";

import { extractSafeTopLevelErrorCode } from "../support/safe-diagnostic";
import {
  loadStagingPersona,
  loginAsStagingPersona,
  preflightStagingPersona,
  STANDARD_APPLICANT,
  type PersonaPreflightCheck,
} from "../support/staging-persona";

const liveSmokeEnabled =
  process.env.AZURE_DOCUMENT_ANALYSIS_LIVE_SMOKE === "true";
const summaryPath =
  process.env.DOCUMENT_ANALYSIS_AUTO_ENTRY_LIVE_SMOKE_SUMMARY_PATH;
const diagnosticPath = process.env.DOCUMENT_ANALYSIS_LIVE_SMOKE_DIAGNOSTIC_PATH;
const imageSha = process.env.DOCUMENT_ANALYSIS_LIVE_SMOKE_IMAGE_SHA;
const providerTimeoutMilliseconds = 10 * 60_000;
const forbiddenAzureHosts =
  /\.(?:cognitiveservices\.azure\.com|services\.ai\.azure\.com|openai\.azure\.com|blob\.core\.windows\.net)$/;

type AutoEntryJob = {
  id: string;
  provider: "CONTENT_UNDERSTANDING";
  profile: "AUTO_ENTRY";
  modelId: string;
  providerApiVersion: string;
  status: string;
  createdAt: string;
  completedAt: string | null;
};

type ReviewField<T> = { value: T | null; status: "OK" | "REVIEW" | "MISSING" };
type AutoEntryReview = {
  schemaVersion: string;
  pages: unknown[];
  document: {
    taxBreakdown: ReviewField<Array<{ taxRatePercent: ReviewField<number> }>>;
  };
};

type AutoEntryView = {
  schemaVersion: number;
  provider: "CONTENT_UNDERSTANDING";
  modelId: string;
  providerApiVersion: string;
  status: "SUCCEEDED";
  documents: Array<{ fields: { autoEntry: { schemaVersion: string } } }>;
};

type SafeDiagnostic = {
  stage:
    | "login"
    | "persona-preflight"
    | "submit"
    | "poll"
    | "review"
    | "handoff"
    | "confirmation"
    | "save"
    | "submit-expense";
  personaCode: typeof STANDARD_APPLICANT;
  preflightCheck?: PersonaPreflightCheck;
  provider?: "CONTENT_UNDERSTANDING";
  profile?: "AUTO_ENTRY";
  status?: string;
  apiVersion?: string;
  createdAt?: string;
  completedAt?: string;
  handoffHttpStatus?: number;
  handoffErrorCode?: string;
};

let diagnostic: SafeDiagnostic = {
  stage: "login",
  personaCode: STANDARD_APPLICANT,
};

function requiredEnvironment(name: string): string {
  const value = process.env[name];
  if (!value)
    throw new Error(`Required environment variable ${name} is not set.`);
  return value;
}

function requireCondition(
  condition: unknown,
  message: string,
): asserts condition {
  if (!condition) throw new Error(message);
}

function updateDiagnostic(
  stage: SafeDiagnostic["stage"],
  job?: AutoEntryJob,
): void {
  diagnostic = {
    stage,
    personaCode: STANDARD_APPLICANT,
    ...(job
      ? {
          provider: job.provider,
          profile: job.profile,
          status: job.status,
          apiVersion: job.providerApiVersion,
          createdAt: job.createdAt,
          ...(job.completedAt ? { completedAt: job.completedAt } : {}),
        }
      : {}),
  };
}

function updatePreflightDiagnostic(preflightCheck: PersonaPreflightCheck): void {
  diagnostic = {
    stage: "persona-preflight",
    personaCode: STANDARD_APPLICANT,
    preflightCheck,
  };
}

function updateHandoffDiagnostic(
  job: AutoEntryJob,
  handoffHttpStatus: number,
  handoffErrorCode?: string,
): void {
  updateDiagnostic("handoff", job);
  diagnostic = {
    ...diagnostic,
    handoffHttpStatus,
    ...(handoffErrorCode ? { handoffErrorCode } : {}),
  };
}

async function writeSafeDiagnostic(): Promise<void> {
  if (!diagnosticPath) return;
  await mkdir(dirname(diagnosticPath), { recursive: true });
  await writeFile(diagnosticPath, `${JSON.stringify(diagnostic)}\n`, {
    encoding: "utf8",
    mode: 0o600,
  });
}

async function safeTopLevelErrorCode(response: APIResponse): Promise<string | undefined> {
  try {
    const body = (await response.json()) as unknown;
    return extractSafeTopLevelErrorCode(body);
  } catch {
    return undefined;
  }
}

async function waitForSucceededJob(
  page: Page,
  job: AutoEntryJob,
): Promise<AutoEntryJob> {
  const deadline = Date.now() + providerTimeoutMilliseconds;
  updateDiagnostic("poll", job);
  while (Date.now() < deadline) {
    const response = await page.request.get(
      `/api/backend/document-analyses/${job.id}?profile=AUTO_ENTRY`,
    );
    requireCondition(
      response.status() === 200,
      "AUTO_ENTRY job polling failed.",
    );
    const current = (await response.json()) as AutoEntryJob;
    updateDiagnostic("poll", current);
    if (current.status === "SUCCEEDED") {
      requireCondition(
        Boolean(current.completedAt),
        "AUTO_ENTRY job did not return completedAt.",
      );
      return current;
    }
    if (
      current.status === "FAILED" ||
      current.status === "FAILED_RECOVERY_REQUIRED"
    ) {
      throw new Error("AUTO_ENTRY job reached a terminal failure state.");
    }
    await page.waitForTimeout(5_000);
  }
  throw new Error("AUTO_ENTRY job did not reach SUCCEEDED within 10 minutes.");
}

test.describe("Azure AUTO_ENTRY staging smoke", () => {
  test.skip(
    !liveSmokeEnabled,
    "AZURE_DOCUMENT_ANALYSIS_LIVE_SMOKE=true is required for the billed staging smoke.",
  );
  test.describe.configure({ retries: 0 });

  test.afterEach(async ({}, testInfo) => {
    if (testInfo.status !== testInfo.expectedStatus)
      await writeSafeDiagnostic();
  });

  test("creates, confirms, saves, and submits an AUTO_ENTRY draft only through the BFF", async ({
    page,
  }) => {
    test.setTimeout(17 * 60_000);
    requireCondition(
      /^[0-9a-f]{40}$/.test(imageSha ?? ""),
      "The live smoke image SHA is invalid.",
    );
    const bffOrigin = new URL(requiredEnvironment("BASE_URL")).origin;
    let directAzureRequestCount = 0;
    let directNonBffRequestCount = 0;

    updatePreflightDiagnostic("MANIFEST");
    const persona = await loadStagingPersona(STANDARD_APPLICANT);
    const departmentManager = await loadStagingPersona("DEPARTMENT_MANAGER");
    const accountingApprover = await loadStagingPersona("ACCOUNTING_APPROVER");
    updatePreflightDiagnostic("AUTHENTICATION");
    await loginAsStagingPersona(
      page,
      persona,
      requiredEnvironment("STAGING_SEED_USER_PASSWORD"),
    );
    page.on("request", (request) => {
      const url = new URL(request.url());
      if (forbiddenAzureHosts.test(url.hostname)) directAzureRequestCount += 1;
      if (url.origin !== bffOrigin) directNonBffRequestCount += 1;
    });
    await preflightStagingPersona(page, persona, {
      onCheck: updatePreflightDiagnostic,
      approvalFixtures: { departmentManager, accountingApprover },
    });

    await page.goto("/expenses/auto-entry");
    await expect(page).toHaveURL(/\/expenses\/auto-entry$/);
    await expect(
      page.getByRole("heading", {
        name: "請求書申請(自動入力)",
        exact: true,
      }),
    ).toBeVisible();
    const createdResponse = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === "/api/backend/document-analyses" &&
        response.request().method() === "POST",
    );
    updateDiagnostic("submit");
    await page
      .locator("#expense-auto-entry-file")
      .setInputFiles(
        resolve(
          "../../backend/src/test/resources/document-analysis/auto-entry/v2.1/documents/invoice-02.jpg",
        ),
      );
    const submitted = await createdResponse;
    requireCondition(
      submitted.status() === 202,
      "AUTO_ENTRY job submission failed.",
    );
    const created = (await submitted.json()) as AutoEntryJob;
    requireCondition(
      created.provider === "CONTENT_UNDERSTANDING" &&
        created.profile === "AUTO_ENTRY" &&
        created.modelId === "enterprise_workflow_auto_entry_v2.1.1" &&
        created.providerApiVersion === "2025-11-01",
      "AUTO_ENTRY job contract did not match the staging analyzer.",
    );
    const terminal = await waitForSucceededJob(page, created);
    await expect(page.getByLabel("現在の分析状態").first()).toHaveText(
      "Succeeded",
      {
        timeout: providerTimeoutMilliseconds,
      },
    );

    const viewResponse = await page.request.get(
      `/api/backend/document-analyses/${terminal.id}/view?profile=AUTO_ENTRY`,
    );
    requireCondition(
      viewResponse.status() === 200,
      "AUTO_ENTRY normalized view request failed.",
    );
    const view = (await viewResponse.json()) as AutoEntryView;
    requireCondition(
      view.schemaVersion === 1 &&
        view.provider === "CONTENT_UNDERSTANDING" &&
        view.modelId === "enterprise_workflow_auto_entry_v2.1.1" &&
        view.providerApiVersion === "2025-11-01" &&
        view.status === "SUCCEEDED" &&
        view.documents.length > 0 &&
        view.documents[0]?.fields.autoEntry.schemaVersion === "2.1",
      "AUTO_ENTRY normalized view contract did not match the staging analyzer.",
    );

    updateDiagnostic("review", terminal);
    const reviewResponse = await page.request.get(
      `/api/backend/document-analyses/${terminal.id}/auto-entry-review`,
    );
    requireCondition(
      reviewResponse.status() === 200,
      "AUTO_ENTRY review request failed.",
    );
    const review = (await reviewResponse.json()) as AutoEntryReview;
    requireCondition(
      review.schemaVersion === "2.1" && review.pages.length > 0,
      "AUTO_ENTRY normalized review contract did not match v2.1.",
    );
    for (const breakdown of review.document.taxBreakdown.value ?? []) {
      requireCondition(
        breakdown.taxRatePercent.value !== null ||
          breakdown.taxRatePercent.status === "MISSING",
        "A missing TaxRatePercent must remain MISSING without inference.",
      );
    }
    await expect(
      page.getByText("請求書・注文書の読み取り値", { exact: true }),
    ).toBeVisible();
    await expect(
      page.getByTestId("expense-auto-entry-tax-amount"),
    ).toBeVisible();
    await expect(
      page.getByTestId("expense-auto-entry-adjustments"),
    ).toBeVisible();

    const runLabel = `STAGING AUTO_ENTRY ACCEPTANCE ${Date.now()}`;
    await page.getByRole("button", { name: "すべて", exact: true }).click();
    await page.getByRole("textbox", { name: "件名", exact: true }).fill(runLabel);
    await page
      .getByRole("textbox", { name: "利用目的", exact: true })
      .fill("AUTO_ENTRY staging acceptance");
    await page
      .getByRole("textbox", { name: "内容", exact: true })
      .first()
      .fill("staging acceptance item");
    await page.getByRole("spinbutton", { name: "金額（円）", exact: true }).first().fill("1000");

    updateDiagnostic("handoff", terminal);
    const handoffResponse = page.waitForResponse(
      (response) =>
        response
          .url()
          .endsWith("/api/backend/expense-applications/from-auto-entry") &&
        response.request().method() === "POST",
    );
    page.once("dialog", (dialog) => dialog.accept());
    await page.getByRole("button", { name: "決定", exact: true }).click();
    const handoff = await handoffResponse;
    const handoffHttpStatus = handoff.status();
    const handoffErrorCode = [200, 201].includes(handoffHttpStatus)
      ? undefined
      : await safeTopLevelErrorCode(handoff);
    updateHandoffDiagnostic(terminal, handoffHttpStatus, handoffErrorCode);
    requireCondition(
      [200, 201].includes(handoffHttpStatus),
      "AUTO_ENTRY formal handoff failed.",
    );
    const handoffPayload = handoff.request().postDataJSON() as Record<
      string,
      unknown
    >;
    requireCondition(
      JSON.stringify(Object.keys(handoffPayload).sort()) ===
        JSON.stringify([
          "analysisId",
          "application",
          "confirmedFieldPaths",
          "document",
        ]),
      "AUTO_ENTRY handoff payload included fields outside the formal contract.",
    );
    const handoffBody = (await handoff.json()) as {
      application: { id: string };
    };
    const applicationId = handoffBody.application.id;
    await expect(page).toHaveURL(
      new RegExp(`/expenses/auto-entry/confirm/${applicationId}$`),
    );

    updateDiagnostic("confirmation", terminal);
    await expect(
      page.getByRole("heading", { name: "自動入力の確認", exact: true }),
    ).toBeVisible();
    await expect(
      page
        .getByRole("region", { name: "invoice-02.jpgのプレビュー" })
        .locator("img"),
    ).toHaveAttribute(
      "src",
      new RegExp(
        `/api/backend/expense-applications/${applicationId}/attachments/[0-9a-f-]{36}/content$`,
      ),
    );
    await page.reload();
    await expect(
      page.getByRole("heading", { name: "自動入力の確認", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByTestId("expense-auto-entry-tax-amount"),
    ).toBeVisible();
    await expect(
      page.getByTestId("expense-auto-entry-adjustments"),
    ).toBeVisible();

    await page.getByRole("button", { name: "すべて", exact: true }).click();
    await page
      .getByLabel("請求社 / 発行元", { exact: true })
      .fill("Staging confirmation update");
    updateDiagnostic("save", terminal);
    const saveResponse = page.waitForResponse(
      (response) =>
        response
          .url()
          .endsWith(
            `/api/backend/expense-applications/${applicationId}/auto-entry-draft`,
          ) && response.request().method() === "PUT",
    );
    await page.getByRole("button", { name: "下書き保存", exact: true }).click();
    requireCondition(
      (await saveResponse).ok(),
      "AUTO_ENTRY draft save failed.",
    );
    await page.reload();
    await page.getByRole("button", { name: "すべて", exact: true }).click();
    await expect(
      page.getByLabel("請求社 / 発行元", { exact: true }),
    ).toHaveValue("Staging confirmation update");

    updateDiagnostic("submit-expense", terminal);
    const submitResponse = page.waitForResponse(
      (response) =>
        response
          .url()
          .endsWith(
            `/api/backend/expense-applications/${applicationId}/submit`,
          ) && response.request().method() === "POST",
    );
    page.once("dialog", (dialog) => dialog.accept());
    await page.getByRole("button", { name: "申請", exact: true }).click();
    requireCondition(
      (await submitResponse).ok(),
      "AUTO_ENTRY expense submit failed.",
    );
    await expect(page).toHaveURL(new RegExp(`/expenses/${applicationId}$`));
    await expect(
      page.getByText("承認待ち", { exact: true }).first(),
    ).toBeVisible();
    requireCondition(
      directAzureRequestCount === 0,
      "Browser made a direct Azure or Blob request.",
    );
    requireCondition(
      directNonBffRequestCount === 0,
      "Browser made a direct request outside the Frontend BFF.",
    );

    if (summaryPath) {
      await mkdir(dirname(summaryPath), { recursive: true });
      await writeFile(
        summaryPath,
        `${JSON.stringify({
          imageSha,
          personaCode: STANDARD_APPLICANT,
          autoEntry: {
            provider: terminal.provider,
            profile: terminal.profile,
            analyzerId: terminal.modelId,
            apiVersion: terminal.providerApiVersion,
            status: terminal.status,
            outerSchemaVersion: view.schemaVersion,
            autoEntrySchemaVersion:
              view.documents[0]?.fields.autoEntry.schemaVersion,
            handoffStatus: handoff.status(),
            expenseStatus: "PENDING_APPROVAL",
            createdAt: terminal.createdAt,
            completedAt: terminal.completedAt,
          },
        })}\n`,
        { encoding: "utf8", mode: 0o600 },
      );
    }
  });
});
