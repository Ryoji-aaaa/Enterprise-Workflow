import assert from "node:assert/strict";
import test from "node:test";

import { probeExpenseEditRoute } from "./expense-edit-route.ts";
import { ExpenseAutoEntryApiError, type ExpenseAutoEntryDraftResponse } from "./expense-auto-entry-api.ts";

const ID = "123e4567-e89b-42d3-a456-426614174000";

test("AUTO_ENTRY contextがあれば専用確認routeを選ぶ", async () => {
  const result = await probeExpenseEditRoute(
    ID,
    undefined,
    async () => ({ application: { id: ID } } as ExpenseAutoEntryDraftResponse),
  );
  assert.equal(result, "AUTO_ENTRY");
});

test("exactなAUTO_ENTRY未作成404だけ通常編集を選ぶ", async () => {
  const result = await probeExpenseEditRoute(ID, undefined, async () => {
    throw new ExpenseAutoEntryApiError(
      404, "EXPENSE_AUTO_ENTRY_DRAFT_NOT_FOUND", "not found",
    );
  });
  assert.equal(result, "GENERIC");
});

test("所有者外404や403・5xxを通常編集として扱わずfail closedにする", async () => {
  for (const [status, code] of [
    [404, "EXPENSE_APPLICATION_NOT_FOUND"],
    [403, "AUTHORIZATION_DENIED"],
    [503, "BACKEND_UNAVAILABLE"],
  ] as const) {
    await assert.rejects(probeExpenseEditRoute(ID, undefined, async () => {
      throw new ExpenseAutoEntryApiError(status, code, "failed");
    }), ExpenseAutoEntryApiError);
  }
});
