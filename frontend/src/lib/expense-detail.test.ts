import assert from "node:assert/strict";
import test from "node:test";

import { performExpenseDetailAction } from "./expense-detail.ts";
import type { ExpenseApplication } from "./expense-application.ts";
import type { WorkflowInstance } from "./workflow.ts";

const ID = "123e4567-e89b-42d3-a456-426614174000";

test("取下げ成功後に申請状態を更新してlatest workflowを再取得する", async () => {
  const events: string[] = [];
  const calls: Array<{ path: string; method: string }> = [];
  const cancelledApplication = { id: ID, status: "CANCELLED" } as ExpenseApplication;
  const cancelledWorkflow = {
    instanceId: "223e4567-e89b-42d3-a456-426614174000",
    status: "CANCELLED",
    steps: [{ status: "CANCELLED" }],
  } as WorkflowInstance;
  const responses = [
    Response.json(cancelledApplication),
    Response.json(cancelledWorkflow),
  ];
  const state: {
    application: ExpenseApplication | null;
    workflow: WorkflowInstance | null;
  } = { application: null, workflow: null };

  await performExpenseDetailAction({
    actionPath: `/api/backend/expense-applications/${ID}/cancel`,
    applicationId: ID,
    showWorkflow: true,
    onApplication: (next) => {
      state.application = next;
      events.push(`application:${next.status}`);
    },
    onWorkflow: (next) => {
      state.workflow = next;
      events.push(`workflow:${next?.steps[0]?.status}`);
    },
    fetchImplementation: async (input, init) => {
      calls.push({ path: String(input), method: init?.method ?? "GET" });
      events.push(init?.method ?? "GET");
      const response = responses.shift();
      if (!response) throw new Error("unexpected request");
      return response;
    },
  });

  assert.equal(state.application?.status, "CANCELLED");
  assert.equal(state.workflow?.status, "CANCELLED");
  assert.equal(state.workflow?.steps[0]?.status, "CANCELLED");
  assert.deepEqual(calls, [
    { path: `/api/backend/expense-applications/${ID}/cancel`, method: "POST" },
    {
      path: `/api/backend/workflow/subjects/EXPENSE_APPLICATION/${ID}/latest`,
      method: "GET",
    },
  ]);
  assert.deepEqual(events, [
    "POST", "application:CANCELLED", "GET", "workflow:CANCELLED",
  ]);
});
