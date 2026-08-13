import assert from "node:assert/strict";
import test from "node:test";

import {
  ExpenseAutoEntryApiError,
  createExpenseAutoEntryDraft,
  expenseAutoEntrySafeErrorMessage,
  updateExpenseAutoEntryDraft,
} from "./expense-auto-entry-api.ts";

const ID = "123e4567-e89b-42d3-a456-426614174000";

const createRequest = {
  analysisId: ID,
  application: {
    category: "OTHER" as const,
    title: "件名",
    purpose: "目的",
    expenseDate: "2026-08-13",
    remarks: "",
    items: [{
      sourceLineItemIndex: 0,
      expenseDate: "2026-08-13",
      description: "明細",
      amount: 100,
      merchantName: "",
      origin: "",
      destination: "",
      transportationType: "",
      participants: "",
    }],
  },
  document: {
    issuerName: "発行元",
    issuerTaxRegistrationNumber: "",
    invoiceTotalAmount: 100,
  },
  confirmedFieldPaths: [],
};

const updateRequest = {
  applicationVersion: 1,
  contextVersion: 1,
  application: createRequest.application,
  document: createRequest.document,
  confirmedFieldPaths: [],
};

function unavailableFetch() {
  let calls = 0;
  return {
    calls: () => calls,
    fetchImplementation: async () => {
      calls += 1;
      return Response.json({ code: "BACKEND_UNAVAILABLE" }, { status: 503 });
    },
  };
}

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

test("handoffの503は内部再送せず、利用者による同一analysisIdの再試行に委ねる", async () => {
  const request = unavailableFetch();

  await assert.rejects(
    createExpenseAutoEntryDraft(createRequest, request.fetchImplementation),
    (cause: unknown) => cause instanceof ExpenseAutoEntryApiError
      && cause.status === 503
      && cause.code === "BACKEND_UNAVAILABLE",
  );
  assert.equal(request.calls(), 1);
});

test("AUTO_ENTRY PUTの503はblind retryせず再読み込みを要求できる", async () => {
  const request = unavailableFetch();

  await assert.rejects(
    updateExpenseAutoEntryDraft(ID, updateRequest, request.fetchImplementation),
    (cause: unknown) => cause instanceof ExpenseAutoEntryApiError
      && cause.status === 503
      && cause.code === "BACKEND_UNAVAILABLE",
  );
  assert.equal(request.calls(), 1);
});
