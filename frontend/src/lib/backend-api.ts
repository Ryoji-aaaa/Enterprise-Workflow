export const BACKEND_TIMEOUT_MILLISECONDS = 5_000;

export type CurrentUser = {
  id: string;
  externalSubject: string;
  email: string;
  displayName: string;
  employmentType:
    | "SYSTEM"
    | "REGULAR_EMPLOYEE"
    | "ASSOCIATE_EMPLOYEE"
    | "PART_TIME"
    | "CONTRACT_EMPLOYEE";
  department: {
    name: string;
  } | null;
  roles: string[];
  permissions: string[];
  features: {
    mailNotificationHistory: boolean;
    documentIntelligence: boolean;
    contentUnderstanding: boolean;
  };
};

export function canViewOrganizationChart(user: CurrentUser): boolean {
  return user.permissions.includes("ORGANIZATION_CHART_READ")
    && (user.employmentType === "REGULAR_EMPLOYEE"
      || user.employmentType === "ASSOCIATE_EMPLOYEE");
}

export function canViewMailNotificationHistory(user: CurrentUser): boolean {
  return user.features?.mailNotificationHistory === true
    && user.permissions.includes("MAIL_NOTIFICATION_READ");
}

export function canUseDocumentIntelligence(user: CurrentUser): boolean {
  return user.features?.documentIntelligence === true
    && user.permissions.includes("DOCUMENT_INTELLIGENCE_ANALYZE");
}

export function canUseContentUnderstanding(user: CurrentUser): boolean {
  return user.features?.contentUnderstanding === true
    && user.permissions.includes("CONTENT_UNDERSTANDING_ANALYZE");
}

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
