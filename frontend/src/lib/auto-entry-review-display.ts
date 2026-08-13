import type { AutoEntryField } from "./auto-entry-review.ts";

export type AutoEntryArrayDisplayState = "missing" | "empty" | "items";

export function getAutoEntryArrayDisplayState<T>(
  field: Pick<AutoEntryField<T[]>, "status" | "value">,
): AutoEntryArrayDisplayState {
  if (field.value === null) return field.status === "MISSING" ? "missing" : "empty";
  return field.value.length === 0 ? "empty" : "items";
}

export function formatAutoEntryFieldValue(
  field: Pick<AutoEntryField<string | number>, "value">,
): string {
  if (field.value === null) return "未取得";
  return typeof field.value === "number"
    ? new Intl.NumberFormat("ja-JP").format(field.value)
    : field.value;
}
