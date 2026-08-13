import type {
  AutoEntryField,
  AutoEntryFindingCode,
  AutoEntrySourceRef,
} from "./auto-entry-review.ts";

export const AUTO_ENTRY_FINDING_LABELS = {
  LOW_CONFIDENCE: "信頼度が低いため確認してください",
  ENUM_VALUE_UNKNOWN: "未対応の値が抽出されています",
  LINE_AMOUNT_INCONSISTENT: "明細金額が計算結果と一致しません",
  TAX_BREAKDOWN_INCONSISTENT: "税率別内訳の金額が一致しません",
  TAX_TOTAL_INCONSISTENT: "税額合計が一致しません",
  TOTAL_INCONSISTENT: "合計金額が一致しません",
  ADJUSTMENT_DIRECTION_UNKNOWN: "調整金額の加算・減算方向を判定できません",
  TAX_MODE_AMBIGUOUS: "税込・税抜を判定できません",
  PAYMENT_DUE_BEFORE_ISSUE_DATE: "支払期限が発行日より前です",
} satisfies Record<AutoEntryFindingCode, string>;

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

export function formatAutoEntryFinding(finding: AutoEntryFindingCode): string {
  return AUTO_ENTRY_FINDING_LABELS[finding];
}

export function formatAutoEntryConfidence(confidence: number | null): string | null {
  if (confidence === null) return null;
  return `信頼度 ${new Intl.NumberFormat("ja-JP", {
    style: "percent",
    maximumFractionDigits: 1,
  }).format(confidence)}`;
}

export function formatAutoEntrySources(sources: readonly AutoEntrySourceRef[]): string | null {
  const pageNumbers = [...new Set(sources.map((source) => source.pageNumber))];
  return pageNumbers.length === 0 ? null : `参照ページ ${pageNumbers.join(", ")}`;
}
