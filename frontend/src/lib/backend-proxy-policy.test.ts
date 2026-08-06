import assert from "node:assert/strict";
import test from "node:test";

import {
  backendProxyRequestHeaders,
  backendProxyResponseHeaders,
  hasOversizedBackendProxyBody,
  isAllowedBackendProxyRequest,
  MAX_ATTACHMENT_PROXY_BODY_BYTES,
} from "./backend-proxy-policy.ts";

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

test("添付APIの正しいメソッドとUUIDだけを許可する", () => {
  const collection = `/expense-applications/${USER_ID}/attachments`;
  const item = `${collection}/${ASSIGNMENT_ID}`;
  assert.equal(isAllowedBackendProxyRequest("GET", collection), true);
  assert.equal(isAllowedBackendProxyRequest("POST", collection), true);
  assert.equal(isAllowedBackendProxyRequest("GET", `${item}/content`), true);
  assert.equal(isAllowedBackendProxyRequest("DELETE", item), true);
  assert.equal(isAllowedBackendProxyRequest("PUT", collection), false);
  assert.equal(isAllowedBackendProxyRequest("POST", `${item}/content`), false);
  assert.equal(
    isAllowedBackendProxyRequest("GET", `/expense-applications/not-a-uuid/attachments`),
    false,
  );
});

test("multipart boundaryを維持し添付Content-Length上限を事前検査する", () => {
  const path = `/expense-applications/${USER_ID}/attachments`;
  const source = new Headers({
    "Content-Type": "multipart/form-data; boundary=----browser-boundary",
  });
  const headers = backendProxyRequestHeaders(source, "POST", path);
  assert.equal(
    headers.get("content-type"),
    "multipart/form-data; boundary=----browser-boundary",
  );
  assert.equal(headers.get("accept"), "application/json");
  assert.equal(
    hasOversizedBackendProxyBody("POST", path, String(MAX_ATTACHMENT_PROXY_BODY_BYTES + 1)),
    true,
  );
  assert.equal(
    hasOversizedBackendProxyBody("POST", path, String(MAX_ATTACHMENT_PROXY_BODY_BYTES)),
    false,
  );
  assert.equal(hasOversizedBackendProxyBody("POST", path, null), false);
});

test("添付コンテンツのAcceptと安全なレスポンスヘッダーだけを転送する", () => {
  const path = `/expense-applications/${USER_ID}/attachments/${ASSIGNMENT_ID}/content`;
  const requestHeaders = backendProxyRequestHeaders(
    new Headers({ Accept: "application/pdf" }), "GET", path,
  );
  assert.equal(requestHeaders.get("accept"), "application/pdf");

  const responseHeaders = backendProxyResponseHeaders(new Headers({
    "Content-Type": "application/pdf",
    "Content-Length": "42",
    "Content-Disposition": "inline; filename=receipt.pdf",
    "Cache-Control": "private, no-store",
    "X-Content-Type-Options": "nosniff",
    "X-Internal-Debug": "must-not-leak",
  }));
  assert.equal(responseHeaders.get("content-type"), "application/pdf");
  assert.equal(responseHeaders.get("content-length"), "42");
  assert.equal(responseHeaders.get("content-disposition"), "inline; filename=receipt.pdf");
  assert.equal(responseHeaders.get("cache-control"), "private, no-store");
  assert.equal(responseHeaders.get("x-content-type-options"), "nosniff");
  assert.equal(responseHeaders.has("x-internal-debug"), false);
});

test("任意のBackend API、未許可メソッド、不正なIDを拒否する", () => {
  assert.equal(isAllowedBackendProxyRequest("GET", "/actuator/health"), false);
  assert.equal(isAllowedBackendProxyRequest("DELETE", "/admin/users"), false);
  assert.equal(isAllowedBackendProxyRequest("GET", "/admin/users/not-a-uuid"), false);
  assert.equal(isAllowedBackendProxyRequest("GET", "/../actuator/health"), false);
});
