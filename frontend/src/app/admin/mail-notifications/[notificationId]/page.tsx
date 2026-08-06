"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { MailOpen } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  displayDate,
  type MailNotificationDetail,
  notificationStatusLabels,
  notificationTypeLabels,
} from "@/lib/mail-notification";

function Entry({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><dt className="text-xs text-muted-foreground">{label}</dt><dd className="mt-1 break-words text-sm">{children ?? "—"}</dd></div>;
}

export default function MailNotificationDetailPage() {
  const { notificationId } = useParams<{ notificationId: string }>();
  const [notification, setNotification] = useState<MailNotificationDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    fetch(`/api/backend/admin/mail-notifications/${encodeURIComponent(notificationId)}`, {
      cache: "no-store",
      signal: controller.signal,
    }).then(async (response) => {
      if (response.status === 403) throw new Error("メール通知履歴を参照する権限がありません（403）。");
      if (response.status === 404) throw new Error("メール通知履歴が見つかりません（404）。");
      if (!response.ok) throw new Error("メール通知履歴を取得できませんでした。");
      setNotification((await response.json()) as MailNotificationDetail);
      setError(null);
    }).catch((cause) => {
      if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : "メール通知履歴を取得できませんでした。");
    });
    return () => controller.abort();
  }, [notificationId]);

  return <main className="min-h-svh bg-muted/30 p-4 md:p-8"><div className="mx-auto max-w-5xl">
    <div className="mb-6 flex items-center justify-between gap-3"><div><h1 className="flex items-center gap-2 text-2xl font-semibold"><MailOpen className="text-primary" />メール通知詳細</h1><p className="mt-1 text-sm text-muted-foreground">送付内容と再試行情報を確認します。</p></div><Button render={<Link href="/admin/mail-notifications" />} variant="outline">一覧へ戻る</Button></div>
    {error && <Card><CardContent className="text-destructive">{error}</CardContent></Card>}
    {!error && !notification && <Card><CardContent>メール通知履歴を読み込んでいます…</CardContent></Card>}
    {notification && <div className="grid gap-4">
      <Card><CardHeader><CardTitle className="flex items-center gap-2">{notification.subject}<Badge variant={notification.status === "FAILED" ? "destructive" : "secondary"}>{notificationStatusLabels[notification.status]}</Badge></CardTitle></CardHeader><CardContent><dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <Entry label="通知種別">{notificationTypeLabels[notification.notificationType]}</Entry><Entry label="宛先氏名">{notification.recipientName}</Entry><Entry label="宛先メールアドレス">{notification.recipientEmail}</Entry><Entry label="申請番号">{notification.applicationNumber}</Entry><Entry label="申請件名">{notification.applicationTitle}</Entry><Entry label="試行回数">{notification.attemptCount}</Entry><Entry label="作成日時">{displayDate(notification.createdAt)}</Entry><Entry label="送付日時">{displayDate(notification.sentAt)}</Entry><Entry label="次回試行日時">{displayDate(notification.nextAttemptAt)}</Entry>
      </dl></CardContent></Card>
      <Card><CardHeader><CardTitle>本文</CardTitle></CardHeader><CardContent><pre className="whitespace-pre-wrap break-words rounded-md bg-muted p-4 font-sans text-sm">{notification.bodyText}</pre></CardContent></Card>
      {(notification.lastErrorCode || notification.lastErrorMessage) && <Card><CardHeader><CardTitle>直近のエラー</CardTitle></CardHeader><CardContent><dl className="grid gap-4 sm:grid-cols-2"><Entry label="エラーコード">{notification.lastErrorCode}</Entry><Entry label="エラーメッセージ">{notification.lastErrorMessage}</Entry></dl></CardContent></Card>}
    </div>}
  </div></main>;
}
