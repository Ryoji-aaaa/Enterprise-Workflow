"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ClipboardCheck } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { categoryLabels, type ExpensePage, yen } from "@/lib/expense-application";

export default function ApprovalsPage() {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<ExpensePage | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    fetch(`/api/backend/expense-approvals/pending?page=${page}&size=20&sort=submittedAt,asc`, { cache: "no-store", signal: controller.signal })
      .then(async (response) => {
        if (response.status === 403) throw new Error("承認権限がありません（403）。");
        if (!response.ok) throw new Error("承認待ち一覧を取得できませんでした。");
        setData((await response.json()) as ExpensePage); setError(null);
      }).catch((cause) => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : "承認待ち一覧を取得できませんでした。"); });
    return () => controller.abort();
  }, [page]);
  return <main className="min-h-svh bg-muted/30 p-4 md:p-8"><div className="mx-auto max-w-7xl"><div className="mb-6 flex items-center justify-between gap-3"><div><h1 className="flex items-center gap-2 text-2xl font-semibold"><ClipboardCheck className="text-primary" />承認待ち</h1><p className="text-sm text-muted-foreground">現在、自分が候補者の承認ステップだけを表示します。</p></div><Button render={<Link href="/top" />} variant="outline">トップへ</Button></div>{error && <Card><CardContent className="text-destructive">{error}</CardContent></Card>}{!error && !data && <Card><CardContent>読み込んでいます…</CardContent></Card>}{data && <Card><CardContent><div className="overflow-x-auto"><table className="w-full min-w-3xl text-left text-sm"><thead className="border-b text-muted-foreground"><tr><th className="p-3">申請番号</th><th className="p-3">申請者</th><th className="p-3">件名</th><th className="p-3">区分</th><th className="p-3">金額</th><th className="p-3">操作</th></tr></thead><tbody className="divide-y">{data.content.map((item) => <tr key={item.id}><td className="p-3">{item.applicationNumber}</td><td className="p-3">{item.applicantName}</td><td className="p-3 font-medium">{item.title}</td><td className="p-3">{categoryLabels[item.category]}</td><td className="p-3">{yen(item.totalAmount)}</td><td className="p-3"><Button render={<Link href={`/approvals/${item.id}`} />}>確認</Button></td></tr>)}</tbody></table></div>{data.content.length === 0 && <p className="py-8 text-center text-muted-foreground">現在の承認待ちはありません。</p>}<div className="mt-4 flex justify-end gap-3"><Button disabled={page === 0} onClick={() => setPage((value) => value - 1)} variant="outline">前へ</Button><span className="self-center text-sm">{page + 1} / {Math.max(data.totalPages, 1)}</span><Button disabled={page + 1 >= data.totalPages} onClick={() => setPage((value) => value + 1)} variant="outline">次へ</Button></div></CardContent></Card>}</div></main>;
}
