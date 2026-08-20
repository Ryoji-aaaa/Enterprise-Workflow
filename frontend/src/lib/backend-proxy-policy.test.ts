import assert from "node:assert/strict";
import test from "node:test";

import {
  backendProxyRequestHeaders,
  backendProxyResponseHeaders,
  getBackendProxyPolicy,
  hasOversizedBackendProxyBody,
  isOversizedBackendProxyBody,
  isAllowedBackendProxyRequest,
  MAX_ATTACHMENT_PROXY_BODY_BYTES,
  MAX_DOCUMENT_ANALYSIS_PROXY_BODY_BYTES,
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
  assert.equal(isAllowedBackendProxyRequest("GET", "/workflow/tasks"), true);
  assert.equal(
    isAllowedBackendProxyRequest("POST", `/workflow/tasks/${ASSIGNMENT_ID}/approve`),
    true,
  );
  assert.equal(isAllowedBackendProxyRequest("GET", `/workflow/tasks/${ASSIGNMENT_ID}`), true);
  assert.equal(isAllowedBackendProxyRequest(
    "GET", `/workflow/subjects/EXPENSE_APPLICATION/${ASSIGNMENT_ID}/latest`,
  ), true);
  assert.equal(isAllowedBackendProxyRequest("DELETE", `/expense-applications/${USER_ID}`), false);
});

test("AUTO_ENTRYからの経費下書き作成はPOSTだけを許可し、30秒timeoutを使う", () => {
  const path = "/expense-applications/from-auto-entry";
  assert.equal(isAllowedBackendProxyRequest("POST", path), true);
  assert.equal(isAllowedBackendProxyRequest("GET", path), false);
  assert.equal(isAllowedBackendProxyRequest("PUT", path), false);
  assert.equal(isAllowedBackendProxyRequest("DELETE", path), false);
  assert.equal(getBackendProxyPolicy("POST", path)?.timeoutMilliseconds, 30_000);
  assert.equal(getBackendProxyPolicy("POST", path)?.responseType, "json");
});

test("AUTO_ENTRY保存済み下書きはUUID pathのGET/PUTだけを許可する", () => {
  const path = `/expense-applications/${USER_ID}/auto-entry-draft`;
  assert.equal(isAllowedBackendProxyRequest("GET", path), true);
  assert.equal(isAllowedBackendProxyRequest("PUT", path), true);
  assert.equal(getBackendProxyPolicy("GET", path)?.responseType, "json");
  for (const method of ["POST", "PATCH", "DELETE"]) assert.equal(isAllowedBackendProxyRequest(method, path), false);
  assert.equal(isAllowedBackendProxyRequest("GET", "/expense-applications/not-a-uuid/auto-entry-draft"), false);
  assert.equal(isAllowedBackendProxyRequest("GET", `${path}/unexpected`), false);
});

test("メール通知履歴は一覧とUUID詳細のGETだけを許可する", () => {
  const collection = "/admin/mail-notifications";
  assert.equal(isAllowedBackendProxyRequest("GET", collection), true);
  assert.equal(isAllowedBackendProxyRequest("GET", `${collection}/${USER_ID}`), true);
  assert.equal(isAllowedBackendProxyRequest("POST", collection), false);
  assert.equal(isAllowedBackendProxyRequest("PATCH", `${collection}/${USER_ID}`), false);
  assert.equal(isAllowedBackendProxyRequest("DELETE", `${collection}/${USER_ID}`), false);
  assert.equal(isAllowedBackendProxyRequest("GET", `${collection}/not-a-uuid`), false);
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

test("Document Analysisは必要なcollection/detail/result routeだけを許可する", () => {
  const collection = "/document-analyses";
  const item = `/document-analyses/${USER_ID}`;
  assert.equal(isAllowedBackendProxyRequest("GET", collection), true);
  assert.equal(isAllowedBackendProxyRequest("POST", collection), true);
  assert.equal(isAllowedBackendProxyRequest("GET", item), true);
  assert.equal(isAllowedBackendProxyRequest("GET", `${item}/source`), true);
  assert.equal(isAllowedBackendProxyRequest("GET", `${item}/view`), true);
  assert.equal(isAllowedBackendProxyRequest("GET", `${item}/raw-result`), true);
  assert.equal(isAllowedBackendProxyRequest("GET", `${item}/auto-entry-review`), true);

  assert.equal(isAllowedBackendProxyRequest("DELETE", collection), false);
  assert.equal(isAllowedBackendProxyRequest("POST", item), false);
  assert.equal(isAllowedBackendProxyRequest("GET", "/document-analyses/not-a-uuid"), false);
  assert.equal(isAllowedBackendProxyRequest("GET", `${item}/retry`), false);
  assert.equal(isAllowedBackendProxyRequest("POST", `${item}/auto-entry-review`), false);
});

test("Document Analysisはrouteごとのtimeoutとbody上限を持つ", () => {
  const collectionGet = getBackendProxyPolicy("GET", "/document-analyses");
  const collectionPost = getBackendProxyPolicy("POST", "/document-analyses");
  const source = getBackendProxyPolicy("GET", `/document-analyses/${USER_ID}/source`);
  const view = getBackendProxyPolicy("GET", `/document-analyses/${USER_ID}/view`);
  const raw = getBackendProxyPolicy("GET", `/document-analyses/${USER_ID}/raw-result`);
  const review = getBackendProxyPolicy(
    "GET",
    `/document-analyses/${USER_ID}/auto-entry-review`,
  );

  assert.equal(collectionGet?.timeoutMilliseconds, 5_000);
  assert.equal(collectionPost?.timeoutMilliseconds, 30_000);
  assert.equal(collectionPost?.maxBodyBytes, MAX_DOCUMENT_ANALYSIS_PROXY_BODY_BYTES);
  assert.equal(collectionPost?.oversizedErrorCode, "DOCUMENT_ANALYSIS_TOO_LARGE");
  assert.equal(source?.timeoutMilliseconds, 30_000);
  assert.equal(view?.timeoutMilliseconds, 15_000);
  assert.equal(raw?.timeoutMilliseconds, 15_000);
  assert.equal(review?.timeoutMilliseconds, 15_000);
  assert.equal(
    hasOversizedBackendProxyBody(
      "POST",
      "/document-analyses",
      String(MAX_DOCUMENT_ANALYSIS_PROXY_BODY_BYTES + 1),
    ),
    true,
  );
  assert.equal(isOversizedBackendProxyBody(collectionPost!, MAX_DOCUMENT_ANALYSIS_PROXY_BODY_BYTES), false);
  assert.equal(isOversizedBackendProxyBody(collectionPost!, MAX_DOCUMENT_ANALYSIS_PROXY_BODY_BYTES + 1), true);
});

test("Document Analysis multipart boundaryとsource Acceptを維持する", () => {
  const uploadHeaders = backendProxyRequestHeaders(
    new Headers({ "Content-Type": "multipart/form-data; boundary=----browser-boundary" }),
    "POST",
    "/document-analyses",
  );
  assert.equal(uploadHeaders.get("content-type"), "multipart/form-data; boundary=----browser-boundary");
  assert.equal(uploadHeaders.get("accept"), "application/json");

  const sourceHeaders = backendProxyRequestHeaders(
    new Headers({ Accept: "application/pdf" }),
    "GET",
    `/document-analyses/${USER_ID}/source`,
  );
  assert.equal(sourceHeaders.get("accept"), "application/pdf");
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
