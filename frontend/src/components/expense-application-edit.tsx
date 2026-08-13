"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

import { ExpenseApplicationForm } from "@/components/expense-application-form";
import { Card, CardContent } from "@/components/ui/card";
import { AuthenticationRequiredError } from "@/lib/backend-browser-client";
import { probeExpenseEditRoute } from "@/lib/expense-edit-route";

export function ExpenseApplicationEdit({ applicationId }: { applicationId: string }) {
  const router = useRouter();
  const [generic, setGeneric] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void probeExpenseEditRoute(applicationId, controller.signal).then((route) => {
      if (controller.signal.aborted) return;
      if (route === "AUTO_ENTRY") {
        router.replace(`/expenses/auto-entry/confirm/${encodeURIComponent(applicationId)}`);
        return;
      }
      setGeneric(true);
    }).catch((cause) => {
      if (!controller.signal.aborted && !(cause instanceof AuthenticationRequiredError)) {
        setError(cause instanceof Error ? cause.message : "申請内容を読み込めませんでした。");
      }
    });
    return () => controller.abort();
  }, [applicationId, router]);

  if (error) return <Card><CardContent className="text-destructive">{error}</CardContent></Card>;
  if (!generic) return <Card><CardContent>申請の編集方法を確認しています…</CardContent></Card>;
  return <ExpenseApplicationForm applicationId={applicationId} />;
}
