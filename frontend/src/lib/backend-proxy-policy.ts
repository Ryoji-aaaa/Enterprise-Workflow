const UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}";
export const MAX_ATTACHMENT_PROXY_BODY_BYTES = 11 * 1024 * 1024;

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

type Rule = {
  methods: ReadonlySet<string>;
  path: RegExp;
};

const rules: readonly Rule[] = [
  { methods: new Set(["GET"]), path: /^\/(?:me|organization-chart)$/ },
  {
    methods: new Set(["GET"]),
    path: /^\/admin\/(?:audit-logs|organization-units|positions|roles)$/,
  },
  { methods: new Set(["GET"]), path: /^\/admin\/mail-notifications$/ },
  { methods: new Set(["GET"]), path: mailNotificationItemPath },
  {
    methods: new Set(["GET", "POST"]),
    path: attachmentCollectionPath,
  },
  {
    methods: new Set(["DELETE"]),
    path: attachmentItemPath,
  },
  {
    methods: new Set(["GET"]),
    path: attachmentContentPath,
  },
  { methods: new Set(["GET"]), path: /^\/admin\/users$/ },
  { methods: new Set(["GET", "POST"]), path: /^\/expense-applications$/ },
  { methods: new Set(["GET"]), path: /^\/expense-approvals\/pending$/ },
  {
    methods: new Set(["GET", "PUT"]),
    path: new RegExp(`^/expense-applications/${UUID_PATTERN}$`),
  },
  {
    methods: new Set(["POST"]),
    path: new RegExp(
      `^/expense-applications/${UUID_PATTERN}/(?:submit|resubmit|cancel)$`,
    ),
  },
  {
    methods: new Set(["POST"]),
    path: new RegExp(
      `^/expense-approvals/${UUID_PATTERN}/(?:approve|return)$`,
    ),
  },
  {
    methods: new Set(["GET", "PATCH"]),
    path: new RegExp(`^/admin/users/${UUID_PATTERN}$`),
  },
  {
    methods: new Set(["PATCH"]),
    path: new RegExp(`^/admin/users/${UUID_PATTERN}/status$`),
  },
  {
    methods: new Set(["GET", "POST"]),
    path: new RegExp(`^/admin/users/${UUID_PATTERN}/(?:roles|organization-assignments)$`),
  },
  {
    methods: new Set(["PATCH", "DELETE"]),
    path: new RegExp(
      `^/admin/users/${UUID_PATTERN}/organization-assignments/${UUID_PATTERN}$`,
    ),
  },
  {
    methods: new Set(["DELETE"]),
    path: new RegExp(`^/admin/users/${UUID_PATTERN}/roles/${UUID_PATTERN}$`),
  },
];

export function isAllowedBackendProxyRequest(method: string, path: string): boolean {
  return rules.some((rule) => rule.methods.has(method) && rule.path.test(path));
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
  if (!isExpenseAttachmentUploadRequest(method, path) || contentLength === null) return false;
  const parsed = Number(contentLength);
  return Number.isFinite(parsed) && parsed > MAX_ATTACHMENT_PROXY_BODY_BYTES;
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
    isExpenseAttachmentContentRequest(method, path)
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
