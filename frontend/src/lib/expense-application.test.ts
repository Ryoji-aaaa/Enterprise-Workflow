import assert from "node:assert/strict";
import test from "node:test";

import {
  categoryFieldLabels,
  expenseErrorMessage,
  isExpenseInputValid,
  totalExpenseAmount,
} from "./expense-application.ts";

test("明細追加・削除後の金額を再計算する", () => {
  const base = {
    expenseDate: "2026-08-02", description: "test", merchantName: "",
    origin: "", destination: "", transportationType: "", participants: "",
  };
  assert.equal(totalExpenseAmount([{ ...base, amount: 1200 }, { ...base, amount: 800 }]), 2000);
  assert.equal(totalExpenseAmount([{ ...base, amount: 1200 }]), 1200);
});

test("カテゴリ切替で固有項目が変わる", () => {
  assert.deepEqual(categoryFieldLabels("MEAL"), ["店舗名", "参加者"]);
  assert.deepEqual(categoryFieldLabels("TRANSPORTATION"), ["交通手段", "出発地", "到着地"]);
  assert.deepEqual(categoryFieldLabels("TRAINING"), ["研修名", "主催者", "開催日"]);
  assert.deepEqual(categoryFieldLabels("CERTIFICATION"), [
    "資格名", "試験実施団体", "受験予定日または受験日",
  ]);
  assert.deepEqual(categoryFieldLabels("OTHER"), ["内容"]);
});

test("業務エラーコードを日本語へ変換する", () => {
  assert.match(expenseErrorMessage("WORKFLOW_ASSIGNEE_NOT_FOUND", "fallback"), /承認候補者/);
  assert.equal(expenseErrorMessage("UNKNOWN", "fallback"), "fallback");
});

test("共通項目とカテゴリ別必須項目が不足する申請を拒否する", () => {
  const transportation = {
    expenseDate: "2026-08-02", description: "電車往復", amount: 1200,
    merchantName: "", origin: "東京", destination: "横浜",
    transportationType: "TRAIN", participants: "",
  };
  assert.equal(isExpenseInputValid(
    "TRANSPORTATION", "交通費", "顧客訪問", "2026-08-02", [transportation],
  ), true);
  assert.equal(isExpenseInputValid(
    "TRANSPORTATION", "交通費", "顧客訪問", "2026-08-02",
    [{ ...transportation, destination: "" }],
  ), false);
  assert.equal(isExpenseInputValid(
    "MEAL", "会食費", "商談", "2026-08-02",
    [{ ...transportation, merchantName: "店舗", participants: "" }],
  ), false);
  assert.equal(isExpenseInputValid(
    "TRAINING", "研修費", "受講", "2026-08-02",
    [{ ...transportation, merchantName: "" }],
  ), false);
});
