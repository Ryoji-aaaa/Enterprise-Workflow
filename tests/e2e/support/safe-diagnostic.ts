export function extractSafeTopLevelErrorCode(value: unknown): string | undefined {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return undefined;
  const code = (value as Record<string, unknown>).code;
  return typeof code === "string"
    && code.length > 0
    && code.length <= 128
    && /^[A-Z0-9_]+$/.test(code)
    ? code
    : undefined;
}
