import { auth } from "@/lib/auth";
import {
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
