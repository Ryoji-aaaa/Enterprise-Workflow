import { writeFile } from "node:fs/promises";
import { resolve } from "node:path";

import { expect, test, type APIResponse, type Page } from "@playwright/test";

const liveSmokeEnabled = process.env.AZURE_DOCUMENT_ANALYSIS_LIVE_SMOKE === "true";
const summaryPath = process.env.DOCUMENT_ANALYSIS_LIVE_SMOKE_SUMMARY_PATH;
const forbiddenAzureHosts = /\.(?:cognitiveservices\.azure\.com|services\.ai\.azure\.com|openai\.azure\.com|blob\.core\.windows\.net)$/;

type Job = {
  id: string;
  provider: "DOCUMENT_INTELLIGENCE" | "CONTENT_UNDERSTANDING";
  modelId: string;
  providerApiVersion: string;
  status: string;
  createdAt: string;
  completedAt: string | null;
};

type View = {
  schemaVersion: number;
  provider: Job["provider"];
  modelId: string;
  providerApiVersion: string;
  status: string;
  documents: Array<{ markdown?: string }>;
};

function requiredEnvironment(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Required environment variable ${name} is not set.`);
  return value;
}

async function login(page: Page, email: string, password: string): Promise<void> {
  const issuer = requiredEnvironment("KEYCLOAK_URL");
  await page.goto("/login");
  await page.getByRole("button", { name: "ログイン", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(
    `^${issuer.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}/realms/workflow/protocol/openid-connect/auth`,
  ));
  await page.locator("#username").fill(email);
  await page.locator("#password").fill(password);
  await page.locator("#kc-login").click();
  await expect(page).toHaveURL(/\/top$/);
}

async function requireDocumentAnalysisCapabilities(page: Page): Promise<void> {
  const response = await page.request.get("/api/backend/me");
  expect(response.status()).toBe(200);
  const user = (await response.json()) as {
    permissions: string[];
    features: { documentIntelligence: boolean; contentUnderstanding: boolean };
  };
  expect(user.features).toMatchObject({ documentIntelligence: true, contentUnderstanding: true });
  expect(user.permissions).toEqual(expect.arrayContaining([
    "DOCUMENT_INTELLIGENCE_ANALYZE",
    "CONTENT_UNDERSTANDING_ANALYZE",
  ]));
}

async function analyze(
  page: Page,
  route: "/document-intelligence" | "/content-understanding",
  expectedProvider: Job["provider"],
  expectedApiVersion: string,
): Promise<Job> {
  await page.goto(route);
  await expect(page).toHaveURL(new RegExp(`${route}$`));
  await page.locator("#document-analysis-file-desktop").setInputFiles(resolve("fixtures/receipt.pdf"));
  const submit = page.waitForResponse((response) =>
    new URL(response.url()).pathname === "/api/backend/document-analyses"
      && response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Run Analysis", exact: true }).click();
  const submitted = await submit;
  expect(submitted.status()).toBe(202);
  const job = (await submitted.json()) as Job;
  expect(job.provider).toBe(expectedProvider);
  expect(job.modelId).toBe("prebuilt-layout");
  expect(job.providerApiVersion).toBe(expectedApiVersion);

  await expect.poll(async () => {
    const response = await page.request.get(`/api/backend/document-analyses/${job.id}`);
    expect(response.status()).toBe(200);
    return (await response.json()) as Job;
  }, { timeout: 10 * 60_000, intervals: [1_000, 2_000, 5_000] }).toMatchObject({ status: "SUCCEEDED" });

  const viewResponse = await page.request.get(`/api/backend/document-analyses/${job.id}/view`);
  expect(viewResponse.status()).toBe(200);
  const view = (await viewResponse.json()) as View;
  expect(view).toMatchObject({
    schemaVersion: 1,
    provider: expectedProvider,
    modelId: "prebuilt-layout",
    providerApiVersion: expectedApiVersion,
    status: "SUCCEEDED",
  });
  expect(view.documents.length).toBeGreaterThan(0);
  expect(view.documents.some((document) => Boolean(document.markdown?.trim()))).toBeTruthy();

  const rawResponse = await page.request.get(`/api/backend/document-analyses/${job.id}/raw-result`);
  expect(rawResponse.status()).toBe(200);
  return await verifyUiAndReturnJob(page, job, rawResponse, expectedApiVersion);
}

async function verifyUiAndReturnJob(
  page: Page,
  submittedJob: Job,
  rawResponse: APIResponse,
  expectedApiVersion: string,
): Promise<Job> {
  const rawText = await rawResponse.text();
  expect(() => JSON.parse(rawText)).not.toThrow();
  expect(rawText).not.toContain("backend-fake-provider");
  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", {
    timeout: 10 * 60_000,
  });
  await page.getByRole("tab", { name: "Markdown", exact: true }).first().click();
  await expect(page.getByRole("tabpanel").first()).toContainText(/\S/);
  await page.getByRole("tab", { name: "Result", exact: true }).first().click();
  await expect(page.getByRole("tabpanel").first()).toContainText(new RegExp(`"apiVersion": "${expectedApiVersion}"`));
  return {
    ...submittedJob,
    status: "SUCCEEDED",
    completedAt: new Date().toISOString(),
  };
}

test.describe("Azure Document Analysis staging smoke", () => {
  test.skip(!liveSmokeEnabled, "AZURE_DOCUMENT_ANALYSIS_LIVE_SMOKE=true is required for the billed staging smoke.");

  test("runs both GA providers only through the BFF", async ({ page }) => {
    test.setTimeout(11 * 60_000);
    const directAzureRequests: string[] = [];
    page.on("request", (request) => {
      const url = new URL(request.url());
      if (forbiddenAzureHosts.test(url.hostname)) directAzureRequests.push(url.href);
    });

    await login(
      page,
      requiredEnvironment("DOCUMENT_ANALYSIS_SMOKE_USER_EMAIL"),
      requiredEnvironment("DOCUMENT_ANALYSIS_SMOKE_USER_PASSWORD"),
    );
    await requireDocumentAnalysisCapabilities(page);
    const startedAt = new Date().toISOString();
    const documentIntelligence = await analyze(
      page, "/document-intelligence", "DOCUMENT_INTELLIGENCE", "2024-11-30",
    );
    const contentUnderstanding = await analyze(
      page, "/content-understanding", "CONTENT_UNDERSTANDING", "2025-11-01",
    );
    expect(directAzureRequests).toEqual([]);

    if (summaryPath) {
      await writeFile(summaryPath, JSON.stringify({
        startedAt,
        finishedAt: new Date().toISOString(),
        analyses: [documentIntelligence, contentUnderstanding].map((job) => ({
          provider: job.provider,
          analysisId: job.id,
          status: job.status,
          apiVersion: job.providerApiVersion,
          startedAt: job.createdAt,
          finishedAt: job.completedAt,
        })),
      }, null, 2));
    }
  });
});
