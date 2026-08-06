import assert from "node:assert/strict";
import test from "node:test";

import {
  betterAuthCookieNames,
  expiredBetterAuthCookies,
} from "./auth-cookies.ts";

test("Better Authの通常・Secure・chunk Cookieだけを列挙する", () => {
  const headers = new Headers({
    cookie: [
      "theme=dark",
      "better-auth.session_data=session",
      "better-auth.account_data.0=account-0",
      "better-auth.account_data.1=account-1",
      "__Secure-better-auth.session_token=secure-session",
      "better-auth.session_data=session-duplicate",
    ].join("; "),
  });

  assert.deepEqual(betterAuthCookieNames(headers), [
    "better-auth.session_data",
    "better-auth.account_data.0",
    "better-auth.account_data.1",
    "__Secure-better-auth.session_token",
  ]);
});

test("Better Auth Cookieを同じpathと属性で失効させる", () => {
  const headers = new Headers({
    cookie: "better-auth.session_data=session; unrelated=value",
  });

  assert.deepEqual(expiredBetterAuthCookies(headers, false), [
    "better-auth.session_data=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax",
  ]);
  assert.deepEqual(expiredBetterAuthCookies(headers, true), [
    "better-auth.session_data=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax; Secure",
  ]);
});
