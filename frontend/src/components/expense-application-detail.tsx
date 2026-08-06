"use client";

import { useEffect, useState } from "react";
import Link from "next/link";

import { ExpenseAttachmentSection } from "@/components/expense-attachment-section";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { AuthenticationRequiredError, fetchBackend } from "@/lib/backend-browser-client";
import {
  canShowExpenseApprovalActions,
  categoryLabels,
  expenseErrorMessage,
  statusLabels,
  type ExpenseApplication,
  yen,
} from "@/lib/expense-application";

type ErrorBody = { code?: string; message?: string };
const stepStatus: Record<string, string> = {
  WAITING: "待機中", PENDING: "承認待ち", APPROVED: "承認済み",
  RETURNED: "差戻し", SKIPPED: "省略", CANCELLED: "取消",
};

export function ExpenseApplicationDetail({ applicationId, approvalView = false }: { applicationId: string; approvalView?: boolean }) {
  const [application, setApplication] = useState<ExpenseApplication | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [comment, setComment] = useState("");
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

  async function action(path: string, body?: object) {
    setProcessing(true);
    setError(null);
    try {
      const response = await fetchBackend(path, {
        method: "POST",
        headers: body ? { "Content-Type": "application/json" } : undefined,
        body: body ? JSON.stringify(body) : undefined,
      });
      const result = (await response.json()) as ExpenseApplication & ErrorBody;
      if (!response.ok) throw new Error(expenseErrorMessage(result.code, result.message ?? "処理できませんでした。"));
      setApplication(result);
      setComment("");
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
    <Card><CardHeader><CardTitle>承認経路{application.approvalRun ? `（実行 ${application.approvalRun.runNumber}）` : ""}</CardTitle></CardHeader><CardContent>{application.approvalRun ? <ol className="space-y-3">{application.approvalRun.steps.map((step) => <li className="rounded-lg border p-3" key={step.id}><div className="flex items-center justify-between gap-3"><p className="font-medium">{step.targetOrganizationUnitName} {step.type === "ACCOUNTING" ? "経理承認" : "部門長承認"}</p><Badge variant="secondary">{stepStatus[step.status] ?? step.status}</Badge></div>{step.processedBy && <p className="mt-1 text-sm text-muted-foreground">処理者: {step.processedBy}{step.processedAt ? ` / ${new Date(step.processedAt).toLocaleString("ja-JP")}` : ""}</p>}{step.comment && <p className="mt-1 text-sm">コメント: {step.comment}</p>}</li>)}</ol> : <p className="text-muted-foreground">申請後に承認経路が確定します。</p>}</CardContent></Card>
    {canShowExpenseApprovalActions(application) && <Card><CardHeader><CardTitle>承認操作</CardTitle></CardHeader><CardContent className="space-y-3"><label className="grid gap-1 text-sm">コメント（差戻し時は必須）<textarea className="min-h-24 rounded-md border bg-background p-3" onChange={(event) => setComment(event.target.value)} value={comment} /></label><div className="flex justify-end gap-3"><Button disabled={processing || !comment.trim()} onClick={() => void action(`/api/backend/expense-approvals/${application.pendingStepId}/return`, { comment })} variant="outline">差戻し</Button><Button disabled={processing} onClick={() => void action(`/api/backend/expense-approvals/${application.pendingStepId}/approve`, { comment })}>承認</Button></div></CardContent></Card>}
    <div className="flex flex-wrap justify-end gap-3">
      {application.editable && <Button render={<Link href={`/expenses/${application.id}/edit`} />} variant="outline">編集</Button>}
      {application.cancellable && <Button disabled={processing} onClick={() => { if (window.confirm("この申請を取り下げますか？")) void action(`/api/backend/expense-applications/${application.id}/cancel`); }} variant="outline">取下げ</Button>}
      <Button render={<Link href={approvalView ? "/approvals" : "/expenses"} />} variant="outline">一覧へ戻る</Button>
    </div>
  </div>;
}
