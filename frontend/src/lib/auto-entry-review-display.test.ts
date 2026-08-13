import assert from "node:assert/strict";
import test from "node:test";

import type { AutoEntryField, AutoEntryTaxBreakdown } from "./auto-entry-review.ts";
import {
  formatAutoEntryFieldValue,
  getAutoEntryArrayDisplayState,
} from "./auto-entry-review-display.ts";

function missingTaxRate(categoryNotation: string, category: string): AutoEntryTaxBreakdown {
  return {
    review: { confidence: null, status: "OK", sources: [], findings: [] },
    taxRatePercent: { value: null, confidence: 0.9, status: "MISSING", sources: [], findings: [] },
    taxableAmount: { value: 1000, confidence: 0.9, status: "OK", sources: [], findings: [] },
    taxAmount: { value: 100, confidence: 0.9, status: "OK", sources: [], findings: [] },
    categoryNotation: { value: categoryNotation, confidence: 0.9, status: "OK", sources: [], findings: [] },
    category: { value: category, confidence: 0.9, status: "OK", sources: [], findings: [] },
  };
}

test("TaxRatePercentがnullならCategoryやCategoryNotationから10/8を補完しない", () => {
  const breakdowns = [
    missingTaxRate("10%対象額", "STANDARD"),
    missingTaxRate("軽減8%対象額", "REDUCED"),
  ];

  for (const breakdown of breakdowns) {
    assert.equal(breakdown.taxRatePercent.status, "MISSING");
    assert.equal(formatAutoEntryFieldValue(breakdown.taxRatePercent), "未取得");
  }
});

test("配列fieldはnullの未取得と空配列のなしを区別する", () => {
  const field = <T>(value: T[] | null, status: AutoEntryField<T[]>["status"]): Pick<AutoEntryField<T[]>, "status" | "value"> => ({ value, status });

  assert.equal(getAutoEntryArrayDisplayState(field(null, "MISSING")), "missing");
  assert.equal(getAutoEntryArrayDisplayState(field(null, "OK")), "empty");
  assert.equal(getAutoEntryArrayDisplayState(field([], "OK")), "empty");
  assert.equal(getAutoEntryArrayDisplayState(field(["item"], "OK")), "items");
});
