import { auth } from "@/lib/auth";
import {
  BACKEND_TIMEOUT_MILLISECONDS,
  requestBackendMe,
  type CurrentUser,
} from "@/lib/backend-api";
import { serverEnvironment } from "@/lib/environment";

const KEYCLOAK_PROVIDER_ID = "keycloak";
export type { CurrentUser } from "@/lib/backend-api";

export type BackendMeResult =
  | {
      kind: "success";
      user: CurrentUser;
      setCookies: string[];
    }
  | {
      kind: "unauthenticated";
      setCookies: string[];
    }
  | {
      kind: "unregistered" | "forbidden" | "unavailable";
      setCookies: string[];
    };

function setCookieValues(response: Response): string[] {
  return response.headers.getSetCookie();
}

async function readJson<T>(response: Response): Promise<T | undefined> {
  try {
    return (await response.json()) as T;
  } catch {
    return undefined;
  }
}

export async function getBackendMe(
  requestHeaders: Headers,
): Promise<BackendMeResult> {
  const sessionResponse = await auth.api.getSession({
    headers: requestHeaders,
    asResponse: true,
  });
  const setCookies = setCookieValues(sessionResponse);
  const session = await readJson(sessionResponse);

  if (!sessionResponse.ok || !session) {
    return { kind: "unauthenticated", setCookies };
  }

  const tokenResponse = await auth.api.getAccessToken({
    headers: requestHeaders,
    body: {
      providerId: KEYCLOAK_PROVIDER_ID,
    },
    asResponse: true,
  });
  setCookies.push(...setCookieValues(tokenResponse));

  if (!tokenResponse.ok) {
    return { kind: "unauthenticated", setCookies };
  }

  const token = await readJson<{ accessToken?: string }>(tokenResponse);
  if (!token?.accessToken) {
    return { kind: "unauthenticated", setCookies };
  }

  const backendResult = await requestBackendMe({
    accessToken: token.accessToken,
    backendUrl: serverEnvironment.backendInternalUrl,
  });

  return backendResult.kind === "success"
    ? { ...backendResult, setCookies }
    : { kind: backendResult.kind, setCookies };
}

export type BackendProxyResult = {
  response: Response;
  setCookies: string[];
};

export async function proxyBackendRequest(
  requestHeaders: Headers,
  path: string,
  init: RequestInit = {},
): Promise<BackendProxyResult> {
  const sessionResponse = await auth.api.getSession({
    headers: requestHeaders,
    asResponse: true,
  });
  const setCookies = setCookieValues(sessionResponse);
  const session = await readJson(sessionResponse);
  if (!sessionResponse.ok || !session) {
    return {
      response: Response.json(
        { code: "AUTHENTICATION_REQUIRED", message: "再度ログインしてください。" },
        { status: 401 },
      ),
      setCookies,
    };
  }

  const tokenResponse = await auth.api.getAccessToken({
    headers: requestHeaders,
    body: { providerId: KEYCLOAK_PROVIDER_ID },
    asResponse: true,
  });
  setCookies.push(...setCookieValues(tokenResponse));
  const token = tokenResponse.ok
    ? await readJson<{ accessToken?: string }>(tokenResponse)
    : undefined;
  if (!token?.accessToken) {
    return {
      response: Response.json(
        { code: "AUTHENTICATION_REQUIRED", message: "再度ログインしてください。" },
        { status: 401 },
      ),
      setCookies,
    };
  }

  try {
    const headers = new Headers(init.headers);
    headers.set("Authorization", `Bearer ${token.accessToken}`);
    if (!headers.has("Accept")) headers.set("Accept", "application/json");
    const response = await fetch(`${serverEnvironment.backendInternalUrl}${path}`, {
      ...init,
      cache: "no-store",
      headers,
      signal: AbortSignal.timeout(BACKEND_TIMEOUT_MILLISECONDS),
    });
    return { response, setCookies };
  } catch {
    return {
      response: Response.json(
        { code: "BACKEND_UNAVAILABLE", message: "現在サービスを利用できません。" },
        { status: 503 },
      ),
      setCookies,
    };
  }
}
