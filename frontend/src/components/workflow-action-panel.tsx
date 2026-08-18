"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { AuthenticationRequiredError, fetchBackend } from "@/lib/backend-browser-client";
import { expenseErrorMessage } from "@/lib/expense-application";

export function WorkflowActionPanel({ stepId, onCompleted }: { stepId: string; onCompleted: () => void }) {
  const [comment, setComment] = useState("");
  const [processing, setProcessing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function act(action: "approve" | "return") {
    setProcessing(true); setError(null);
    try {
      const response = await fetchBackend(`/api/backend/workflow/tasks/${stepId}/${action}`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ comment }),
      });
      const body = await response.json() as { code?: string; message?: string };
      if (!response.ok) throw new Error(expenseErrorMessage(body.code, body.message ?? "処理できませんでした。"));
      onCompleted();
    } catch (cause) {
      if (!(cause instanceof AuthenticationRequiredError)) setError(cause instanceof Error ? cause.message : "処理できませんでした。");
    } finally { setProcessing(false); }
  }
  return <Card><CardHeader><CardTitle>承認操作</CardTitle></CardHeader><CardContent className="space-y-3">
    {error && <p className="text-sm text-destructive">{error}</p>}
    <label className="grid gap-1 text-sm">コメント（差戻し時は必須）
      <textarea className="min-h-24 rounded-md border bg-background p-3" onChange={(event) => setComment(event.target.value)} value={comment} />
    </label><div className="flex justify-end gap-3">
      <Button disabled={processing || !comment.trim()} onClick={() => void act("return")} variant="outline">差戻し</Button>
      <Button disabled={processing} onClick={() => void act("approve")}>承認</Button>
    </div></CardContent></Card>;
}
