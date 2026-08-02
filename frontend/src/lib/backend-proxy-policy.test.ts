import assert from "node:assert/strict";
import test from "node:test";

import { isAllowedBackendProxyRequest } from "./backend-proxy-policy.ts";

const USER_ID = "123e4567-e89b-42d3-a456-426614174000";
const ASSIGNMENT_ID = "123e4567-e89b-42d3-a456-426614174001";

test("組織図とユーザー管理に必要なメソッドとパスだけを許可する", () => {
  assert.equal(isAllowedBackendProxyRequest("GET", "/me"), true);
  assert.equal(isAllowedBackendProxyRequest("GET", "/organization-chart"), true);
  assert.equal(isAllowedBackendProxyRequest("GET", "/admin/users"), true);
  assert.equal(isAllowedBackendProxyRequest("GET", "/admin/audit-logs"), true);
  assert.equal(isAllowedBackendProxyRequest("PATCH", `/admin/users/${USER_ID}`), true);
  assert.equal(
    isAllowedBackendProxyRequest(
      "DELETE",
      `/admin/users/${USER_ID}/organization-assignments/${ASSIGNMENT_ID}`,
    ),
    true,
  );
});

test("経費申請と承認に必要なパスだけを許可する", () => {
  assert.equal(isAllowedBackendProxyRequest("GET", "/expense-applications"), true);
  assert.equal(isAllowedBackendProxyRequest("POST", "/expense-applications"), true);
  assert.equal(isAllowedBackendProxyRequest("PUT", `/expense-applications/${USER_ID}`), true);
  assert.equal(
    isAllowedBackendProxyRequest("POST", `/expense-applications/${USER_ID}/submit`),
    true,
  );
  assert.equal(isAllowedBackendProxyRequest("GET", "/expense-approvals/pending"), true);
  assert.equal(
    isAllowedBackendProxyRequest("POST", `/expense-approvals/${ASSIGNMENT_ID}/approve`),
    true,
  );
  assert.equal(isAllowedBackendProxyRequest("DELETE", `/expense-applications/${USER_ID}`), false);
});

test("任意のBackend API、未許可メソッド、不正なIDを拒否する", () => {
  assert.equal(isAllowedBackendProxyRequest("GET", "/actuator/health"), false);
  assert.equal(isAllowedBackendProxyRequest("DELETE", "/admin/users"), false);
  assert.equal(isAllowedBackendProxyRequest("GET", "/admin/users/not-a-uuid"), false);
  assert.equal(isAllowedBackendProxyRequest("GET", "/../actuator/health"), false);
});
