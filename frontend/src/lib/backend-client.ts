import { auth } from "@/lib/auth";
import { serverEnvironment } from "@/lib/environment";

const KEYCLOAK_PROVIDER_ID = "keycloak";
const BACKEND_TIMEOUT_MILLISECONDS = 5_000;

export type CurrentUser = {
  id: string;
  externalSubject: string;
  email: string;
  displayName: string;
  department: {
    name: string;
  } | null;
  roles: string[];
};

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

type BackendErrorBody = {
  code?: string;
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

  let backendResponse: Response;
  try {
    backendResponse = await fetch(
      `${serverEnvironment.backendInternalUrl}/api/me`,
      {
        cache: "no-store",
        headers: {
          Authorization: `Bearer ${token.accessToken}`,
          Accept: "application/json",
        },
        signal: AbortSignal.timeout(BACKEND_TIMEOUT_MILLISECONDS),
      },
    );
  } catch {
    return { kind: "unavailable", setCookies };
  }

  if (backendResponse.ok) {
    const user = await readJson<CurrentUser>(backendResponse);
    return user
      ? { kind: "success", user, setCookies }
      : { kind: "unavailable", setCookies };
  }

  if (backendResponse.status === 401) {
    return { kind: "unauthenticated", setCookies };
  }

  if (backendResponse.status === 403) {
    const error = await readJson<BackendErrorBody>(backendResponse);
    return {
      kind:
        error?.code === "APPLICATION_USER_NOT_REGISTERED"
          ? "unregistered"
          : "forbidden",
      setCookies,
    };
  }

  return { kind: "unavailable", setCookies };
}
