"use client";

import { useEffect, useState } from "react";
import { Button, LinkButton } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { AuthenticationRequiredError, fetchBackend } from "@/lib/backend-browser-client";
import type { WorkflowTaskPage } from "@/lib/workflow";

export function WorkflowTaskList() {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<WorkflowTaskPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    fetchBackend(`/api/backend/workflow/tasks?page=${page}&size=20&sort=stepOrder,asc`, {
      cache: "no-store", signal: controller.signal,
    }).then(async (response) => {
      if (response.status === 403) throw new Error("承認権限がありません（403）。");
      if (!response.ok) throw new Error("ワークフロータスクを取得できませんでした。");
      setData(await response.json() as WorkflowTaskPage); setError(null);
    }).catch((cause) => {
      if (!controller.signal.aborted && !(cause instanceof AuthenticationRequiredError)) {
        setError(cause instanceof Error ? cause.message : "ワークフロータスクを取得できませんでした。");
      }
    });
    return () => controller.abort();
  }, [page]);
  if (error) return <Card><CardContent className="text-destructive">{error}</CardContent></Card>;
  if (!data) return <Card><CardContent>読み込んでいます…</CardContent></Card>;
  return <Card><CardContent><div className="overflow-x-auto"><table className="w-full min-w-3xl text-left text-sm">
    <thead className="border-b text-muted-foreground"><tr><th className="p-3">業務</th><th className="p-3">参照番号</th><th className="p-3">申請者</th><th className="p-3">件名</th><th className="p-3">現在工程</th><th className="p-3">操作</th></tr></thead>
    <tbody className="divide-y">{data.content.map((task) => <tr key={task.stepId}>
      <td className="p-3">{task.workflowName}</td><td className="p-3">{task.subjectReference}</td>
      <td className="p-3">{task.requesterName}</td><td className="p-3 font-medium">{task.subjectTitle}</td>
      <td className="p-3">{task.stepName}</td><td className="p-3"><LinkButton href={`/approvals/${task.stepId}`}>確認</LinkButton></td>
    </tr>)}</tbody></table></div>
    {data.content.length === 0 && <p className="py-8 text-center text-muted-foreground">現在の承認待ちはありません。</p>}
    <div className="mt-4 flex justify-end gap-3"><Button disabled={page === 0} onClick={() => setPage((value) => value - 1)} variant="outline">前へ</Button>
      <span className="self-center text-sm">{page + 1} / {Math.max(data.totalPages, 1)}</span>
      <Button disabled={page + 1 >= data.totalPages} onClick={() => setPage((value) => value + 1)} variant="outline">次へ</Button></div>
  </CardContent></Card>;
}
