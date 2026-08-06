import { NextResponse } from "next/server";

import { proxyBackendRequest } from "@/lib/backend-client";
import {
  backendProxyRequestHeaders,
  backendProxyResponseHeaders,
  hasOversizedBackendProxyBody,
  isAllowedBackendProxyRequest,
} from "@/lib/backend-proxy-policy";

export const dynamic = "force-dynamic";

async function proxy(
  request: Request,
  context: { params: Promise<{ path: string[] }> },
) {
  const { path } = await context.params;
  const backendPath = `/${path.join("/")}`;
  if (!isAllowedBackendProxyRequest(request.method, backendPath)) {
    return NextResponse.json(
      {
        code: "BACKEND_ROUTE_NOT_ALLOWED",
        message: "このBackend APIは公開されていません。",
      },
      { status: 404 },
    );
  }
  if (hasOversizedBackendProxyBody(
    request.method,
    backendPath,
    request.headers.get("content-length"),
  )) {
    return NextResponse.json(
      {
        code: "EXPENSE_ATTACHMENT_TOO_LARGE",
        message: "ファイルサイズが上限を超えています。",
      },
      { status: 413 },
    );
  }
  const query = new URL(request.url).search;
  const body = request.method === "GET" || request.method === "HEAD"
    ? undefined
    : await request.arrayBuffer();
  const result = await proxyBackendRequest(
    request.headers,
    `/api${backendPath.split("/").map(encodeURIComponent).join("/")}${query}`,
    {
      method: request.method,
      body,
      headers: backendProxyRequestHeaders(request.headers, request.method, backendPath),
    },
  );
  const response = new NextResponse(result.response.body, {
    status: result.response.status,
    headers: backendProxyResponseHeaders(result.response.headers),
  });
  for (const cookie of result.setCookies) {
    response.headers.append("set-cookie", cookie);
  }
  return response;
}

export const GET = proxy;
export const POST = proxy;
export const PUT = proxy;
export const PATCH = proxy;
export const DELETE = proxy;
