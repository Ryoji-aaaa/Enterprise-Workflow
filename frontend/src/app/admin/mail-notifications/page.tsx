"use client";

import { FormEvent, useEffect, useState } from "react";
import Link from "next/link";
import { MailCheck } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import {
  displayDate,
  type MailNotificationPage,
  type NotificationStatus,
  type NotificationType,
  notificationStatuses,
  notificationStatusLabels,
  notificationTypes,
  notificationTypeLabels,
} from "@/lib/mail-notification";

type Filters = {
  status: NotificationStatus | "";
  notificationType: NotificationType | "";
  recipientEmail: string;
  applicationNumber: string;
  from: string;
  to: string;
};

const initialFilters: Filters = {
  status: "SENT",
  notificationType: "",
  recipientEmail: "",
  applicationNumber: "",
  from: "",
  to: "",
};

function queryString(filters: Filters, page: number): string {
  const query = new URLSearchParams({ page: String(page), size: "50" });
  for (const key of ["status", "notificationType", "recipientEmail", "applicationNumber"] as const) {
    if (filters[key]) query.set(key, filters[key]);
  }
  if (filters.from) query.set("from", new Date(filters.from).toISOString());
  if (filters.to) query.set("to", new Date(filters.to).toISOString());
  return query.toString();
}

export default function MailNotificationsPage() {
  const [draft, setDraft] = useState<Filters>(initialFilters);
  const [filters, setFilters] = useState<Filters>(initialFilters);
  const [page, setPage] = useState(0);
  const [data, setData] = useState<MailNotificationPage | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    fetch(`/api/backend/admin/mail-notifications?${queryString(filters, page)}`, {
      cache: "no-store",
      signal: controller.signal,
    }).then(async (response) => {
      if (response.status === 403) throw new Error("メール通知履歴を参照する権限がありません（403）。");
      if (!response.ok) throw new Error("メール通知履歴を取得できませんでした。");
      setData((await response.json()) as MailNotificationPage);
      setError(null);
    }).catch((cause) => {
      if (!controller.signal.aborted) {
        setError(cause instanceof Error ? cause.message : "メール通知履歴を取得できませんでした。");
      }
    });
    return () => controller.abort();
  }, [filters, page]);

  function search(event: FormEvent) {
    event.preventDefault();
    setPage(0);
    setFilters({ ...draft });
  }

  return (
    <main className="min-h-svh bg-muted/30 p-4 md:p-8">
      <div className="mx-auto max-w-7xl">
        <div className="mb-6 flex items-center justify-between gap-3">
          <div>
            <h1 className="flex items-center gap-2 text-2xl font-semibold"><MailCheck className="text-primary" />送付済メール一覧</h1>
            <p className="mt-1 text-sm text-muted-foreground">ローカル開発環境のメール通知送付履歴を確認します。</p>
          </div>
          <Button render={<Link href="/top" />} variant="outline">トップへ戻る</Button>
        </div>

        <Card className="mb-4">
          <CardHeader><CardTitle>検索条件</CardTitle></CardHeader>
          <CardContent>
            <form className="grid gap-3 md:grid-cols-3" onSubmit={search}>
              <label className="grid gap-1 text-sm">状態
                <select className="h-9 rounded-md border bg-background px-3" value={draft.status} onChange={(event) => setDraft({ ...draft, status: event.target.value as Filters["status"] })}>
                  <option value="">すべて</option>
                  {notificationStatuses.map((status) => <option key={status} value={status}>{notificationStatusLabels[status]}</option>)}
                </select>
              </label>
              <label className="grid gap-1 text-sm">通知種別
                <select className="h-9 rounded-md border bg-background px-3" value={draft.notificationType} onChange={(event) => setDraft({ ...draft, notificationType: event.target.value as Filters["notificationType"] })}>
                  <option value="">すべて</option>
                  {notificationTypes.map((type) => <option key={type} value={type}>{notificationTypeLabels[type]}</option>)}
                </select>
              </label>
              <label className="grid gap-1 text-sm">宛先メールアドレス<Input value={draft.recipientEmail} onChange={(event) => setDraft({ ...draft, recipientEmail: event.target.value })} /></label>
              <label className="grid gap-1 text-sm">申請番号<Input value={draft.applicationNumber} onChange={(event) => setDraft({ ...draft, applicationNumber: event.target.value })} /></label>
              <label className="grid gap-1 text-sm">開始日時<Input type="datetime-local" value={draft.from} onChange={(event) => setDraft({ ...draft, from: event.target.value })} /></label>
              <label className="grid gap-1 text-sm">終了日時<Input type="datetime-local" value={draft.to} onChange={(event) => setDraft({ ...draft, to: event.target.value })} /></label>
              <div className="flex gap-2 md:col-span-3">
                <Button type="submit">検索</Button>
                <Button type="button" variant="outline" onClick={() => { setDraft(initialFilters); setFilters(initialFilters); setPage(0); }}>条件を戻す</Button>
              </div>
            </form>
          </CardContent>
        </Card>

        {error && <Card><CardContent className="text-destructive">{error}</CardContent></Card>}
        {!error && !data && <Card><CardContent>メール通知履歴を読み込んでいます…</CardContent></Card>}
        {data && <Card>
          <CardHeader><CardTitle>通知履歴（{data.totalElements}件）</CardTitle></CardHeader>
          <CardContent>
            <div className="overflow-x-auto">
              <table className="w-full min-w-6xl text-left text-sm">
                <thead className="border-b text-muted-foreground"><tr><th className="p-3">送付日時</th><th className="p-3">状態</th><th className="p-3">種別</th><th className="p-3">宛先</th><th className="p-3">件名</th><th className="p-3">対象申請</th><th className="p-3">試行</th><th className="p-3">操作</th></tr></thead>
                <tbody className="divide-y">{data.content.map((item) => <tr key={item.notificationId}>
                  <td className="p-3 whitespace-nowrap">{displayDate(item.sentAt)}</td>
                  <td className="p-3"><Badge variant={item.status === "FAILED" ? "destructive" : "secondary"}>{notificationStatusLabels[item.status]}</Badge></td>
                  <td className="p-3">{notificationTypeLabels[item.notificationType]}</td>
                  <td className="p-3"><span className="block font-medium">{item.recipientName ?? "—"}</span><span className="text-muted-foreground">{item.recipientEmail}</span></td>
                  <td className="p-3 font-medium">{item.subject}</td>
                  <td className="p-3">{item.applicationNumber ? <><span className="block">{item.applicationNumber}</span><span className="text-muted-foreground">{item.applicationTitle}</span></> : "—"}</td>
                  <td className="p-3 text-center">{item.attemptCount}</td>
                  <td className="p-3"><Button render={<Link href={`/admin/mail-notifications/${item.notificationId}`} />} variant="outline">詳細</Button></td>
                </tr>)}</tbody>
              </table>
            </div>
            {data.content.length === 0 && <p className="py-8 text-center text-muted-foreground">条件に一致するメール通知はありません。</p>}
            <div className="mt-4 flex justify-end gap-3"><Button disabled={data.page === 0} onClick={() => setPage((value) => value - 1)} variant="outline">前へ</Button><span className="self-center text-sm">{data.page + 1} / {Math.max(data.totalPages, 1)}</span><Button disabled={data.page + 1 >= data.totalPages} onClick={() => setPage((value) => value + 1)} variant="outline">次へ</Button></div>
          </CardContent>
        </Card>}
      </div>
    </main>
  );
}
