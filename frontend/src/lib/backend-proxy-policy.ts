const UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}";

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
  { methods: new Set(["GET"]), path: /^\/admin\/users$/ },
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
