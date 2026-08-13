import assert from "node:assert/strict";
import test from "node:test";

import {
  ExpenseSubmitResultError,
  submitExpenseApplicationWithReconciliation,
} from "./expense-submit.ts";

const ID = "123e4567-e89b-42d3-a456-426614174000";

function application(status: string): Record<string, unknown> {
  return { id: ID, status };
}

function sequence(responses: Response[]) {
  const calls: Array<{ input: string; method: string }> = [];
  const fetchImplementation = async (input: string | URL | Request, init?: RequestInit) => {
    calls.push({ input: String(input), method: init?.method ?? "GET" });
    const response = responses.shift();
    if (!response) throw new Error("unexpected request");
    return response;
  };
  return { calls, fetchImplementation };
}

test("503後のPENDING read-backをcommit済みとして扱いPOSTを再送しない", async () => {
  const request = sequence([
    Response.json({ code: "BACKEND_UNAVAILABLE" }, { status: 503 }),
    Response.json(application("PENDING_APPROVAL")),
  ]);

  const result = await submitExpenseApplicationWithReconciliation(
    ID, "submit", request.fetchImplementation,
  );

  assert.equal(result.status, "PENDING_APPROVAL");
  assert.deepEqual(request.calls.map((call) => call.method), ["POST", "GET"]);
});

test("POSTのnetwork errorもPENDING read-backで照合しPOSTを再送しない", async () => {
  const calls: string[] = [];
  const result = await submitExpenseApplicationWithReconciliation(
    ID,
    "submit",
    async (_input, init) => {
      calls.push(init?.method ?? "GET");
      if (calls.length === 1) throw new TypeError("network error");
      return Response.json(application("PENDING_APPROVAL"));
    },
  );

  assert.equal(result.status, "PENDING_APPROVAL");
  assert.deepEqual(calls, ["POST", "GET"]);
});

test("INVALID_STATUS後のAPPROVED read-backもcommit済みとして扱う", async () => {
  const request = sequence([
    Response.json({ code: "EXPENSE_APPLICATION_INVALID_STATUS" }, { status: 409 }),
    Response.json(application("APPROVED")),
  ]);

  const result = await submitExpenseApplicationWithReconciliation(
    ID, "resubmit", request.fetchImplementation,
  );

  assert.equal(result.status, "APPROVED");
  assert.deepEqual(request.calls.map((call) => call.method), ["POST", "GET"]);
});

test("503後もDRAFTなら再試行可能なエラーとして留まりPOSTを再送しない", async () => {
  const request = sequence([
    Response.json({ code: "BACKEND_UNAVAILABLE" }, { status: 503 }),
    Response.json(application("DRAFT")),
  ]);

  await assert.rejects(
    submitExpenseApplicationWithReconciliation(ID, "submit", request.fetchImplementation),
    (cause: unknown) => cause instanceof ExpenseSubmitResultError
      && cause.retryable
      && !cause.resultUnknown,
  );
  assert.deepEqual(request.calls.map((call) => call.method), ["POST", "GET"]);
});

test("read-backも失敗した場合は結果不明を明示しPOSTを再送しない", async () => {
  const request = sequence([
    Response.json({ code: "BACKEND_UNAVAILABLE" }, { status: 503 }),
    Response.json({ code: "BACKEND_UNAVAILABLE" }, { status: 503 }),
  ]);

  await assert.rejects(
    submitExpenseApplicationWithReconciliation(ID, "submit", request.fetchImplementation),
    (cause: unknown) => cause instanceof ExpenseSubmitResultError
      && cause.resultUnknown
      && !cause.retryable,
  );
  assert.deepEqual(request.calls.map((call) => call.method), ["POST", "GET"]);
});

test("非ambiguousエラーはread-backせずそのまま表示する", async () => {
  const request = sequence([
    Response.json({ code: "ACCOUNTING_APPROVER_NOT_FOUND" }, { status: 422 }),
  ]);

  await assert.rejects(
    submitExpenseApplicationWithReconciliation(ID, "submit", request.fetchImplementation),
    /経理承認者/,
  );
  assert.deepEqual(request.calls.map((call) => call.method), ["POST"]);
});
