import assert from "node:assert/strict";
import test from "node:test";

import {
  AuthenticationRequiredError,
  createBackendFetch,
  SESSION_EXPIRED_LOGIN_PATH,
} from "./backend-browser-client.ts";

function response(status: number): typeof fetch {
  return async () => new Response(null, { status });
}

test("200、403、503はレスポンスをそのまま返して遷移しない", async () => {
  for (const status of [200, 403, 503]) {
    const navigations: string[] = [];
    const fetchBackend = createBackendFetch({
      fetchImplementation: response(status),
      navigate: (path) => navigations.push(path),
    });

    assert.equal((await fetchBackend("/api/backend/me")).status, status);
    assert.deepEqual(navigations, []);
  }
});

test("401は期限切れログイン画面へ一度だけ遷移して処理を中断する", async () => {
  const navigations: string[] = [];
  const fetchBackend = createBackendFetch({
    fetchImplementation: response(401),
    navigate: (path) => navigations.push(path),
  });

  const results = await Promise.allSettled([
    fetchBackend("/api/backend/me"),
    fetchBackend("/api/backend/expense-applications"),
  ]);

  assert.deepEqual(navigations, [SESSION_EXPIRED_LOGIN_PATH]);
  for (const result of results) {
    assert.equal(result.status, "rejected");
    if (result.status === "rejected") {
      assert.ok(result.reason instanceof AuthenticationRequiredError);
    }
  }
});
