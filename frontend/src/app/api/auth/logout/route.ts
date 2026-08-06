import { NextResponse } from "next/server";

import { auth } from "@/lib/auth";
import { expiredBetterAuthCookies } from "@/lib/auth-cookies";
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
  for (const setCookie of signOutResponse.headers.getSetCookie()) {
    response.headers.append("set-cookie", setCookie);
  }
  for (const expiredCookie of expiredBetterAuthCookies(request.headers)) {
    response.headers.append("set-cookie", expiredCookie);
  }
  response.headers.set("Cache-Control", "no-store");
  response.headers.set("Clear-Site-Data", '"cache"');

  return response;
}
