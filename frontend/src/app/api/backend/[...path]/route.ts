import { NextResponse } from "next/server";

import { expiredBetterAuthCookies } from "@/lib/auth-cookies";
import { proxyBackendRequest } from "@/lib/backend-client";
import {
  backendProxyRequestHeaders,
  backendProxyResponseHeaders,
  getBackendProxyPolicy,
  hasOversizedBackendProxyBody,
  isOversizedBackendProxyBody,
} from "@/lib/backend-proxy-policy";

export const dynamic = "force-dynamic";

async function proxy(
  request: Request,
  context: { params: Promise<{ path: string[] }> },
) {
  const { path } = await context.params;
  const backendPath = `/${path.join("/")}`;
  const policy = getBackendProxyPolicy(request.method, backendPath);
  if (!policy) {
    return NextResponse.json(
      {
        code: "BACKEND_ROUTE_NOT_ALLOWED",
        message: "このBackend APIは公開されていません。",
      },
      { status: 404 },
    );
  }
  const oversizedErrorCode = policy.oversizedErrorCode ?? "BACKEND_REQUEST_TOO_LARGE";

  function oversizedResponse() {
    return NextResponse.json(
      {
        code: oversizedErrorCode,
        message: "ファイルサイズが上限を超えています。",
      },
      { status: 413 },
    );
  }

  if (hasOversizedBackendProxyBody(
    request.method,
    backendPath,
    request.headers.get("content-length"),
  )) {
    return oversizedResponse();
  }
  const query = new URL(request.url).search;
  const body = request.method === "GET" || request.method === "HEAD"
    ? undefined
    : await request.arrayBuffer();
  if (body && isOversizedBackendProxyBody(policy, body.byteLength)) {
    return oversizedResponse();
  }
  const result = await proxyBackendRequest(
    request.headers,
    `/api${backendPath.split("/").map(encodeURIComponent).join("/")}${query}`,
    {
      method: request.method,
      body,
      headers: backendProxyRequestHeaders(request.headers, request.method, backendPath),
      timeoutMilliseconds: policy.timeoutMilliseconds,
    },
  );
  const response = new NextResponse(result.response.body, {
    status: result.response.status,
    headers: backendProxyResponseHeaders(result.response.headers),
  });
  for (const cookie of result.setCookies) {
    response.headers.append("set-cookie", cookie);
  }
  if (response.status === 401) {
    for (const expiredCookie of expiredBetterAuthCookies(request.headers)) {
      response.headers.append("set-cookie", expiredCookie);
    }
    response.headers.set("Cache-Control", "no-store");
  }
  return response;
}

export const GET = proxy;
export const POST = proxy;
export const PUT = proxy;
export const PATCH = proxy;
export const DELETE = proxy;
