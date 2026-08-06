import assert from "node:assert/strict";
import test from "node:test";

import {
  canViewMailNotificationHistory,
  canViewOrganizationChart,
  requestBackendMe,
  type BackendFetch,
  type CurrentUser,
} from "./backend-api.ts";

const currentUser: CurrentUser = {
  id: "00000000-0000-0000-0000-000000000001",
  externalSubject: "subject",
  email: "example.user1@sdcj.co.jp",
  displayName: "開発一般ユーザー",
  employmentType: "REGULAR_EMPLOYEE",
  department: { name: "開発部" },
  roles: ["USER"],
  permissions: ["WORKFLOW_SUBMIT", "ORGANIZATION_CHART_READ"],
  features: { mailNotificationHistory: true },
};

test("組織図メニューは権限を持つ正社員と準社員だけに許可する", () => {
  assert.equal(canViewOrganizationChart(currentUser), true);
  assert.equal(canViewOrganizationChart({
    ...currentUser,
    employmentType: "ASSOCIATE_EMPLOYEE",
  }), true);
  for (const employmentType of ["PART_TIME", "CONTRACT_EMPLOYEE", "SYSTEM"] as const) {
    assert.equal(canViewOrganizationChart({ ...currentUser, employmentType }), false);
  }
  assert.equal(canViewOrganizationChart({ ...currentUser, permissions: [] }), false);
});

test("メール通知履歴はローカル機能フラグとDB権限が両方ある場合だけ許可する", () => {
  const permitted = {
    ...currentUser,
    permissions: [...currentUser.permissions, "MAIL_NOTIFICATION_READ"],
  };
  assert.equal(canViewMailNotificationHistory(permitted), true);
  assert.equal(canViewMailNotificationHistory(currentUser), false);
  assert.equal(canViewMailNotificationHistory({
    ...permitted,
    features: { mailNotificationHistory: false },
  }), false);
});

function response(status: number, body?: unknown): BackendFetch {
  return async () =>
    new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    });
}

test("returns the business user for HTTP 200", async () => {
  const result = await requestBackendMe({
    accessToken: "test-token",
    backendUrl: "http://backend:8080",
    fetchImplementation: response(200, currentUser),
  });

  assert.deepEqual(result, { kind: "success", user: currentUser });
});

test("translates backend HTTP 401", async () => {
  const result = await requestBackendMe({
    accessToken: "test-token",
    backendUrl: "http://backend:8080",
    fetchImplementation: response(401),
  });

  assert.deepEqual(result, { kind: "unauthenticated" });
});

test("translates an unregistered backend HTTP 403", async () => {
  const result = await requestBackendMe({
    accessToken: "test-token",
    backendUrl: "http://backend:8080",
    fetchImplementation: response(403, {
      code: "APPLICATION_USER_NOT_REGISTERED",
    }),
  });

  assert.deepEqual(result, { kind: "unregistered" });
});

test("translates every other backend HTTP 403", async () => {
  const result = await requestBackendMe({
    accessToken: "test-token",
    backendUrl: "http://backend:8080",
    fetchImplementation: response(403, {
      code: "APPLICATION_USER_DISABLED",
    }),
  });

  assert.deepEqual(result, { kind: "forbidden" });
});

test("translates backend HTTP 5xx without exposing the body", async () => {
  const result = await requestBackendMe({
    accessToken: "test-token",
    backendUrl: "http://backend:8080",
    fetchImplementation: response(500, {
      exception: "internal stack and token material",
    }),
  });

  assert.deepEqual(result, { kind: "unavailable" });
});

test("translates connection failures", async () => {
  const result = await requestBackendMe({
    accessToken: "test-token",
    backendUrl: "http://backend:8080",
    fetchImplementation: async () => {
      throw new Error("connection failed");
    },
  });

  assert.deepEqual(result, { kind: "unavailable" });
});

test("aborts a backend request after the configured timeout", async () => {
  const waitingFetch: BackendFetch = async (_input, init) =>
    new Promise((_resolve, reject) => {
      init?.signal?.addEventListener("abort", () => {
        reject(init.signal?.reason);
      });
    });

  const startedAt = Date.now();
  const result = await requestBackendMe({
    accessToken: "test-token",
    backendUrl: "http://backend:8080",
    fetchImplementation: waitingFetch,
    timeoutMilliseconds: 20,
  });

  assert.deepEqual(result, { kind: "unavailable" });
  assert.ok(Date.now() - startedAt < 1_000);
});
