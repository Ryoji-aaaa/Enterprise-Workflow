"use client";

import Image from "next/image";
import { useEffect, useRef, useState } from "react";
import { Download, ExternalLink, Paperclip, Trash2, Upload } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import {
  EXPENSE_ATTACHMENT_ACCEPT,
  expenseAttachmentErrorMessage,
  expenseAttachmentFileSize,
  expenseAttachmentTypeLabel,
  MAX_EXPENSE_ATTACHMENTS,
  type ExpenseAttachment,
  validateExpenseAttachmentFile,
} from "@/lib/expense-attachment";

type ErrorBody = { code?: string; message?: string };

export function ExpenseAttachmentSection({
  applicationId,
  editable,
}: {
  applicationId: string;
  editable: boolean;
}) {
  const [attachments, setAttachments] = useState<ExpenseAttachment[]>([]);
  const [loading, setLoading] = useState(true);
  const [processing, setProcessing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const controller = new AbortController();
    fetch(`/api/backend/expense-applications/${applicationId}/attachments`, {
      cache: "no-store",
      signal: controller.signal,
    }).then(async (response) => {
      const body = (await response.json()) as ExpenseAttachment[] & ErrorBody;
      if (!response.ok) {
        throw new Error(body.message ?? "添付ファイルを取得できませんでした。");
      }
      setAttachments(body);
      setError(null);
    }).catch((cause) => {
      if (!controller.signal.aborted) {
        setError(cause instanceof Error ? cause.message : "添付ファイルを取得できませんでした。");
      }
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, [applicationId]);

  async function upload(file: File) {
    const validation = validateExpenseAttachmentFile(file, attachments);
    if (validation) {
      setError(validation);
      return;
    }
    setProcessing(true);
    setError(null);
    const formData = new FormData();
    formData.append("file", file);
    try {
      const response = await fetch(
        `/api/backend/expense-applications/${applicationId}/attachments`,
        { method: "POST", body: formData },
      );
      const body = (await response.json()) as ExpenseAttachment & ErrorBody;
      if (!response.ok) {
        throw new Error(expenseAttachmentErrorMessage(
          body.code, body.message ?? "ファイルを添付できませんでした。",
        ));
      }
      setAttachments((current) => [...current, body]);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "ファイルを添付できませんでした。");
    } finally {
      if (fileInput.current) fileInput.current.value = "";
      setProcessing(false);
    }
  }

  async function remove(attachment: ExpenseAttachment) {
    if (!window.confirm(`「${attachment.originalFileName}」を削除しますか？`)) return;
    setProcessing(true);
    setError(null);
    try {
      const response = await fetch(
        `/api/backend/expense-applications/${applicationId}/attachments/${attachment.id}`,
        { method: "DELETE" },
      );
      if (!response.ok) {
        const body = (await response.json()) as ErrorBody;
        throw new Error(expenseAttachmentErrorMessage(
          body.code, body.message ?? "添付ファイルを削除できませんでした。",
        ));
      }
      setAttachments((current) => current.filter(({ id }) => id !== attachment.id));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "添付ファイルを削除できませんでした。");
    } finally {
      setProcessing(false);
    }
  }

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between gap-3">
        <CardTitle className="flex items-center gap-2"><Paperclip />領収書・証憑</CardTitle>
        {editable ? (
          <span className="text-xs text-muted-foreground">
            {attachments.length} / {MAX_EXPENSE_ATTACHMENTS}件
          </span>
        ) : null}
      </CardHeader>
      <CardContent className="space-y-4">
        {error ? <p className="text-sm text-destructive" role="alert">{error}</p> : null}
        {editable ? (
          <div className="rounded-lg border border-dashed p-4">
            <label className="grid gap-2 text-sm" htmlFor={`attachment-${applicationId}`}>
              <span>ファイルを追加（PDF、JPEG、PNG／1ファイル10 MiB以下）</span>
              <Input
                accept={EXPENSE_ATTACHMENT_ACCEPT}
                disabled={processing || attachments.length >= MAX_EXPENSE_ATTACHMENTS}
                id={`attachment-${applicationId}`}
                onChange={(event) => {
                  const selected = event.target.files?.[0];
                  if (selected) void upload(selected);
                }}
                ref={fileInput}
                type="file"
              />
            </label>
            {processing ? <p className="mt-2 text-xs text-muted-foreground"><Upload className="mr-1 inline" />処理しています…</p> : null}
          </div>
        ) : null}

        {loading ? <p className="text-muted-foreground">添付ファイルを読み込んでいます…</p> : null}
        {!loading && attachments.length === 0 ? (
          <p className="text-muted-foreground">領収書・証憑は添付されていません。</p>
        ) : null}
        {attachments.length > 0 ? (
          <ul className="grid gap-3 sm:grid-cols-2">
            {attachments.map((attachment) => {
              const contentPath = `/api/backend/expense-applications/${applicationId}/attachments/${attachment.id}/content`;
              const image = attachment.contentType.startsWith("image/");
              return (
                <li className="rounded-lg border p-3" key={attachment.id}>
                  {image ? (
                    <Image
                      alt={`${attachment.originalFileName}のプレビュー`}
                      className="mb-3 h-36 w-full rounded-md border object-contain"
                      height={144}
                      src={contentPath}
                      unoptimized
                      width={240}
                    />
                  ) : null}
                  <p className="break-all font-medium">{attachment.originalFileName}</p>
                  <p className="mt-1 text-xs text-muted-foreground">
                    {expenseAttachmentTypeLabel(attachment.contentType)} ・ {expenseAttachmentFileSize(attachment.fileSize)}
                  </p>
                  <p className="text-xs text-muted-foreground">
                    {attachment.uploadedByName} ・ {new Date(attachment.uploadedAt).toLocaleString("ja-JP")}
                  </p>
                  <div className="mt-3 flex flex-wrap gap-2">
                    {attachment.previewable ? (
                      <Button
                        render={<a href={contentPath} rel="noopener noreferrer" target="_blank" />}
                        variant="outline"
                      >
                        <ExternalLink />プレビュー
                      </Button>
                    ) : null}
                    <Button
                      render={<a href={`${contentPath}?download=true`} />}
                      variant="outline"
                    >
                      <Download />ダウンロード
                    </Button>
                    {editable && attachment.deletable ? (
                      <Button
                        disabled={processing}
                        onClick={() => void remove(attachment)}
                        variant="destructive"
                      >
                        <Trash2 />削除
                      </Button>
                    ) : null}
                  </div>
                </li>
              );
            })}
          </ul>
        ) : null}
      </CardContent>
    </Card>
  );
}
