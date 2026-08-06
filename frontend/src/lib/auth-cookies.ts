const BETTER_AUTH_COOKIE_NAME = /^(?:__Secure-)?better-auth\./;

export function betterAuthCookieNames(requestHeaders: Headers): string[] {
  const cookieHeader = requestHeaders.get("cookie") ?? "";
  const names = cookieHeader
    .split(";")
    .map((cookie) => cookie.trim().split("=", 1)[0])
    .filter((name) => BETTER_AUTH_COOKIE_NAME.test(name));

  return [...new Set(names)];
}

export function expiredBetterAuthCookies(
  requestHeaders: Headers,
  secure = process.env.NODE_ENV === "production",
): string[] {
  return betterAuthCookieNames(requestHeaders).map((name) =>
    `${name}=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax${
      secure ? "; Secure" : ""
    }`,
  );
}
