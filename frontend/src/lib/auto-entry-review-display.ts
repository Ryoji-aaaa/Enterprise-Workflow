import type { AutoEntryField } from "./auto-entry-review.ts";

export function formatAutoEntryFieldValue(
  field: Pick<AutoEntryField<string | number>, "value">,
): string {
  if (field.value === null) return "未取得";
  return typeof field.value === "number"
    ? new Intl.NumberFormat("ja-JP").format(field.value)
    : field.value;
}
