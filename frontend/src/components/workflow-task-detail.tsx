"use client";

import { useEffect, useState } from "react";
import { ExpenseApplicationDetail } from "@/components/expense-application-detail";
import { WorkflowActionPanel } from "@/components/workflow-action-panel";
import { WorkflowTimeline } from "@/components/workflow-timeline";
import { Card, CardContent } from "@/components/ui/card";
import { AuthenticationRequiredError, fetchBackend } from "@/lib/backend-browser-client";
import type { WorkflowTaskDetail as WorkflowTaskDetailModel } from "@/lib/workflow";

export function WorkflowTaskDetail({ stepId }: { stepId: string }) {
  const [detail, setDetail] = useState<WorkflowTaskDetailModel | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    fetchBackend(`/api/backend/workflow/tasks/${stepId}`, { cache: "no-store", signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error(response.status === 404
          ? "このタスクは既に処理されたか、表示できません。" : "タスクを取得できませんでした。");
        setDetail(await response.json() as WorkflowTaskDetailModel);
      }).catch((cause) => {
        if (!controller.signal.aborted && !(cause instanceof AuthenticationRequiredError)) {
          setError(cause instanceof Error ? cause.message : "タスクを取得できませんでした。");
        }
      });
    return () => controller.abort();
  }, [stepId]);
  if (error) return <Card><CardContent className="text-destructive">{error}</CardContent></Card>;
  if (!detail) return <Card><CardContent>読み込んでいます…</CardContent></Card>;
  return <div className="space-y-6">
    <Card><CardContent className="grid gap-3 text-sm sm:grid-cols-3">
      <div><p className="text-muted-foreground">ワークフロー</p><p>{detail.task.workflowName}</p></div>
      <div><p className="text-muted-foreground">現在工程</p><p>{detail.task.stepName}</p></div>
      <div><p className="text-muted-foreground">申請者</p><p>{detail.task.requesterName}</p></div>
    </CardContent></Card>
    {detail.task.subjectType === "EXPENSE_APPLICATION"
      ? <ExpenseApplicationDetail applicationId={detail.task.subjectId} backHref="/approvals" showWorkflow={false} />
      : <Card><CardContent>この業務種別の詳細表示にはまだ対応していません。</CardContent></Card>}
    <WorkflowTimeline runNumber={detail.task.runNumber} steps={detail.timeline} />
    <WorkflowActionPanel stepId={stepId} onCompleted={() => { window.location.href = "/approvals"; }} />
  </div>;
}
