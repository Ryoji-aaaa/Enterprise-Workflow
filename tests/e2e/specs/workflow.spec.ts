import { expect, request as playwrightRequest, test, type Page } from "@playwright/test";

const keycloakUrl = process.env.KEYCLOAK_URL ?? "http://localhost:8180";
const mailpitUrl = process.env.MAILPIT_URL ?? "http://localhost:8025";
const adminEmail = requiredEnvironment("DEV_ADMIN_EMAIL");
const adminPassword = requiredEnvironment("DEV_ADMIN_PASSWORD");
const userEmail = requiredEnvironment("DEV_USER_EMAIL");
const userPassword = requiredEnvironment("DEV_USER_PASSWORD");
const pendingEmail = requiredEnvironment("DEV_PENDING_EMAIL");
const pendingPassword = requiredEnvironment("DEV_PENDING_PASSWORD");
const presidentEmail = requiredEnvironment("DEV_PRESIDENT_EMAIL");
const presidentPassword = requiredEnvironment("DEV_PRESIDENT_PASSWORD");
const partTimeEmail = requiredEnvironment("DEV_PART_TIME_EMAIL");
const partTimePassword = requiredEnvironment("DEV_PART_TIME_PASSWORD");
const notificationSubject = "[Workflow] 未登録ユーザーからアクセスがありました";

function requiredEnvironment(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Required environment variable ${name} is not set.`);
  }
  return value;
}

async function login(page: Page, email: string, password: string): Promise<void> {
  await page.goto("/login");
  for (let attempt = 1; attempt <= 2; attempt += 1) {
    const signInResponse = page.waitForResponse((response) =>
      response.url().includes("/api/auth/sign-in/oauth2"),
    );
    await page.getByRole("button", { name: "ログイン", exact: true }).click();
    const response = await signInResponse;
    if (response.ok()) {
      break;
    }

    expect(response.status(), "OAuth initiation may only be retried after rate limiting").toBe(429);
    expect(attempt, "OAuth initiation remained rate limited after the bounded retry").toBeLessThan(2);
    const retryAfterSeconds = Number(response.headers()["x-retry-after"]);
    expect(retryAfterSeconds).toBeGreaterThan(0);
    expect(retryAfterSeconds).toBeLessThanOrEqual(30);
    await page.waitForTimeout(retryAfterSeconds * 1_000 + 100);
  }
  await expect(page).toHaveURL(
    new RegExp(
      `^${keycloakUrl.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}/realms/workflow/protocol/openid-connect/auth`,
    ),
  );
  await page.locator("#username").fill(email);
  await page.locator("#password").fill(password);
  await page.locator("#kc-login").click();
}

async function searchNotificationCount(): Promise<number> {
  const context = await playwrightRequest.newContext();
  try {
    const query = new URLSearchParams({
      query: `subject:"${notificationSubject}"`,
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
      expect(body.messages[0]?.Subject).toBe(notificationSubject);
      expect(body.messages[0]?.To.some(({ Address }) => Address === adminEmail)).toBeTruthy();
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
});

test("一般ユーザーがログインしてモックダッシュボードを表示できる", async ({ page }) => {
  await login(page, userEmail, userPassword);

  await expect(page).toHaveURL(/\/top$/);
  await expect(page.getByText("開発一般ユーザー", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "モック文字８", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "モック文字９", exact: true }),
  ).toBeVisible();

  const authenticationCookies = (await page.context().cookies()).filter((cookie) =>
    /better-auth.*(?:session|account_data)/.test(cookie.name),
  );
  expect(authenticationCookies.some((cookie) => /session/.test(cookie.name))).toBeTruthy();
  expect(authenticationCookies.some((cookie) => /account_data/.test(cookie.name))).toBeTruthy();
  expect(authenticationCookies.every((cookie) => cookie.httpOnly)).toBeTruthy();

  const meResponse = await page.request.get("/api/backend/me");
  expect(meResponse.status()).toBe(200);
  const meBody = (await meResponse.json()) as Record<string, unknown>;
  expect(meBody).toMatchObject({
    email: userEmail,
    displayName: "開発一般ユーザー",
  });
  expect(meBody.roles).toEqual(expect.arrayContaining([
    "APPLICATION_USER",
    "ORGANIZATION_CHART_VIEWER",
  ]));
  expect(meBody).not.toHaveProperty("accessToken");
  expect(meBody).not.toHaveProperty("refreshToken");
  expect(meBody).not.toHaveProperty("idToken");

  const topResponse = await page.request.get("/top");
  expect(topResponse.status()).toBe(200);
  expect(topResponse.headers()["cache-control"]).toContain("no-store");
  expect(await topResponse.text()).not.toMatch(
    /accessToken|refreshToken|idToken|eyJ[A-Za-z0-9_-]+\./,
  );
});

test("管理者ユーザーの名前を表示して業務ロールをBFFから取得する", async ({ page }) => {
  await login(page, adminEmail, adminPassword);

  await expect(page).toHaveURL(/\/top$/);
  await expect(page.getByText("開発管理者", { exact: true })).toBeVisible();

  const meResponse = await page.request.get("/api/backend/me");
  expect(meResponse.status()).toBe(200);
  expect(await meResponse.json()).toMatchObject({
    email: adminEmail,
    displayName: "開発管理者",
  });
});

test("社長が組織図とユーザー編集を利用しロール変更を監査できる", async ({ page }) => {
  await login(page, presidentEmail, presidentPassword);

  await expect(page).toHaveURL(/\/top$/);
  await page.setViewportSize({ width: 390, height: 844 });
  const mobileNavigation = page.getByRole("navigation", { name: "モバイルナビゲーション" });
  await expect(mobileNavigation.getByRole("link", { name: "組織図" })).toBeVisible();
  await expect(mobileNavigation.getByRole("link", { name: "ユーザー管理" })).toBeVisible();
  await page.setViewportSize({ width: 1280, height: 720 });
  await expect(page.getByRole("link", { name: "組織図" })).toBeVisible();
  const meResponse = await page.request.get("/api/backend/me");
  expect(meResponse.status()).toBe(200);
  const me = (await meResponse.json()) as { id: string };
  await page.getByRole("link", { name: "組織図" }).click();
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
  await expect(page.getByRole("heading", { name: "ユーザー情報編集" })).toBeVisible();

  await page.goto("/top");
  await page.getByRole("link", { name: "ユーザー管理" }).click();
  await expect(page.getByRole("heading", { name: "ユーザー管理" })).toBeVisible();
  await expect(page.getByText(/ユーザー一覧（\d+件）/)).toBeVisible();
  await page.goto(`/admin/users/${me.id}/edit`);
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

  const meResponse = await page.request.get("/api/backend/me");
  expect(meResponse.status()).toBe(200);
  const me = (await meResponse.json()) as { id: string };
  await expect(page.getByRole("link", { name: "組織図" })).toBeVisible();
  await expect(page.getByRole("link", { name: "ユーザー管理" })).toHaveCount(0);
  await page.getByRole("link", { name: "組織図" }).click();
  await expect(page.getByText("仮 社長", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: /ユーザー情報を編集/ })).toHaveCount(0);

  const directApiResponse = await page.request.get(`/api/backend/admin/users/${me.id}`);
  expect(directApiResponse.status()).toBe(403);
  await page.goto(`/admin/users/${me.id}/edit`);
  await expect(page.getByText("この情報を管理する権限がありません（403）。")).toBeVisible();
});

test("パートは組織図メニューがなく直接アクセスも403になる", async ({ page }) => {
  await login(page, partTimeEmail, partTimePassword);

  await expect(page).toHaveURL(/\/top$/);
  await expect(page.getByRole("link", { name: "組織図" })).toHaveCount(0);
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
