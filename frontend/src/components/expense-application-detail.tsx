"use client";

import { useEffect, useState } from "react";

import { ExpenseAttachmentSection } from "@/components/expense-attachment-section";
import { WorkflowTimeline } from "@/components/workflow-timeline";
import { Badge } from "@/components/ui/badge";
import { Button, LinkButton } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { AuthenticationRequiredError, fetchBackend } from "@/lib/backend-browser-client";
import {
  loadLatestExpenseWorkflow,
  performExpenseDetailAction,
} from "@/lib/expense-detail";
import {
  categoryLabels,
  statusLabels,
  type ExpenseApplication,
  yen,
} from "@/lib/expense-application";
import type { WorkflowInstance } from "@/lib/workflow";

export function ExpenseApplicationDetail({ applicationId, showWorkflow = true, backHref = "/expenses" }: { applicationId: string; showWorkflow?: boolean; backHref?: string }) {
  const [application, setApplication] = useState<ExpenseApplication | null>(null);
  const [workflow, setWorkflow] = useState<WorkflowInstance | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [processing, setProcessing] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    fetchBackend(`/api/backend/expense-applications/${applicationId}`, {
      cache: "no-store", signal: controller.signal,
    }).then(async (response) => {
      if (!response.ok) throw new Error(response.status === 403
        ? "この申請を表示する権限がありません。" : "申請を取得できませんでした。");
      setApplication((await response.json()) as ExpenseApplication);
      setError(null);
    }).catch((cause) => {
      if (!controller.signal.aborted && !(cause instanceof AuthenticationRequiredError)) {
        setError(cause instanceof Error ? cause.message : "申請を取得できませんでした。");
      }
    });
    return () => controller.abort();
  }, [applicationId]);

  useEffect(() => {
    if (!showWorkflow) return;
    const controller = new AbortController();
    loadLatestExpenseWorkflow(applicationId, fetchBackend, controller.signal)
      .then(setWorkflow).catch((cause) => {
        if (!controller.signal.aborted && !(cause instanceof AuthenticationRequiredError)) {
          setError(cause instanceof Error ? cause.message : "承認経路を取得できませんでした。");
        }
      });
    return () => controller.abort();
  }, [applicationId, showWorkflow]);

  async function action(path: string, body?: object) {
    setProcessing(true);
    setError(null);
    try {
      await performExpenseDetailAction({
        actionPath: path,
        applicationId,
        showWorkflow,
        body,
        onApplication: setApplication,
        onWorkflow: setWorkflow,
        fetchImplementation: fetchBackend,
      });
    } catch (cause) {
      if (!(cause instanceof AuthenticationRequiredError)) {
        setError(cause instanceof Error ? cause.message : "処理できませんでした。");
      }
    } finally {
      setProcessing(false);
    }
  }

  if (!application && !error) return <Card><CardContent>申請を読み込んでいます…</CardContent></Card>;
  if (!application) return <Card><CardContent className="text-destructive">{error}</CardContent></Card>;

  return <div className="space-y-6">
    {error && <Card><CardContent className="text-destructive">{error}</CardContent></Card>}
    {application.returnReason && <Card className="border-destructive/40"><CardHeader><CardTitle>差戻し理由</CardTitle></CardHeader><CardContent>{application.returnReason}</CardContent></Card>}
    <Card><CardHeader className="flex-row items-center justify-between"><CardTitle>{application.title}</CardTitle><Badge>{statusLabels[application.status]}</Badge></CardHeader><CardContent className="grid gap-4 text-sm sm:grid-cols-2 lg:grid-cols-3">
      <div><p className="text-muted-foreground">申請番号</p><p>{application.applicationNumber}</p></div>
      <div><p className="text-muted-foreground">区分</p><p>{categoryLabels[application.category]}</p></div>
      <div><p className="text-muted-foreground">利用日</p><p>{application.expenseDate}</p></div>
      <div><p className="text-muted-foreground">申請者</p><p>{application.applicantName}</p></div>
      <div><p className="text-muted-foreground">申請時所属</p><p>{application.divisionUnitName} / {application.organizationUnitName}</p></div>
      <div><p className="text-muted-foreground">合計</p><p className="font-semibold">{yen(application.totalAmount)}</p></div>
      <div className="sm:col-span-2 lg:col-span-3"><p className="text-muted-foreground">利用目的</p><p className="whitespace-pre-wrap">{application.purpose}</p></div>
      {application.remarks && <div className="sm:col-span-2 lg:col-span-3"><p className="text-muted-foreground">備考</p><p className="whitespace-pre-wrap">{application.remarks}</p></div>}
    </CardContent></Card>
    <Card><CardHeader><CardTitle>明細</CardTitle></CardHeader><CardContent><div className="overflow-x-auto"><table className="w-full min-w-2xl text-left text-sm"><thead className="border-b text-muted-foreground"><tr><th className="p-2">利用日</th><th className="p-2">内容</th><th className="p-2">支払先・区間</th><th className="p-2 text-right">金額</th></tr></thead><tbody className="divide-y">{application.items.map((item) => <tr key={item.id}><td className="p-2">{item.expenseDate}</td><td className="p-2">{item.description}{item.participants && <p className="text-xs text-muted-foreground">参加者: {item.participants}</p>}</td><td className="p-2">{item.merchantName || [item.origin, item.destination].filter(Boolean).join(" → ") || "-"}</td><td className="p-2 text-right">{yen(item.amount)}</td></tr>)}</tbody></table></div></CardContent></Card>
    <ExpenseAttachmentSection applicationId={application.id} editable={application.editable} />
    {showWorkflow && workflow && <WorkflowTimeline runNumber={workflow.runNumber} steps={workflow.steps} />}
    <div className="flex flex-wrap justify-end gap-3">
      {application.editable && <LinkButton href={`/expenses/${application.id}/edit`} variant="outline">編集</LinkButton>}
      {application.cancellable && <Button disabled={processing} onClick={() => { if (window.confirm("この申請を取り下げますか？")) void action(`/api/backend/expense-applications/${application.id}/cancel`); }} variant="outline">取下げ</Button>}
      <LinkButton href={backHref} variant="outline">一覧へ戻る</LinkButton>
    </div>
  </div>;
}
