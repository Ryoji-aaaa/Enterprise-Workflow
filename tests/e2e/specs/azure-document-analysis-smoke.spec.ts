import { mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";

import { expect, test, type APIResponse, type Page } from "@playwright/test";

import {
  loadStagingPersona,
  loginAsStagingPersona,
  preflightStagingPersona,
  STANDARD_APPLICANT,
  type PersonaPreflightCheck,
} from "../support/staging-persona";

const liveSmokeEnabled = process.env.AZURE_DOCUMENT_ANALYSIS_LIVE_SMOKE === "true";
const summaryPath = process.env.DOCUMENT_ANALYSIS_LIVE_SMOKE_SUMMARY_PATH;
const diagnosticPath = process.env.DOCUMENT_ANALYSIS_LIVE_SMOKE_DIAGNOSTIC_PATH;
const imageSha = process.env.DOCUMENT_ANALYSIS_LIVE_SMOKE_IMAGE_SHA;
const forbiddenAzureHosts = /\.(?:cognitiveservices\.azure\.com|services\.ai\.azure\.com|openai\.azure\.com|blob\.core\.windows\.net)$/;
const providerTimeoutMilliseconds = 10 * 60_000;

type Provider = "DOCUMENT_INTELLIGENCE" | "CONTENT_UNDERSTANDING";
type Job = {
  id: string;
  provider: Provider;
  modelId: string;
  providerApiVersion: string;
  status: string;
  createdAt: string;
  completedAt: string | null;
};

type View = {
  schemaVersion: number;
  provider: Provider;
  modelId: string;
  providerApiVersion: string;
  status: string;
  documents: Array<{ markdown?: string }>;
};

type SafeDiagnostic = {
  provider?: Provider;
  stage: "login" | "persona-preflight" | "submit" | "poll" | "result" | "ui";
  personaCode: typeof STANDARD_APPLICANT;
  preflightCheck?: PersonaPreflightCheck | "DOCUMENT_ANALYSIS_CONTRACT";
  status?: string;
  apiVersion?: string;
  createdAt?: string;
  completedAt?: string;
};

let diagnostic: SafeDiagnostic = { stage: "login", personaCode: STANDARD_APPLICANT };

function requiredEnvironment(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Required environment variable ${name} is not set.`);
  return value;
}

function requireCondition(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function updateDiagnostic(
  stage: SafeDiagnostic["stage"],
  job?: Pick<Job, "provider" | "status" | "providerApiVersion" | "createdAt" | "completedAt">,
): void {
  diagnostic = {
    stage,
    personaCode: STANDARD_APPLICANT,
    ...(job ? {
      provider: job.provider,
      status: job.status,
      apiVersion: job.providerApiVersion,
      createdAt: job.createdAt,
      ...(job.completedAt ? { completedAt: job.completedAt } : {}),
    } : {}),
  };
}

function updatePreflightDiagnostic(
  preflightCheck: SafeDiagnostic["preflightCheck"],
): void {
  diagnostic = {
    stage: "persona-preflight",
    personaCode: STANDARD_APPLICANT,
    preflightCheck,
  };
}

async function writeSafeDiagnostic(): Promise<void> {
  if (!diagnosticPath) return;
  await mkdir(dirname(diagnosticPath), { recursive: true });
  await writeFile(diagnosticPath, `${JSON.stringify(diagnostic)}\n`, { encoding: "utf8", mode: 0o600 });
}

function requireDocumentAnalysisContract(user: {
  roles: string[];
  features: Record<string, unknown>;
}): void {
  updatePreflightDiagnostic("DOCUMENT_ANALYSIS_CONTRACT");
  requireCondition(
    user.roles.includes("APPLICATION_USER")
      && !user.roles.includes("DOCUMENT_ANALYSIS_USER"),
    "The smoke user must use APPLICATION_USER without the retired Document Analysis role.",
  );
  requireCondition(
    !("documentIntelligence" in user.features)
      && !("contentUnderstanding" in user.features),
    "Document Analysis must not be exposed as a feature flag.",
  );
}

async function waitForTerminalJob(page: Page, job: Job): Promise<Job> {
  const deadline = Date.now() + providerTimeoutMilliseconds;
  updateDiagnostic("poll", job);
  while (Date.now() < deadline) {
    const response = await page.request.get(`/api/backend/document-analyses/${job.id}`);
    requireCondition(response.status() === 200, "Document Analysis job polling failed.");
    const polled = (await response.json()) as Job;
    updateDiagnostic("poll", polled);
    if (polled.status === "SUCCEEDED") {
      requireCondition(Boolean(polled.completedAt), "Document Analysis job did not return completedAt.");
      return polled;
    }
    if (polled.status === "FAILED" || polled.status === "FAILED_RECOVERY_REQUIRED") {
      throw new Error("Document Analysis job reached a terminal failure state.");
    }
    await page.waitForTimeout(5_000);
  }
  throw new Error("Document Analysis job did not reach SUCCEEDED within 10 minutes.");
}

async function analyze(
  page: Page,
  route: "/document-intelligence" | "/content-understanding",
  expectedProvider: Provider,
  expectedApiVersion: string,
): Promise<Job> {
  updateDiagnostic("submit", {
    provider: expectedProvider,
    status: "SUBMITTING",
    providerApiVersion: expectedApiVersion,
    createdAt: "",
    completedAt: null,
  });
  await page.goto(route);
  await expect(page).toHaveURL(new RegExp(`${route}$`));
  await page.locator("#document-analysis-file-desktop").setInputFiles(resolve("fixtures/receipt.pdf"));
  const submit = page.waitForResponse((response) =>
    new URL(response.url()).pathname === "/api/backend/document-analyses"
      && response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Run Analysis", exact: true }).click();
  const submitted = await submit;
  requireCondition(submitted.status() === 202, "Document Analysis job submission failed.");
  const job = (await submitted.json()) as Job;
  requireCondition(
    job.provider === expectedProvider
      && job.modelId === "prebuilt-layout"
      && job.providerApiVersion === expectedApiVersion,
    "Document Analysis job contract did not match the requested provider.",
  );
  const terminalJob = await waitForTerminalJob(page, job);

  updateDiagnostic("result", terminalJob);
  const viewResponse = await page.request.get(`/api/backend/document-analyses/${job.id}/view`);
  requireCondition(viewResponse.status() === 200, "Document Analysis view request failed.");
  const view = (await viewResponse.json()) as View;
  requireCondition(
    view.schemaVersion === 1
      && view.provider === expectedProvider
      && view.modelId === "prebuilt-layout"
      && view.providerApiVersion === expectedApiVersion
      && view.status === "SUCCEEDED",
    "Document Analysis view contract did not match the requested provider.",
  );
  requireCondition(
    view.documents.length > 0 && view.documents.some((document) => Boolean(document.markdown?.trim())),
    "Document Analysis view did not contain Markdown.",
  );

  const rawResponse = await page.request.get(`/api/backend/document-analyses/${job.id}/raw-result`);
  requireCondition(rawResponse.status() === 200, "Document Analysis raw-result request failed.");
  await verifyUiAndReturnTerminalJob(page, terminalJob, rawResponse, expectedApiVersion);
  return terminalJob;
}

async function verifyUiAndReturnTerminalJob(
  page: Page,
  terminalJob: Job,
  rawResponse: APIResponse,
  expectedApiVersion: string,
): Promise<void> {
  updateDiagnostic("ui", terminalJob);
  const rawText = await rawResponse.text();
  let rawResultIsObject = false;
  try {
    const parsed = JSON.parse(rawText) as unknown;
    rawResultIsObject = typeof parsed === "object" && parsed !== null && !Array.isArray(parsed);
  } catch {
    rawResultIsObject = false;
  }
  requireCondition(rawResultIsObject, "Document Analysis raw result must be a JSON object.");
  requireCondition(!rawText.includes("backend-fake-provider"), "Document Analysis raw result must not use the Fake Provider.");
  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", {
    timeout: providerTimeoutMilliseconds,
  });
  await page.getByRole("tab", { name: "Markdown", exact: true }).first().click();
  const markdown = await page.getByRole("tabpanel").first().textContent();
  requireCondition(Boolean(markdown?.trim()), "Document Analysis Markdown tab was empty.");
  await page.getByRole("tab", { name: "Result", exact: true }).first().click();
  await expect(page.getByText(`${terminalJob.modelId} / ${expectedApiVersion}`, { exact: true }).first()).toBeVisible();
  await expect(page.getByRole("tabpanel").first().locator("pre")).toBeVisible();
}

test.describe("Azure Document Analysis staging smoke", () => {
  test.skip(!liveSmokeEnabled, "AZURE_DOCUMENT_ANALYSIS_LIVE_SMOKE=true is required for the billed staging smoke.");

  test.afterEach(async ({}, testInfo) => {
    if (testInfo.status !== testInfo.expectedStatus) await writeSafeDiagnostic();
  });

  test("runs both GA providers only through the BFF", async ({ page }) => {
    test.setTimeout(22 * 60_000);
    requireCondition(/^[0-9a-f]{40}$/.test(imageSha ?? ""), "The live smoke image SHA is invalid.");
    const directAzureRequests: string[] = [];
    page.on("request", (request) => {
      const url = new URL(request.url());
      if (forbiddenAzureHosts.test(url.hostname)) directAzureRequests.push(url.href);
    });

    updatePreflightDiagnostic("MANIFEST");
    const persona = await loadStagingPersona(STANDARD_APPLICANT);
    updatePreflightDiagnostic("AUTHENTICATION");
    await loginAsStagingPersona(
      page,
      persona,
      requiredEnvironment("STAGING_SEED_USER_PASSWORD"),
    );
    const preflight = await preflightStagingPersona(page, persona, {
      onCheck: updatePreflightDiagnostic,
    });
    requireDocumentAnalysisContract(preflight.currentUser);
    const documentIntelligence = await analyze(
      page, "/document-intelligence", "DOCUMENT_INTELLIGENCE", "2024-11-30",
    );
    const contentUnderstanding = await analyze(
      page, "/content-understanding", "CONTENT_UNDERSTANDING", "2025-11-01",
    );
    requireCondition(directAzureRequests.length === 0, "Browser made a direct Azure request.");

    if (summaryPath) {
      await mkdir(dirname(summaryPath), { recursive: true });
      await writeFile(summaryPath, `${JSON.stringify({
        imageSha,
        personaCode: STANDARD_APPLICANT,
        analyses: [documentIntelligence, contentUnderstanding].map((job) => ({
          provider: job.provider,
          status: job.status,
          apiVersion: job.providerApiVersion,
          createdAt: job.createdAt,
          completedAt: job.completedAt,
        })),
      })}\n`, { encoding: "utf8", mode: 0o600 });
    }
  });
});
