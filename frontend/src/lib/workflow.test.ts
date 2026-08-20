import assert from "node:assert/strict";
import test from "node:test";
import { workflowStepStatusLabels, type WorkflowTask } from "./workflow.ts";

test("workflow task contract identifies a task by stepId and keeps subject generic", () => {
  const task: WorkflowTask = {
    stepId: "00000000-0000-4000-8000-000000000001",
    instanceId: "00000000-0000-4000-8000-000000000002",
    runNumber: 1,
    workflowCode: "EXPENSE_APPROVAL",
    workflowName: "経費承認",
    subjectType: "EXPENSE_APPLICATION",
    subjectId: "00000000-0000-4000-8000-000000000003",
    subjectReference: "EXP-20260818-000001",
    subjectTitle: "交通費",
    requesterName: "申請者",
    stepName: "所属部門長承認",
    submittedAt: "2026-08-18T00:00:00Z",
  };
  assert.notEqual(task.stepId, task.subjectId);
  assert.equal(task.subjectType, "EXPENSE_APPLICATION");
  assert.equal(workflowStepStatusLabels.PENDING, "承認待ち");
});
