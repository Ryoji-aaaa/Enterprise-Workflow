import { NextResponse } from "next/server";

import { expiredBetterAuthCookies } from "@/lib/auth-cookies";
import { getBackendMe } from "@/lib/backend-client";

export const dynamic = "force-dynamic";

function responseBody(kind: Exclude<
  Awaited<ReturnType<typeof getBackendMe>>["kind"],
  "success"
>) {
  switch (kind) {
    case "unauthenticated":
      return {
        code: "AUTHENTICATION_REQUIRED",
        message: "再度ログインしてください。",
      };
    case "unregistered":
      return {
        code: "APPLICATION_USER_NOT_REGISTERED",
        message: "このアカウントはワークフローアプリに登録されていません。",
      };
    case "forbidden":
      return {
        code: "APPLICATION_ACCESS_DENIED",
        message: "このアカウントではワークフローアプリを利用できません。",
      };
    case "unavailable":
      return {
        code: "BACKEND_UNAVAILABLE",
        message: "現在サービスを利用できません。",
      };
  }
}

export async function GET(request: Request) {
  const result = await getBackendMe(request.headers);
  const status =
    result.kind === "success"
      ? 200
      : result.kind === "unauthenticated"
        ? 401
        : result.kind === "unavailable"
          ? 503
          : 403;
  const response = NextResponse.json(
    result.kind === "success" ? result.user : responseBody(result.kind),
    { status },
  );

  for (const setCookie of result.setCookies) {
    response.headers.append("set-cookie", setCookie);
  }
  if (status === 401) {
    for (const expiredCookie of expiredBetterAuthCookies(request.headers)) {
      response.headers.append("set-cookie", expiredCookie);
    }
  }
  response.headers.set("Cache-Control", "no-store");

  return response;
}
