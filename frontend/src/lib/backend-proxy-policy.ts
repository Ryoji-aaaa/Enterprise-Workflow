const UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}";
export const MAX_ATTACHMENT_PROXY_BODY_BYTES = 11 * 1024 * 1024;
export const MAX_DOCUMENT_ANALYSIS_PROXY_BODY_BYTES = 11 * 1024 * 1024;
const DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS = 5_000;

const attachmentCollectionPath = new RegExp(
  `^/expense-applications/${UUID_PATTERN}/attachments$`,
);
const attachmentItemPath = new RegExp(
  `^/expense-applications/${UUID_PATTERN}/attachments/${UUID_PATTERN}$`,
);
const attachmentContentPath = new RegExp(
  `^/expense-applications/${UUID_PATTERN}/attachments/${UUID_PATTERN}/content$`,
);
const mailNotificationItemPath = new RegExp(
  `^/admin/mail-notifications/${UUID_PATTERN}$`,
);
const documentAnalysisCollectionPath = /^\/document-analyses$/;
const documentAnalysisItemPath = new RegExp(`^/document-analyses/${UUID_PATTERN}$`);
const documentAnalysisSourcePath = new RegExp(`^/document-analyses/${UUID_PATTERN}/source$`);
const documentAnalysisViewPath = new RegExp(`^/document-analyses/${UUID_PATTERN}/view$`);
const documentAnalysisRawResultPath = new RegExp(
  `^/document-analyses/${UUID_PATTERN}/raw-result$`,
);
const documentAnalysisAutoEntryReviewPath = new RegExp(
  `^/document-analyses/${UUID_PATTERN}/auto-entry-review$`,
);
const expenseAutoEntryHandoffPath = /^\/expense-applications\/from-auto-entry$/;

export type BackendProxyPolicy = {
  methods: ReadonlySet<string>;
  path: RegExp;
  timeoutMilliseconds: number;
  responseType: "json" | "binary";
  maxBodyBytes?: number;
  oversizedErrorCode?: string;
};

const rules: readonly BackendProxyPolicy[] = [
  {
    methods: new Set(["GET"]),
    path: /^\/(?:me|organization-chart)$/,
    responseType: "json",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
  },
  {
    methods: new Set(["GET"]),
    path: /^\/admin\/(?:audit-logs|organization-units|positions|roles)$/,
    responseType: "json",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
  },
  {
    methods: new Set(["GET"]),
    path: /^\/admin\/mail-notifications$/,
    responseType: "json",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
  },
  {
    methods: new Set(["GET"]),
    path: mailNotificationItemPath,
    responseType: "json",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
  },
  {
    methods: new Set(["GET", "POST"]),
    path: attachmentCollectionPath,
    responseType: "json",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
    maxBodyBytes: MAX_ATTACHMENT_PROXY_BODY_BYTES,
    oversizedErrorCode: "EXPENSE_ATTACHMENT_TOO_LARGE",
  },
  {
    methods: new Set(["DELETE"]),
    path: attachmentItemPath,
    responseType: "json",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
  },
  {
    methods: new Set(["GET"]),
    path: attachmentContentPath,
    responseType: "binary",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
  },
  {
    methods: new Set(["GET"]),
    path: /^\/admin\/users$/,
    responseType: "json",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
  },
  {
    methods: new Set(["GET", "POST"]),
    path: /^\/expense-applications$/,
    responseType: "json",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
  },
  {
    methods: new Set(["POST"]),
    path: expenseAutoEntryHandoffPath,
    responseType: "json",
    timeoutMilliseconds: 30_000,
  },
  {
    methods: new Set(["GET"]),
    path: /^\/expense-approvals\/pending$/,
    responseType: "json",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
  },
  {
    methods: new Set(["GET", "PUT"]),
    path: new RegExp(`^/expense-applications/${UUID_PATTERN}$`),
    responseType: "json",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
  },
  {
    methods: new Set(["POST"]),
    path: new RegExp(
      `^/expense-applications/${UUID_PATTERN}/(?:submit|resubmit|cancel)$`,
    ),
    responseType: "json",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
  },
  {
    methods: new Set(["POST"]),
    path: new RegExp(
      `^/expense-approvals/${UUID_PATTERN}/(?:approve|return)$`,
    ),
    responseType: "json",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
  },
  {
    methods: new Set(["GET", "PATCH"]),
    path: new RegExp(`^/admin/users/${UUID_PATTERN}$`),
    responseType: "json",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
  },
  {
    methods: new Set(["PATCH"]),
    path: new RegExp(`^/admin/users/${UUID_PATTERN}/status$`),
    responseType: "json",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
  },
  {
    methods: new Set(["GET", "POST"]),
    path: new RegExp(`^/admin/users/${UUID_PATTERN}/(?:roles|organization-assignments)$`),
    responseType: "json",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
  },
  {
    methods: new Set(["PATCH", "DELETE"]),
    path: new RegExp(
      `^/admin/users/${UUID_PATTERN}/organization-assignments/${UUID_PATTERN}$`,
    ),
    responseType: "json",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
  },
  {
    methods: new Set(["DELETE"]),
    path: new RegExp(`^/admin/users/${UUID_PATTERN}/roles/${UUID_PATTERN}$`),
    responseType: "json",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
  },
  {
    methods: new Set(["GET"]),
    path: documentAnalysisCollectionPath,
    responseType: "json",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
  },
  {
    methods: new Set(["POST"]),
    path: documentAnalysisCollectionPath,
    responseType: "json",
    timeoutMilliseconds: 30_000,
    maxBodyBytes: MAX_DOCUMENT_ANALYSIS_PROXY_BODY_BYTES,
    oversizedErrorCode: "DOCUMENT_ANALYSIS_TOO_LARGE",
  },
  {
    methods: new Set(["GET"]),
    path: documentAnalysisItemPath,
    responseType: "json",
    timeoutMilliseconds: DEFAULT_BACKEND_PROXY_TIMEOUT_MILLISECONDS,
  },
  {
    methods: new Set(["GET"]),
    path: documentAnalysisSourcePath,
    responseType: "binary",
    timeoutMilliseconds: 30_000,
  },
  {
    methods: new Set(["GET"]),
    path: documentAnalysisViewPath,
    responseType: "json",
    timeoutMilliseconds: 15_000,
  },
  {
    methods: new Set(["GET"]),
    path: documentAnalysisRawResultPath,
    responseType: "json",
    timeoutMilliseconds: 15_000,
  },
  {
    methods: new Set(["GET"]),
    path: documentAnalysisAutoEntryReviewPath,
    responseType: "json",
    timeoutMilliseconds: 15_000,
  },
];

export function getBackendProxyPolicy(
  method: string,
  path: string,
): BackendProxyPolicy | undefined {
  return rules.find((rule) => rule.methods.has(method) && rule.path.test(path));
}

export function isAllowedBackendProxyRequest(method: string, path: string): boolean {
  return getBackendProxyPolicy(method, path) !== undefined;
}

export function isExpenseAttachmentUploadRequest(method: string, path: string): boolean {
  return method === "POST" && attachmentCollectionPath.test(path);
}

export function isExpenseAttachmentContentRequest(method: string, path: string): boolean {
  return method === "GET" && attachmentContentPath.test(path);
}

export function hasOversizedBackendProxyBody(
  method: string,
  path: string,
  contentLength: string | null,
): boolean {
  const policy = getBackendProxyPolicy(method, path);
  if (policy?.maxBodyBytes === undefined || contentLength === null) return false;
  const parsed = Number(contentLength);
  return Number.isFinite(parsed) && parsed > policy.maxBodyBytes;
}

export function isOversizedBackendProxyBody(
  policy: Pick<BackendProxyPolicy, "maxBodyBytes">,
  bodyBytes: number,
): boolean {
  return policy.maxBodyBytes !== undefined && bodyBytes > policy.maxBodyBytes;
}

export function backendProxyRequestHeaders(
  requestHeaders: Headers,
  method: string,
  path: string,
): Headers {
  const headers = new Headers();
  const contentType = requestHeaders.get("content-type");
  if (contentType) headers.set("Content-Type", contentType);
  headers.set(
    "Accept",
    getBackendProxyPolicy(method, path)?.responseType === "binary"
      ? (requestHeaders.get("accept") ?? "*/*")
      : "application/json",
  );
  return headers;
}

const SAFE_RESPONSE_HEADERS = [
  "content-type",
  "content-length",
  "content-disposition",
  "cache-control",
  "x-content-type-options",
] as const;

export function backendProxyResponseHeaders(source: Headers): Headers {
  const result = new Headers();
  for (const name of SAFE_RESPONSE_HEADERS) {
    const value = source.get(name);
    if (value !== null) result.set(name, value);
  }
  if (!result.has("content-type")) result.set("Content-Type", "application/json");
  return result;
}
