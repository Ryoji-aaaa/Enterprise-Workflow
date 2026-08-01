import { expect, request as playwrightRequest, test, type Page } from "@playwright/test";

const keycloakUrl = process.env.KEYCLOAK_URL ?? "http://localhost:8180";
const mailpitUrl = process.env.MAILPIT_URL ?? "http://localhost:8025";
const adminEmail = requiredEnvironment("DEV_ADMIN_EMAIL");
const adminPassword = requiredEnvironment("DEV_ADMIN_PASSWORD");
const userEmail = requiredEnvironment("DEV_USER_EMAIL");
const userPassword = requiredEnvironment("DEV_USER_PASSWORD");
const pendingEmail = requiredEnvironment("DEV_PENDING_EMAIL");
const pendingPassword = requiredEnvironment("DEV_PENDING_PASSWORD");
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
    roles: ["APPLICATION_USER"],
  });
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
    roles: ["SYSTEM_ADMIN"],
  });
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
