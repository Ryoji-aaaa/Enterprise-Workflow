import { resolve } from "node:path";

import { expect, request as playwrightRequest, test, type Browser, type Page } from "@playwright/test";

import { loadStagingPersona } from "../support/staging-persona";

const keycloakUrl = process.env.KEYCLOAK_URL ?? "http://localhost:8180";
const mailpitUrl = process.env.MAILPIT_URL ?? "http://localhost:8025";
const adminEmail = requiredEnvironment("DEV_ADMIN_EMAIL");
const adminPassword = requiredEnvironment("DEV_ADMIN_PASSWORD");
const userEmail = requiredEnvironment("DEV_USER_EMAIL");
const userPassword = requiredEnvironment("DEV_USER_PASSWORD");
const pendingEmail = requiredEnvironment("DEV_PENDING_EMAIL");
const pendingPassword = requiredEnvironment("DEV_PENDING_PASSWORD");
const partTimeEmail = requiredEnvironment("DEV_PART_TIME_EMAIL");
const seedUserPassword = requiredEnvironment("DEV_SEED_USER_PASSWORD");
const notificationSubject = "[Workflow] 未登録ユーザーからアクセスがありました";
const expenseApprovalSubject = "[Workflow] 経費申請の承認依頼";
const expenseUpdateSubject = "[Workflow] 経費申請の更新";

function requiredEnvironment(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Required environment variable ${name} is not set.`);
  }
  return value;
}

async function startOAuthLogin(page: Page): Promise<void> {
  for (let attempt = 1; attempt <= 2; attempt += 1) {
    const signInResponse = page.waitForResponse((response) =>
      response.request().method() === "POST"
        && response.url().includes("/api/auth/sign-in/oauth2"),
    );
    await page.getByRole("button", { name: "ログイン", exact: true }).click();
    const response = await signInResponse;
    if (response.ok()) {
      return;
    }

    expect(response.status(), "OAuth initiation may only be retried after rate limiting").toBe(429);
    expect(attempt, "OAuth initiation remained rate limited after the bounded retry").toBeLessThan(2);
    const retryAfterSeconds = Number(response.headers()["x-retry-after"]);
    expect(retryAfterSeconds).toBeGreaterThan(0);
    expect(retryAfterSeconds).toBeLessThanOrEqual(30);
    await page.waitForTimeout(retryAfterSeconds * 1_000 + 100);
  }
}

async function login(page: Page, email: string, password: string): Promise<void> {
  await page.goto("/login");
  await startOAuthLogin(page);
  await expect(page).toHaveURL(
    new RegExp(
      `^${keycloakUrl.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}/realms/workflow/protocol/openid-connect/auth`,
    ),
  );
  await page.locator("#username").fill(email);
  await page.locator("#password").fill(password);
  await page.locator("#kc-login").click();
}

async function expectExpiredSessionLogin(page: Page): Promise<void> {
  await expect(page).toHaveURL(/\/login\?reason=session-expired$/);
  await expect(page.getByText(
    "セッションの有効期限が切れました。再度ログインしてください。",
    { exact: true },
  )).toBeVisible();
  await expect(page.getByRole("button", { name: "ログイン", exact: true })).toBeVisible();
}

async function expectWorkspaceHeader(page: Page): Promise<void> {
  await expect(page.getByRole("banner")).toBeVisible();
  await expect(page.getByRole("button", { name: "ログアウト", exact: true })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "モバイルナビゲーション" })).toHaveCount(0);
}

async function expectNavigationOrientedWorkspaceChrome(page: Page): Promise<void> {
  await expectWorkspaceHeader(page);
  await expect(page.locator('[data-workspace-layout="navigation-oriented"]')).toBeVisible();
  await expect(page.getByRole("complementary", { name: "サイドメニュー" })).toBeVisible();
  await expect(page.getByRole("button", { name: "メニューを開く", exact: true })).toBeHidden();
}

async function expectContentOrientedWorkspaceChrome(page: Page): Promise<void> {
  await expectWorkspaceHeader(page);
  const workspaceLayout = page.locator('[data-workspace-layout="content-oriented"]');
  await expect(workspaceLayout).toBeVisible();
  await expect(workspaceLayout).toHaveCSS("display", "block");
  await expect(page.getByRole("complementary", { name: "サイドメニュー" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "メニューを開く", exact: true })).toBeVisible();
}

async function expectNoWorkspaceChrome(page: Page): Promise<void> {
  await expect(page.getByRole("navigation", { name: "モバイルナビゲーション" })).toHaveCount(0);
  await expect(page.getByRole("complementary", { name: "サイドメニュー" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "メニューを開く", exact: true })).toHaveCount(0);
}

async function expectActivePersistentNavigationLink(page: Page, name: string): Promise<void> {
  await expect(
    page
      .getByRole("complementary", { name: "サイドメニュー" })
      .getByRole("link", { name, exact: true }),
  ).toHaveAttribute("aria-current", "page");
}

async function openWorkspaceDrawer(page: Page) {
  const trigger = page.getByRole("button", { name: "メニューを開く", exact: true });
  await trigger.click();
  const drawer = page.getByRole("dialog", { name: "ワークスペースメニュー" });
  await expect(drawer).toBeVisible();
  return { drawer, trigger };
}

async function expectActiveDrawerNavigationLink(page: Page, name: string): Promise<void> {
  const { drawer, trigger } = await openWorkspaceDrawer(page);
  await expect(drawer.getByRole("link", { name, exact: true }))
    .toHaveAttribute("aria-current", "page");
  await page.keyboard.press("Escape");
  await expect(drawer).toBeHidden();
  await expect(trigger).toBeFocused();
}

async function navigateFromWorkspaceNavigation(page: Page, name: string): Promise<void> {
  const workspaceLayout = page.locator("[data-workspace-layout]");
  await expect(workspaceLayout).toBeVisible();
  const layoutMode = await workspaceLayout.getAttribute("data-workspace-layout");
  const persistentNavigationVisible = layoutMode === "navigation-oriented"
    && (page.viewportSize()?.width ?? 1280) >= 768;
  const sidebar = page.getByRole("complementary", { name: "サイドメニュー" });
  if (persistentNavigationVisible) {
    await expect(sidebar).toBeVisible();
    await sidebar.getByRole("link", { name, exact: true }).click();
    return;
  }

  const { drawer } = await openWorkspaceDrawer(page);
  await drawer.getByRole("link", { name, exact: true }).click();
  await expect(drawer).toBeHidden();
}

type ExpenseDetail = {
  id: string;
  version: number;
  status: string;
  pendingStepId: string | null;
  approvalRun: { runNumber: number; steps: Array<{ targetOrganizationUnitName: string }> };
};

function expensePayload(title: string, version?: number) {
  return {
    category: "TRANSPORTATION",
    title,
    purpose: "Playwright経費申請シナリオ",
    expenseDate: "2026-08-02",
    remarks: "E2E",
    items: [{
      expenseDate: "2026-08-02", description: "電車往復", amount: 1234,
      origin: "東京", destination: "横浜", transportationType: "TRAIN",
    }],
    ...(version === undefined ? {} : { version }),
  };
}

async function expensePage(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await login(page, email, seedUserPassword);
  await expect(page).toHaveURL(/\/top$/);
  return page;
}

async function createAndSubmitExpense(page: Page, title: string): Promise<ExpenseDetail> {
  const created = await page.request.post("/api/backend/expense-applications", {
    data: expensePayload(title),
  });
  expect(created.status()).toBe(201);
  const draft = (await created.json()) as ExpenseDetail;
  const submitted = await page.request.post(
    `/api/backend/expense-applications/${draft.id}/submit`,
  );
  expect(submitted.status()).toBe(200);
  return (await submitted.json()) as ExpenseDetail;
}

async function searchNotificationCount(
  subject = notificationSubject,
  recipient: string | undefined = adminEmail,
): Promise<number> {
  const context = await playwrightRequest.newContext();
  try {
    const query = new URLSearchParams({
      query: `subject:"${subject}"${recipient ? ` to:"${recipient}"` : ""}`,
    });
    const response = await context.get(`${mailpitUrl}/api/v1/search?${query}`);
    expect(response.ok()).toBeTruthy();
    const body = (await response.json()) as {
      messages_count: number;
      messages: Array<{
        Subject: string;
        To: Array<{ Address: string }>;
      }>;
    };
    if (body.messages_count > 0) {
      expect(body.messages[0]?.Subject).toBe(subject);
      if (recipient) {
        expect(body.messages[0]?.To.some(({ Address }) => Address === recipient)).toBeTruthy();
      }
    }
    return body.messages_count;
  } finally {
    await context.dispose();
  }
}

test("未認証ユーザーをログイン画面へリダイレクトする", async ({ page }) => {
  await page.goto("/top");

  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole("button", { name: "ログイン", exact: true })).toBeVisible();
  await expect(page.getByText(userEmail)).toHaveCount(0);
  await expectNoWorkspaceChrome(page);
});

test("未認証ユーザーをワークスペース画面からログイン画面へリダイレクトする", async ({ page }) => {
  await page.goto("/expenses");

  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole("button", { name: "ログイン", exact: true })).toBeVisible();
  await expectNoWorkspaceChrome(page);
});

test("一般ユーザーがログインしてPoC案内とUIサンプルを表示できる", async ({ page }) => {
  await login(page, userEmail, userPassword);

  await expect(page).toHaveURL(/\/top$/);
  await expectNavigationOrientedWorkspaceChrome(page);
  await expectActivePersistentNavigationLink(page, "トップ");
  await expect(page.getByText("開発一般ユーザー", { exact: true })).toBeVisible();
  const meResponse = await page.request.get("/api/backend/me");
  expect(meResponse.status()).toBe(200);
  const meBody = (await meResponse.json()) as Record<string, unknown>;
  const department = meBody.department as { name: string } | null;
  await page.getByRole("button", {
    name: "開発一般ユーザーのユーザー情報を表示",
  }).click();
  const userMenu = page.getByRole("menu", { name: "ユーザー情報" });
  await expect(userMenu).toBeVisible();
  await expect(userMenu.getByText("開発一般ユーザー", { exact: true })).toBeVisible();
  await expect(userMenu.getByText(userEmail, { exact: true })).toBeVisible();
  await expect(userMenu.getByText(department?.name ?? "所属未設定", { exact: true })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(userMenu).toBeHidden();
  await expect(
    page.getByRole("heading", {
      name: "AIを利用した請求書・注文書からの経費申請自動入力 PoC",
      exact: true,
    }),
  ).toBeVisible();
  await expect(page.getByRole("heading", { name: "操作方法", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "自動入力を試す", exact: true }))
    .toHaveAttribute("href", "/expenses/auto-entry");
  for (const sample of [
    {
      filename: "請求書サンプル_01.png",
      href: "/poc/expense-auto-entry/invoice-sample-01.png",
    },
    {
      filename: "請求書サンプル_02.jpg",
      href: "/poc/expense-auto-entry/invoice-sample-02.jpg",
    },
  ]) {
    const sampleLink = page.getByRole("link", { name: sample.filename, exact: true });
    await expect(sampleLink).toBeVisible();
    await expect(sampleLink).toHaveAttribute("href", sample.href);
    await expect(sampleLink).toHaveAttribute("download", sample.filename);
    const downloadPromise = page.waitForEvent("download");
    await sampleLink.click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBe(sample.filename);
  }

  const authenticationCookies = (await page.context().cookies()).filter((cookie) =>
    /better-auth.*(?:session|account_data)/.test(cookie.name),
  );
  expect(authenticationCookies.some((cookie) => /session/.test(cookie.name))).toBeTruthy();
  expect(authenticationCookies.some((cookie) => /account_data/.test(cookie.name))).toBeTruthy();
  expect(authenticationCookies.every((cookie) => cookie.httpOnly)).toBeTruthy();

  expect(meBody).toMatchObject({
    email: userEmail,
    displayName: "開発一般ユーザー",
  });
  expect(meBody.features).toEqual({ mailNotificationHistory: true });
  expect(meBody.roles).toEqual(expect.arrayContaining([
    "APPLICATION_USER",
    "ORGANIZATION_CHART_VIEWER",
  ]));
  expect(meBody.roles).not.toContain("DOCUMENT_ANALYSIS_USER");
  expect(meBody.permissions).toEqual(expect.arrayContaining([
    "DOCUMENT_ANALYSIS_READ_OWN",
    "DOCUMENT_INTELLIGENCE_ANALYZE",
    "CONTENT_UNDERSTANDING_ANALYZE",
  ]));
  expect(meBody).not.toHaveProperty("accessToken");
  expect(meBody).not.toHaveProperty("refreshToken");
  expect(meBody).not.toHaveProperty("idToken");

  const topResponse = await page.request.get("/top");
  expect(topResponse.status()).toBe(200);
  const cacheControl = topResponse.headers()["cache-control"] ?? "";
  expect(
    cacheControl.includes("no-store")
      || (cacheControl.includes("no-cache") && cacheControl.includes("must-revalidate")),
  ).toBeTruthy();
  expect(await topResponse.text()).not.toMatch(
    /accessToken|refreshToken|idToken|eyJ[A-Za-z0-9_-]+\./,
  );

  let meRequestsAfterTop = 0;
  await page.route("**/api/backend/me", async (route) => {
    meRequestsAfterTop += 1;
    await route.continue();
  });
  await navigateFromWorkspaceNavigation(page, "UIサンプル");
  await expect(page).toHaveURL(/\/ui-samples$/);
  await expect(page.getByRole("heading", { name: "モック文字８", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "モック文字９", exact: true })).toBeVisible();
  await expectContentOrientedWorkspaceChrome(page);
  await expectActiveDrawerNavigationLink(page, "UIサンプル");
  await navigateFromWorkspaceNavigation(page, "経費申請");
  await expect(page.getByRole("heading", { name: "経費申請", exact: true })).toBeVisible();
  await expectContentOrientedWorkspaceChrome(page);
  await expectActiveDrawerNavigationLink(page, "経費申請");
  await navigateFromWorkspaceNavigation(page, "組織図");
  await expect(page.getByRole("heading", { name: "組織図", exact: true })).toBeVisible();
  await expectContentOrientedWorkspaceChrome(page);
  await expectActiveDrawerNavigationLink(page, "組織図");
  expect(meRequestsAfterTop).toBe(0);
  await page.unroute("**/api/backend/me");
});

test("Drawerは履歴移動で以前のrouteに戻っても再表示されない", async ({ page }) => {
  await login(page, userEmail, userPassword);
  await expect(page).toHaveURL(/\/top$/);

  await navigateFromWorkspaceNavigation(page, "経費申請");
  await expect(page).toHaveURL(/\/expenses$/);
  const { drawer } = await openWorkspaceDrawer(page);

  await page.goBack();
  await expect(page).toHaveURL(/\/top$/);
  await expect(drawer).toBeHidden();

  await page.goForward();
  await expect(page).toHaveURL(/\/expenses$/);
  await expect(drawer).toBeHidden();

  const { drawer: reopenedDrawer } = await openWorkspaceDrawer(page);
  await expect(reopenedDrawer).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(reopenedDrawer).toBeHidden();
});

test("/topのDrawerはmobileからdesktopへ切り替えると閉じたままになる", async ({ page }) => {
  await login(page, userEmail, userPassword);
  await expect(page).toHaveURL(/\/top$/);
  await page.setViewportSize({ width: 390, height: 844 });

  await expect(page.getByRole("complementary", { name: "サイドメニュー" })).toHaveCount(0);
  const { drawer } = await openWorkspaceDrawer(page);

  await page.setViewportSize({ width: 1280, height: 720 });
  await expect(page.getByRole("complementary", { name: "サイドメニュー" })).toBeVisible();
  await expect(page.getByRole("button", { name: "メニューを開く", exact: true })).toBeHidden();
  await expect(drawer).toBeHidden();
  await expect(page.locator('[data-slot="sheet-overlay"]')).toHaveCount(0);

  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole("button", { name: "メニューを開く", exact: true })).toBeVisible();
  await expect(drawer).toBeHidden();

  const { drawer: reopenedDrawer } = await openWorkspaceDrawer(page);
  await expect(reopenedDrawer).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(reopenedDrawer).toBeHidden();
});

test("一般ユーザーがDocument AnalysisをBFF越しにFake Providerで実行できる", async ({ page }) => {
  test.setTimeout(180_000);

  await page.context().grantPermissions(["clipboard-read", "clipboard-write"]);
  await login(page, userEmail, userPassword);
  await expect(page).toHaveURL(/\/top$/);

  let rawRequests = 0;
  let documentAnalysisPostRequests = 0;
  const externalDocumentAnalysisRequests: string[] = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.pathname === "/api/backend/document-analyses"
        && request.method() === "POST") {
      documentAnalysisPostRequests += 1;
    }
    if (/\.(?:cognitiveservices|services\.ai|openai)\.azure\.com$/.test(url.hostname)
        || /\.blob\.core\.windows\.net$/.test(url.hostname)) {
      externalDocumentAnalysisRequests.push(url.href);
    }
  });
  await page.route("**/api/backend/document-analyses/*/raw-result", async (route) => {
    rawRequests += 1;
    await route.continue();
  });

  await navigateFromWorkspaceNavigation(page, "Document Intelligence");
  await expect(page).toHaveURL(/\/document-intelligence$/);
  await expectContentOrientedWorkspaceChrome(page);
  await expectActiveDrawerNavigationLink(page, "Document Intelligence");
  const runButton = page.getByRole("button", { name: "Run Analysis", exact: true });
  await expect(runButton).toBeDisabled();
  await expect(page.getByTestId("document-analysis-status-indicator")).toHaveCount(0);

  await page.locator("#document-analysis-file-desktop").setInputFiles(resolve("fixtures/receipt.pdf"));
  await expect(page.locator("iframe[title='receipt.pdfのPDFプレビュー']").first()).toBeVisible();
  await expect(runButton).toBeEnabled();
  await expect(page.getByTestId("document-analysis-status-indicator")).toHaveCount(0);
  const createResponse = page.waitForResponse((response) =>
    response.url().includes("/api/backend/document-analyses")
    && response.request().method() === "POST",
  );
  await runButton.click();
  expect((await createResponse).status()).toBe(202);
  expect(documentAnalysisPostRequests).toBe(1);
  await expect(page).toHaveURL(/\/document-intelligence\?analysis=[0-9a-f-]{36}$/);
  const analysisId = new URL(page.url()).searchParams.get("analysis");
  expect(analysisId).toMatch(/^[0-9a-f-]{36}$/);
  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", {
    timeout: 60_000,
  });
  await expect(page.getByTestId("document-analysis-status-indicator").first())
    .toHaveAttribute("data-status", "succeeded");
  await expect(page.getByText(/PO-2026-0001/).first()).toBeVisible();

  await expect(page.getByTestId("document-analysis-file-pane").first()).toHaveCSS("overflow-y", "auto");
  await expect(page.getByTestId("document-analysis-preview-content").first()).toHaveCSS("overflow-y", "auto");
  await expect(page.getByTestId("document-analysis-markdown-content").first()).toHaveCSS("overflow-y", "auto");
  await page.getByRole("button", { name: "Markdownをコピー", exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toContain("# 発注書");

  await page.getByRole("tab", { name: "Paragraphs", exact: true }).first().click();
  await expect(page.getByText("発注番号: PO-2026-0001", { exact: true })).toBeVisible();
  await expect(page.getByTestId("document-analysis-paragraphs-content").first()).toHaveCSS("overflow-y", "auto");
  await page.getByRole("button", { name: "Paragraphsをコピー", exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toContain(
    "paragraph-1,sectionHeading,1,99.0%,発注番号: PO-2026-0001",
  );
  await page.getByRole("tab", { name: "Tables", exact: true }).first().click();
  await expect(page.getByRole("cell", { name: "業務端末", exact: true })).toBeVisible();
  await expect(page.getByTestId("document-analysis-tables-content").first()).toHaveCSS("overflow-y", "auto");
  await page.getByRole("button", { name: "Tablesをコピー", exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toContain(
    "table-0,1,業務端末,2,\"56,000\",\"112,000\"",
  );
  expect(rawRequests).toBe(0);
  await page.getByRole("tab", { name: "Result", exact: true }).first().click();
  await expect(page.getByRole("button", { name: "Resultをコピー", exact: true })).toBeDisabled();
  await expect(page.getByText(/"source": "backend-fake-provider"/).first()).toBeVisible();
  await expect(page.getByTestId("document-analysis-raw-result-content").first()).toHaveCSS("overflow-y", "auto");
  await page.getByRole("button", { name: "Resultをコピー", exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toContain('"source": "backend-fake-provider"');
  expect(rawRequests).toBe(1);
  await page.getByRole("tab", { name: "Markdown", exact: true }).first().click();
  await page.getByRole("tab", { name: "Result", exact: true }).first().click();
  expect(rawRequests).toBe(1);

  await page.reload();
  await expect(page).toHaveURL(new RegExp(`/document-intelligence\\?analysis=${analysisId}$`));
  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", {
    timeout: 60_000,
  });
  await expect(page.getByTestId("document-analysis-status-indicator").first())
    .toHaveAttribute("data-status", "succeeded");
  await expect(page.getByText(/PO-2026-0001/).first()).toBeVisible();
  await expect(page.locator("iframe[title='receipt.pdfのPDFプレビュー']").first()).toBeVisible();
  await page.getByRole("button", { name: /receipt\.pdf/ }).first().click();
  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", {
    timeout: 60_000,
  });

  await navigateFromWorkspaceNavigation(page, "Content Understanding");
  await expect(page).toHaveURL(/\/content-understanding$/);
  await expectContentOrientedWorkspaceChrome(page);
  await expectActiveDrawerNavigationLink(page, "Content Understanding");
  await expect(page.getByRole("heading", { name: "Content Understanding", exact: true })).toBeVisible();
  await expect(page.getByTestId("document-analysis-file-pane").first()).toHaveCSS("overflow-y", "auto");
  await page.locator("#document-analysis-file-desktop").setInputFiles(resolve("fixtures/receipt.pdf"));
  const contentRunButton = page.getByRole("button", { name: "Run Analysis", exact: true });
  await expect(contentRunButton).toBeEnabled();
  const contentCreateResponse = page.waitForResponse((response) =>
    response.url().includes("/api/backend/document-analyses")
    && response.request().method() === "POST",
  );
  await contentRunButton.click();
  expect(((await (await contentCreateResponse).json()) as { provider: string }).provider)
    .toBe("CONTENT_UNDERSTANDING");
  expect(documentAnalysisPostRequests).toBe(2);
  await expect(page).toHaveURL(/\/content-understanding\?analysis=[0-9a-f-]{36}$/);
  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", {
    timeout: 60_000,
  });
  await expect(page.getByTestId("document-analysis-status-indicator").first())
    .toHaveAttribute("data-status", "succeeded");

  await navigateFromWorkspaceNavigation(page, "Document Intelligence");
  await expect(page).toHaveURL(/\/document-intelligence$/);
  await expectContentOrientedWorkspaceChrome(page);
  await expect(page.getByRole("heading", { name: "Document Intelligence", exact: true })).toBeVisible();
  await page.locator("#document-analysis-file-desktop").setInputFiles({
    name: "tiny.png",
    mimeType: "image/png",
    buffer: Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  });
  await expect(page.getByText("tiny.png", { exact: true }).first()).toBeVisible();
  await expect(page.getByRole("button", { name: "Run Analysis", exact: true })).toBeEnabled();
  await page.getByRole("button", { name: "Run Analysis", exact: true }).click();
  expect(documentAnalysisPostRequests).toBe(3);
  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", {
    timeout: 60_000,
  });

  await page.locator("#document-analysis-file-desktop").setInputFiles({
    name: "tiny.jpeg",
    mimeType: "image/jpeg",
    buffer: Buffer.from([0xff, 0xd8, 0xff]),
  });
  await expect(page.getByText("tiny.jpeg", { exact: true }).first()).toBeVisible();
  await expect(page.getByRole("button", { name: "Run Analysis", exact: true })).toBeEnabled();
  await page.getByRole("button", { name: "Run Analysis", exact: true }).click();
  expect(documentAnalysisPostRequests).toBe(4);
  await expect(page.getByLabel("現在の分析状態").first()).toHaveText("Succeeded", {
    timeout: 60_000,
  });

  await page.locator("#document-analysis-file-desktop").setInputFiles({
    name: "unsupported.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("hello"),
  });
  await expect(
    page.getByRole("alert").filter({ hasText: "対応形式はPDF、JPEG、PNGです。" }).first(),
  ).toBeVisible();
  await expect(page.getByTestId("document-analysis-status-indicator").first())
    .toHaveAttribute("data-status", "failed");
  expect(documentAnalysisPostRequests).toBe(4);
  expect(externalDocumentAnalysisRequests).toEqual([]);

  await page.unroute("**/api/backend/document-analyses/*/raw-result");
});

test("Document Analysis UI ShellはモバイルDrawerから到達できる", async ({ page }) => {
  await login(page, userEmail, userPassword);
  await expect(page).toHaveURL(/\/top$/);
  await page.setViewportSize({ width: 390, height: 844 });
  let documentAnalysisPostRequests = 0;
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.pathname === "/api/backend/document-analyses"
        && request.method() === "POST") {
      documentAnalysisPostRequests += 1;
    }
  });

  await expectWorkspaceHeader(page);
  await expect(page.locator('[data-workspace-layout="navigation-oriented"]')).toBeVisible();
  await expect(page.getByRole("complementary", { name: "サイドメニュー" })).toHaveCount(0);
  const { drawer } = await openWorkspaceDrawer(page);
  await expect(drawer.getByRole("link", { name: "トップ" }))
    .toHaveAttribute("aria-current", "page");
  await expect(drawer.getByRole("link", { name: "Document Intelligence" })).toBeVisible();
  await expect(drawer.getByRole("link", { name: "Content Understanding" })).toBeVisible();
  await drawer.getByRole("link", { name: "Content Understanding" }).click();
  await expect(drawer).toBeHidden();
  await expect(page).toHaveURL(/\/content-understanding$/);
  await expectContentOrientedWorkspaceChrome(page);
  await expect(page.getByRole("heading", { name: "Content Understanding", exact: true })).toBeVisible();
  await expect(page.getByTestId("document-analysis-file-pane").last()).toHaveCSS("overflow-y", "auto");
  const workbenchTabs = page.getByRole("tablist", { name: "ワークベンチ表示切替" });
  await workbenchTabs.getByRole("tab", { name: "Preview", exact: true }).click();
  await expect(page.getByTestId("document-analysis-preview-content").last()).toHaveCSS("overflow-y", "auto");
  await workbenchTabs.getByRole("tab", { name: "File", exact: true }).click();

  await page.locator("#document-analysis-file-mobile").setInputFiles({
    name: "too-large.pdf",
    mimeType: "application/pdf",
    buffer: Buffer.alloc(10 * 1024 * 1024 + 1),
  });
  await expect(
    page.getByRole("alert").filter({ hasText: "ファイルサイズは10 MiB以下にしてください。" }),
  ).toBeVisible();
  expect(documentAnalysisPostRequests).toBe(0);
});

test("token更新不能時はtopとの往復をせず期限切れログインへ戻る", async ({ page }) => {
  await login(page, userEmail, userPassword);
  await expect(page).toHaveURL(/\/top$/);
  await expect(page.getByText("開発一般ユーザー", { exact: true })).toBeVisible();
  await page.route("**/api/backend/me", (route) => route.fulfill({
    status: 401,
    contentType: "application/json",
    body: JSON.stringify({
      code: "AUTHENTICATION_REQUIRED",
      message: "再度ログインしてください。",
    }),
  }));

  await page.goto("/top");
  await expectExpiredSessionLogin(page);
  expect((await page.context().cookies()).some((cookie) =>
    /better-auth.*session/.test(cookie.name),
  )).toBeTruthy();

  await page.unroute("**/api/backend/me");
  await startOAuthLogin(page);
  await expect(page).toHaveURL(/\/top$/);
  await expect(page.getByText("開発一般ユーザー", { exact: true })).toBeVisible();
});

test("top以外の画面でもBFF 401を期限切れログインへ統一する", async ({ page }) => {
  await login(page, userEmail, userPassword);
  await expect(page).toHaveURL(/\/top$/);
  await page.route("**/api/backend/expense-applications?**", (route) => route.fulfill({
    status: 401,
    contentType: "application/json",
    body: JSON.stringify({
      code: "AUTHENTICATION_REQUIRED",
      message: "再度ログインしてください。",
    }),
  }));

  await page.goto("/expenses");
  await expectExpiredSessionLogin(page);
});

test("無効なBetter Auth sessionへのBFF 401で認証Cookieを削除する", async ({ page }) => {
  await login(page, userEmail, userPassword);
  await expect(page).toHaveURL(/\/top$/);

  const sessionCookies = (await page.context().cookies()).filter((cookie) =>
    /better-auth.*session/.test(cookie.name),
  );
  expect(sessionCookies.length).toBeGreaterThan(0);
  await page.context().addCookies(sessionCookies.map((cookie) => ({
    name: cookie.name,
    value: "invalid-session-cookie",
    domain: cookie.domain,
    path: cookie.path,
    httpOnly: cookie.httpOnly,
    secure: cookie.secure,
    sameSite: cookie.sameSite,
  })));

  const response = await page.request.get("/api/backend/me");
  expect(response.status()).toBe(401);
  expect(response.headers()["cache-control"]).toContain("no-store");
  expect(response.headers()["set-cookie"]).toContain("Max-Age=0");

  const authenticationCookies = (await page.context().cookies()).filter((cookie) =>
    /better-auth.*(?:session|account_data)/.test(cookie.name),
  );
  expect(authenticationCookies).toHaveLength(0);
});

test("経費申請の一般・部門長・事業部長経路と差戻し再申請をBFF越しに処理する", async ({ browser }) => {
  const [applicantPersona, managerPersona, divisionHeadPersona, accountingPersona] = await Promise.all([
    loadStagingPersona("STANDARD_APPLICANT"),
    loadStagingPersona("DEPARTMENT_MANAGER"),
    loadStagingPersona("DIVISION_HEAD"),
    loadStagingPersona("ACCOUNTING_APPROVER"),
  ]);
  const suffix = Date.now();
  const applicant = await expensePage(browser, applicantPersona.email);
  const manager = await expensePage(browser, managerPersona.email);
  const divisionHead = await expensePage(browser, divisionHeadPersona.email);
  const accounting = await expensePage(browser, accountingPersona.email);
  const outsider = await expensePage(browser, divisionHeadPersona.email);
  try {
    const general = await createAndSubmitExpense(applicant, `E2E一般申請-${suffix}`);
    expect(general.approvalRun.steps.map((step) => step.targetOrganizationUnitName))
      .toEqual(["第1SI営業課", "経理課"]);
    expect(general.pendingStepId).not.toBeNull();
    await manager.goto("/approvals");
    await expectContentOrientedWorkspaceChrome(manager);
    await expectActiveDrawerNavigationLink(manager, "承認待ち");
    await expect(manager.getByText(`E2E一般申請-${suffix}`, { exact: true })).toBeVisible();

    const outsiderResponse = await outsider.request.post(
      `/api/backend/expense-approvals/${general.pendingStepId}/approve`, { data: {} },
    );
    expect(outsiderResponse.status()).toBe(403);
    const managerApproved = await manager.request.post(
      `/api/backend/expense-approvals/${general.pendingStepId}/approve`,
      { data: { comment: "E2E部門承認" } },
    );
    expect(managerApproved.status()).toBe(200);
    const accountingDetail = (await managerApproved.json()) as ExpenseDetail;
    const finalApproved = await accounting.request.post(
      `/api/backend/expense-approvals/${accountingDetail.pendingStepId}/approve`, { data: {} },
    );
    expect(finalApproved.status()).toBe(200);
    expect(((await finalApproved.json()) as ExpenseDetail).status).toBe("APPROVED");
    await applicant.goto(`/expenses/${general.id}`);
    await expect(applicant.getByText("承認済み", { exact: true }).first()).toBeVisible();

    const managerApplication = await createAndSubmitExpense(
      manager, `E2E課長申請-${suffix}`,
    );
    expect(managerApplication.approvalRun.steps.map((step) => step.targetOrganizationUnitName))
      .toEqual(["第1SI事業部", "経理課"]);
    const divisionApproved = await divisionHead.request.post(
      `/api/backend/expense-approvals/${managerApplication.pendingStepId}/approve`, { data: {} },
    );
    expect(divisionApproved.status()).toBe(200);
    const managerAccounting = (await divisionApproved.json()) as ExpenseDetail;
    const managerFinal = await accounting.request.post(
      `/api/backend/expense-approvals/${managerAccounting.pendingStepId}/approve`, { data: {} },
    );
    expect(managerFinal.status()).toBe(200);
    expect(((await managerFinal.json()) as ExpenseDetail).status).toBe("APPROVED");

    const divisionApplication = await createAndSubmitExpense(
      divisionHead, `E2E事業部長申請-${suffix}`,
    );
    expect(divisionApplication.approvalRun.steps.map((step) => step.targetOrganizationUnitName))
      .toEqual(["経理課"]);
    const divisionFinal = await accounting.request.post(
      `/api/backend/expense-approvals/${divisionApplication.pendingStepId}/approve`, { data: {} },
    );
    expect(divisionFinal.status()).toBe(200);
    expect(((await divisionFinal.json()) as ExpenseDetail).status).toBe("APPROVED");

    const returnedApplication = await createAndSubmitExpense(
      applicant, `E2E差戻し申請-${suffix}`,
    );
    const returnedResponse = await manager.request.post(
      `/api/backend/expense-approvals/${returnedApplication.pendingStepId}/return`,
      { data: { comment: "E2E差戻し理由" } },
    );
    expect(returnedResponse.status()).toBe(200);
    const returned = (await returnedResponse.json()) as ExpenseDetail;
    expect(returned.status).toBe("RETURNED");
    const updated = await applicant.request.put(
      `/api/backend/expense-applications/${returned.id}`,
      { data: expensePayload(`E2E差戻し再申請-${suffix}`, returned.version) },
    );
    expect(updated.status()).toBe(200);
    const resubmitted = await applicant.request.post(
      `/api/backend/expense-applications/${returned.id}/resubmit`,
    );
    expect(resubmitted.status()).toBe(200);
    const runTwo = (await resubmitted.json()) as ExpenseDetail;
    expect(runTwo.approvalRun.runNumber).toBe(2);
    const runTwoManagerResponse = await manager.request.post(
      `/api/backend/expense-approvals/${runTwo.pendingStepId}/approve`, { data: {} },
    );
    expect(runTwoManagerResponse.status()).toBe(200);
    const runTwoManager = (await runTwoManagerResponse.json()) as ExpenseDetail;
    const runTwoFinalResponse = await accounting.request.post(
      `/api/backend/expense-approvals/${runTwoManager.pendingStepId}/approve`, { data: {} },
    );
    expect(runTwoFinalResponse.status()).toBe(200);
    expect(((await runTwoFinalResponse.json()) as ExpenseDetail).status).toBe("APPROVED");

    const applicantDetailResponse = await applicant.request.get(
      `/api/backend/expense-applications/${returned.id}`,
    );
    expect(applicantDetailResponse.status()).toBe(200);
    const applicantDetail = (await applicantDetailResponse.json()) as ExpenseDetail;
    expect(applicantDetail.status).toBe("APPROVED");
    expect(applicantDetail.approvalRun.runNumber).toBe(2);
    await applicant.goto(`/expenses/${general.id}`);
    await expectContentOrientedWorkspaceChrome(applicant);
    await expectActiveDrawerNavigationLink(applicant, "経費申請");

    await expect.poll(
      () => searchNotificationCount(expenseApprovalSubject, managerPersona.email),
      { message: "経費承認依頼メールを待機する", timeout: 10_000 },
    ).toBeGreaterThan(0);
    await expect.poll(
      () => searchNotificationCount(expenseUpdateSubject, applicantPersona.email),
      { message: "経費申請者向け更新メールを待機する", timeout: 10_000 },
    ).toBeGreaterThan(0);
  } finally {
    await Promise.all([
      applicant.context().close(), manager.context().close(), divisionHead.context().close(),
      accounting.context().close(), outsider.context().close(),
    ]);
  }
});

test("管理者ユーザーの名前を表示して業務ロールをBFFから取得する", async ({ page }) => {
  await login(page, adminEmail, adminPassword);

  await expect(page).toHaveURL(/\/top$/);
  await expectNavigationOrientedWorkspaceChrome(page);
  await expectActivePersistentNavigationLink(page, "トップ");
  await expect(page.getByText("開発管理者", { exact: true })).toBeVisible();

  const meResponse = await page.request.get("/api/backend/me");
  expect(meResponse.status()).toBe(200);
  expect(await meResponse.json()).toMatchObject({
    email: adminEmail,
    displayName: "開発管理者",
    features: { mailNotificationHistory: true },
  });

  await expect(
    page.getByRole("complementary", { name: "サイドメニュー" })
      .getByRole("link", { name: "送付済メール一覧" }),
  ).toBeVisible();
  await navigateFromWorkspaceNavigation(page, "送付済メール一覧");
  await expectContentOrientedWorkspaceChrome(page);
  await expectActiveDrawerNavigationLink(page, "送付済メール一覧");
  await expect(page.getByRole("heading", { name: "送付済メール一覧" })).toBeVisible();
  await expect(page.getByText(/通知履歴（\d+件）/)).toBeVisible();
  await page.getByRole("link", { name: "詳細" }).first().click();
  await expectContentOrientedWorkspaceChrome(page);
  await expectActiveDrawerNavigationLink(page, "送付済メール一覧");
  await expect(page.getByRole("heading", { name: "メール通知詳細" })).toBeVisible();
  await expect(page.getByText("本文", { exact: true })).toBeVisible();
});

test("社長が組織図とユーザー編集を利用しロール変更を監査できる", async ({ page }) => {
  const president = await loadStagingPersona("PRESIDENT");
  await login(page, president.email, seedUserPassword);

  await expect(page).toHaveURL(/\/top$/);
  await expectNavigationOrientedWorkspaceChrome(page);
  await expectActivePersistentNavigationLink(page, "トップ");
  await page.setViewportSize({ width: 390, height: 844 });
  await expectWorkspaceHeader(page);
  await expect(page.getByRole("complementary", { name: "サイドメニュー" })).toHaveCount(0);
  const { drawer: mobileDrawer } = await openWorkspaceDrawer(page);
  await expect(mobileDrawer.getByRole("link", { name: "トップ" }))
    .toHaveAttribute("aria-current", "page");
  await expect(mobileDrawer.getByRole("link", { name: "組織図" })).toBeVisible();
  await expect(mobileDrawer.getByRole("link", { name: "ユーザー管理" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(mobileDrawer).toBeHidden();
  await page.setViewportSize({ width: 1280, height: 720 });
  await expectNavigationOrientedWorkspaceChrome(page);
  const meResponse = await page.request.get("/api/backend/me");
  expect(meResponse.status()).toBe(200);
  const me = (await meResponse.json()) as { id: string };
  await navigateFromWorkspaceNavigation(page, "組織図");
  await expectContentOrientedWorkspaceChrome(page);
  await expectActiveDrawerNavigationLink(page, "組織図");
  await expect(page.getByRole("heading", { name: "組織図" })).toBeVisible();
  const governance = page.getByRole("heading", { name: "統治機関・会議体" });
  await expect(governance).toBeVisible();
  await expect(page.getByText("株主総会", { exact: true })).toBeVisible();
  await expect(page.getByText("監査役会", { exact: true })).toBeVisible();
  await expect(page.getByText("取締役会", { exact: true })).toBeVisible();
  const businessOrganization = page.getByRole("region", { name: "業務執行組織" });
  await expect(businessOrganization.getByText("仮 社長", { exact: true })).toBeVisible();
  await expect(businessOrganization.getByText("管理本部", { exact: true })).toBeVisible();
  await expect(businessOrganization.getByText("株主総会", { exact: true })).toHaveCount(0);
  const presidentEdit = businessOrganization.getByRole("link", {
    name: "仮 社長のユーザー情報を編集",
  });
  await expect(presidentEdit).toBeVisible();

  const internalAuditCard = businessOrganization
    .getByText("内部監査室", { exact: true })
    .locator("xpath=ancestor::*[@data-slot='card'][1]");
  await expect(internalAuditCard.getByRole("link", {
    name: "仮 内部監査室責任者のユーザー情報を編集",
  })).toBeVisible();
  await internalAuditCard.getByText("一般ユーザーを表示（1名）", { exact: true }).click();
  await expect(internalAuditCard.getByRole("link", {
    name: "仮 内部監査室一般のユーザー情報を編集",
  })).toBeVisible();

  await presidentEdit.click();
  await expect(page).toHaveURL(new RegExp(`/admin/users/${me.id}/edit$`));
  await expectContentOrientedWorkspaceChrome(page);
  await expectActiveDrawerNavigationLink(page, "ユーザー管理");
  await expect(page.getByRole("heading", { name: "ユーザー情報編集" })).toBeVisible();

  await page.goto("/top");
  await expectNavigationOrientedWorkspaceChrome(page);
  await expectActivePersistentNavigationLink(page, "トップ");
  await navigateFromWorkspaceNavigation(page, "ユーザー管理");
  await expectContentOrientedWorkspaceChrome(page);
  await expectActiveDrawerNavigationLink(page, "ユーザー管理");
  await expect(page.getByRole("heading", { name: "ユーザー管理" })).toBeVisible();
  await expect(page.getByText(/ユーザー一覧（\d+件）/)).toBeVisible();
  await page.goto(`/admin/users/${me.id}/edit`);
  await expectContentOrientedWorkspaceChrome(page);
  await expectActiveDrawerNavigationLink(page, "ユーザー管理");
  await expect(page.getByRole("heading", { name: "ユーザー情報編集" })).toBeVisible();
  await expect(page.getByLabel("email（変更不可）")).toHaveAttribute("readonly", "");

  const displayName = page.getByLabel("表示名");
  await displayName.fill("仮 社長 E2E");
  await page.getByRole("button", { name: "基本情報を保存" }).click();
  await expect(page.getByText("基本情報を更新しました。", { exact: true })).toBeVisible();

  const roleSelect = page.locator('select[name="roleId"]');
  await roleSelect.selectOption({ label: "Auditor" });
  await page.getByRole("button", { name: "ロール付与" }).click();
  await expect(page.getByText("ロールを付与しました。", { exact: true })).toBeVisible();

  const auditResponse = await page.request.get(
    `/api/backend/admin/audit-logs?actionType=USER_UPDATED&targetId=${me.id}`,
  );
  expect(auditResponse.status()).toBe(200);
  const auditBody = (await auditResponse.json()) as { totalElements: number };
  expect(auditBody.totalElements).toBeGreaterThan(0);

  const currentAuditor = page.locator("div.rounded-lg.border.p-2").filter({
    has: page.getByText("Auditor", { exact: true }),
    hasText: "剥奪",
  });
  await currentAuditor.getByRole("button", { name: "剥奪" }).click();
  await expect(page.getByText("ロールを剥奪しました。", { exact: true })).toBeVisible();

  await displayName.fill("仮 社長");
  await page.getByRole("button", { name: "基本情報を保存" }).click();
  await expect(page.getByText("基本情報を更新しました。", { exact: true })).toBeVisible();
});

test("一般正社員は組織図を閲覧できユーザー管理は表示されない", async ({ page }) => {
  await login(page, userEmail, userPassword);
  await expect(page).toHaveURL(/\/top$/);
  await expectNavigationOrientedWorkspaceChrome(page);

  const meResponse = await page.request.get("/api/backend/me");
  expect(meResponse.status()).toBe(200);
  const me = (await meResponse.json()) as { id: string };
  const sidebarNavigation = page.getByRole("complementary", { name: "サイドメニュー" });
  await expect(sidebarNavigation.getByRole("link", { name: "組織図" })).toBeVisible();
  await expect(sidebarNavigation.getByRole("link", { name: "ユーザー管理" })).toHaveCount(0);
  await expect(sidebarNavigation.getByRole("link", { name: "送付済メール一覧" })).toHaveCount(0);
  await navigateFromWorkspaceNavigation(page, "組織図");
  await expectContentOrientedWorkspaceChrome(page);
  await expect(page.getByText("仮 社長", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: /ユーザー情報を編集/ })).toHaveCount(0);

  const directApiResponse = await page.request.get(`/api/backend/admin/users/${me.id}`);
  expect(directApiResponse.status()).toBe(403);
  const mailHistoryResponse = await page.request.get("/api/backend/admin/mail-notifications");
  expect(mailHistoryResponse.status()).toBe(403);
  await page.goto("/admin/mail-notifications");
  await expect(page.getByText("メール通知履歴を参照する権限がありません（403）。"))
    .toBeVisible();
  await page.goto(`/admin/users/${me.id}/edit`);
  await expect(page.getByText("この情報を管理する権限がありません（403）。")).toBeVisible();
});

test("パートもDocument Analysisを利用できるが組織図は雇用区分で拒否される", async ({ page }) => {
  await login(page, partTimeEmail, seedUserPassword);

  await expect(page).toHaveURL(/\/top$/);
  await expectNavigationOrientedWorkspaceChrome(page);
  const sidebarNavigation = page.getByRole("complementary", { name: "サイドメニュー" });
  await expect(sidebarNavigation.getByRole("link", { name: "組織図" })).toHaveCount(0);
  await expect(sidebarNavigation.getByRole("link", { name: "Document Intelligence" })).toBeVisible();
  await expect(sidebarNavigation.getByRole("link", { name: "Content Understanding" })).toBeVisible();
  await page.goto("/document-intelligence");
  await expectContentOrientedWorkspaceChrome(page);
  await expect(page.getByRole("heading", { name: "Document Intelligence", exact: true }))
    .toBeVisible();
  await page.goto("/organization-chart");
  await expect(page.getByText("このアカウントでは組織図を閲覧できません（403）。"))
    .toBeVisible();
});

test("ログアウト後は認証済みページを再利用できない", async ({ page }) => {
  await login(page, userEmail, userPassword);
  await expect(page.getByText("開発一般ユーザー", { exact: true })).toBeVisible();

  const logoutResponsePromise = page.waitForResponse((response) =>
    response.url().includes("/api/auth/logout"),
  );
  await page.getByRole("button", { name: "ログアウト", exact: true }).click();
  const logoutResponse = await logoutResponsePromise;
  expect(logoutResponse.headers()["cache-control"]).toContain("no-store");
  await page.waitForURL((url) => url.pathname === "/login" || url.origin === keycloakUrl);
  if (new URL(page.url()).origin === keycloakUrl) {
    await page.getByRole("button", { name: "Logout", exact: true }).click();
  }
  await expect(page).toHaveURL(/\/login$/);

  await page.goto("/top");
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByText(userEmail)).toHaveCount(0);
  const expiredAuthenticationCookies = (await page.context().cookies()).filter((cookie) =>
    /better-auth.*(?:session|account_data)/.test(cookie.name),
  );
  expect(expiredAuthenticationCookies.every((cookie) => cookie.value.length === 0)).toBeTruthy();
});

test("未登録ユーザーを記録し通知を重複送信しない", async ({
  page,
}) => {
  await login(page, pendingEmail, pendingPassword);

  await expect(page).toHaveURL(/\/unregistered$/);
  await expect(page.getByRole("heading", { name: "利用申請を受け付けました" })).toBeVisible();
  await expect(
    page.getByText("このアカウントはワークフローアプリに登録されていません。"),
  ).toBeVisible();
  await expectNoWorkspaceChrome(page);

  await expect.poll(searchNotificationCount).toBe(1);

  const firstRepeat = await page.request.get("/api/backend/me");
  const secondRepeat = await page.request.get("/api/backend/me");
  expect(firstRepeat.status()).toBe(403);
  expect(secondRepeat.status()).toBe(403);
  const pendingBody = (await secondRepeat.json()) as Record<string, unknown>;
  expect(pendingBody).not.toHaveProperty("accessToken");
  expect(pendingBody).not.toHaveProperty("refreshToken");
  expect(pendingBody).not.toHaveProperty("idToken");
  await expect.poll(searchNotificationCount).toBe(1);

  await page.goto("/unavailable");
  await expect(page.getByText("このアカウントではワークフローアプリを利用できません")).toBeVisible();
  await expectNoWorkspaceChrome(page);
  await expect(page.locator("body")).not.toContainText(
    /accessToken|refreshToken|idToken|Bearer|backend:8080|Exception|stack/,
  );
});

test("Spring Bootへホストから直接接続できない", async ({
  request,
}) => {
  const connectionError = await request
    .get("http://127.0.0.1:8080/actuator/health", { timeout: 3_000 })
    .then(() => null)
    .catch((error: unknown) => error);

  expect(connectionError).not.toBeNull();
});

test("未認証のBFFリクエストは401になる", async ({ request }) => {
  const response = await request.get("/api/backend/me");

  expect(response.status()).toBe(401);
});

test("allowlist外のBackend APIは認証処理前に拒否する", async ({ request }) => {
  const response = await request.get("/api/backend/actuator/health");

  expect(response.status()).toBe(404);
  await expect(response.json()).resolves.toMatchObject({
    code: "BACKEND_ROUTE_NOT_ALLOWED",
  });
});
