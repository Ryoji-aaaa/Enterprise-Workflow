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

test("シナリオ1: 未ログインではTopページを表示しない", async ({ page }) => {
  await page.goto("/top");

  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole("button", { name: "ログイン", exact: true })).toBeVisible();
  await expect(page.getByText(userEmail)).toHaveCount(0);
});

test("シナリオ2: 一般ユーザーがログインして業務情報を表示できる", async ({ page }) => {
  await login(page, userEmail, userPassword);

  await expect(page).toHaveURL(/\/top$/);
  await expect(page.getByText("ようこそ、開発一般ユーザーさん")).toBeVisible();
  await expect(page.getByText(userEmail, { exact: true })).toBeVisible();
  await expect(page.getByText("開発部", { exact: true })).toBeVisible();
  await expect(page.getByText("一般ユーザー", { exact: true })).toBeVisible();
});

test("シナリオ3: 管理者ユーザーの業務ロールをDBから表示する", async ({ page }) => {
  await login(page, adminEmail, adminPassword);

  await expect(page).toHaveURL(/\/top$/);
  await expect(page.getByText("ようこそ、開発管理者さん")).toBeVisible();
  await expect(page.getByText(adminEmail, { exact: true })).toBeVisible();
  await expect(page.getByText("管理者", { exact: true })).toBeVisible();
});

test("シナリオ4: ログアウト後はTopページへ戻れない", async ({ page }) => {
  await login(page, userEmail, userPassword);
  await expect(page.getByText("ようこそ、開発一般ユーザーさん")).toBeVisible();

  await page.getByRole("button", { name: "ログアウト", exact: true }).click();
  await page.waitForURL((url) => url.pathname === "/login" || url.origin === keycloakUrl);
  if (new URL(page.url()).origin === keycloakUrl) {
    await page.getByRole("button", { name: "Logout", exact: true }).click();
  }
  await expect(page).toHaveURL(/\/login$/);

  await page.goto("/top");
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByText(userEmail)).toHaveCount(0);
});

test("シナリオ5・6: 未登録ユーザーを記録し通知を重複送信しない", async ({
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
  await expect.poll(searchNotificationCount).toBe(1);
});

test("シナリオ7: Spring Bootへホストから直接接続できない", async ({
  request,
}) => {
  const connectionError = await request
    .get("http://127.0.0.1:8080/actuator/health", { timeout: 3_000 })
    .then(() => null)
    .catch((error: unknown) => error);

  expect(connectionError).not.toBeNull();
});

test("シナリオ8: 未認証のBFFリクエストは401になる", async ({ request }) => {
  const response = await request.get("/api/backend/me");

  expect(response.status()).toBe(401);
});
