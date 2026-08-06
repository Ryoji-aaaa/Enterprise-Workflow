export const SESSION_EXPIRED_LOGIN_PATH = "/login?reason=session-expired";

export class AuthenticationRequiredError extends Error {
  constructor() {
    super("Authentication is required.");
    this.name = "AuthenticationRequiredError";
  }
}

type BrowserBackendFetchDependencies = {
  fetchImplementation?: typeof fetch;
  navigate?: (path: string) => void;
};

export function createBackendFetch({
  fetchImplementation = fetch,
  navigate = (path) => window.location.replace(path),
}: BrowserBackendFetchDependencies = {}) {
  let redirectStarted = false;

  return async function fetchBackend(
    input: string | URL | Request,
    init?: RequestInit,
  ): Promise<Response> {
    const response = await fetchImplementation(input, {
      cache: "no-store",
      ...init,
    });

    if (response.status === 401) {
      if (!redirectStarted) {
        redirectStarted = true;
        navigate(SESSION_EXPIRED_LOGIN_PATH);
      }
      throw new AuthenticationRequiredError();
    }

    return response;
  };
}

export const fetchBackend = createBackendFetch();
