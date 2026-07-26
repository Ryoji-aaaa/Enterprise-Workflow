export const BACKEND_TIMEOUT_MILLISECONDS = 5_000;

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

export type BackendApiResult =
  | {
      kind: "success";
      user: CurrentUser;
    }
  | {
      kind: "unauthenticated" | "unregistered" | "forbidden" | "unavailable";
    };

type BackendErrorBody = {
  code?: string;
};

export type BackendFetch = (
  input: string | URL | Request,
  init?: RequestInit,
) => Promise<Response>;

async function readJson<T>(response: Response): Promise<T | undefined> {
  try {
    return (await response.json()) as T;
  } catch {
    return undefined;
  }
}

export async function requestBackendMe({
  accessToken,
  backendUrl,
  fetchImplementation = fetch,
  timeoutMilliseconds = BACKEND_TIMEOUT_MILLISECONDS,
}: {
  accessToken: string;
  backendUrl: string;
  fetchImplementation?: BackendFetch;
  timeoutMilliseconds?: number;
}): Promise<BackendApiResult> {
  let response: Response;
  try {
    response = await fetchImplementation(`${backendUrl}/api/me`, {
      cache: "no-store",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        Accept: "application/json",
      },
      signal: AbortSignal.timeout(timeoutMilliseconds),
    });
  } catch {
    return { kind: "unavailable" };
  }

  if (response.ok) {
    const user = await readJson<CurrentUser>(response);
    return user ? { kind: "success", user } : { kind: "unavailable" };
  }

  if (response.status === 401) {
    return { kind: "unauthenticated" };
  }

  if (response.status === 403) {
    const error = await readJson<BackendErrorBody>(response);
    return {
      kind:
        error?.code === "APPLICATION_USER_NOT_REGISTERED"
          ? "unregistered"
          : "forbidden",
    };
  }

  return { kind: "unavailable" };
}
