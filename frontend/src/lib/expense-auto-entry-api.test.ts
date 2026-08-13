import assert from "node:assert/strict";
import test from "node:test";

import { expenseAutoEntrySafeErrorMessage } from "./expense-auto-entry-api.ts";

test("AUTO_ENTRY handoff errorを安全な利用者向けメッセージへ変換する", () => {
  assert.match(expenseAutoEntrySafeErrorMessage(400, "EXPENSE_AUTO_ENTRY_SOURCE_MAPPING_INVALID"), /対応/);
  assert.match(expenseAutoEntrySafeErrorMessage(422, "EXPENSE_AUTO_ENTRY_CURRENCY_UNSUPPORTED"), /JPY/);
  assert.match(expenseAutoEntrySafeErrorMessage(409, "DOCUMENT_ANALYSIS_RESULT_NOT_READY"), /準備/);
  assert.match(expenseAutoEntrySafeErrorMessage(503, "DOCUMENT_ANALYSIS_STORAGE_UNAVAILABLE"), /利用/);
  assert.match(expenseAutoEntrySafeErrorMessage(500, "UNKNOWN"), /作成/);
});

test("保存済みAUTO_ENTRY下書きのエラーも安全な利用者向け文言へ変換する", () => {
  assert.match(expenseAutoEntrySafeErrorMessage(404, "EXPENSE_AUTO_ENTRY_DRAFT_NOT_FOUND"), /下書き/);
  assert.match(expenseAutoEntrySafeErrorMessage(403, "UNKNOWN"), /権限/);
  assert.match(expenseAutoEntrySafeErrorMessage(409, "OPTIMISTIC_LOCK_CONFLICT"), /競合/);
  assert.match(expenseAutoEntrySafeErrorMessage(409, "EXPENSE_AUTO_ENTRY_DRAFT_NOT_EDITABLE"), /編集/);
});
