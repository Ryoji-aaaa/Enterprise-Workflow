import { NextResponse } from "next/server";

import { auth } from "@/lib/auth";
import { serverEnvironment } from "@/lib/environment";

function keycloakLogoutUrl(): URL {
  const url = new URL(
    `${serverEnvironment.keycloakIssuer}/protocol/openid-connect/logout`,
  );
  url.searchParams.set("client_id", serverEnvironment.keycloakClientId);
  url.searchParams.set(
    "post_logout_redirect_uri",
    `${serverEnvironment.betterAuthUrl}/login`,
  );
  return url;
}

function betterAuthCookieNames(request: Request): string[] {
  const cookieHeader = request.headers.get("cookie") ?? "";

  return cookieHeader
    .split(";")
    .map((cookie) => cookie.trim().split("=", 1)[0])
    .filter((name) => /^(?:__Secure-)?better-auth\./.test(name));
}

export async function POST(request: Request) {
  const signOutResponse = await auth.api.signOut({
    headers: request.headers,
    asResponse: true,
  });
  const response = NextResponse.redirect(keycloakLogoutUrl(), 303);

  if (!signOutResponse.ok) {
    return NextResponse.json(
      { code: "LOGOUT_FAILED", message: "ログアウトできませんでした。" },
      { status: 500 },
    );
  }
  for (const cookieName of betterAuthCookieNames(request)) {
    response.cookies.set({
      name: cookieName,
      value: "",
      path: "/",
      httpOnly: true,
      secure: process.env.NODE_ENV === "production",
      sameSite: "lax",
      maxAge: 0,
    });
  }
  response.headers.set("Cache-Control", "no-store");
  response.headers.set("Clear-Site-Data", '"cache"');

  return response;
}
