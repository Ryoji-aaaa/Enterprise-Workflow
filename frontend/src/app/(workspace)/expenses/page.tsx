"use client";

import { useEffect, useState } from "react";
import { ReceiptText } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button, LinkButton } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  AuthenticationRequiredError,
  fetchBackend,
} from "@/lib/backend-browser-client";
import {
  categoryLabels,
  statusLabels,
  type ExpensePage,
  type ExpenseStatus,
  yen,
} from "@/lib/expense-application";

export default function ExpensesPage() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<ExpenseStatus | "">("");
  const [data, setData] = useState<ExpensePage | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    fetchBackend(
      `/api/backend/expense-applications?page=${page}&size=20&sort=createdAt,desc${status ? `&status=${status}` : ""}`,
      { cache: "no-store", signal: controller.signal },
    )
      .then(async (response) => {
        if (!response.ok) throw new Error();
        setData((await response.json()) as ExpensePage);
        setError(null);
      })
      .catch((cause) => {
        if (
          !controller.signal.aborted &&
          !(cause instanceof AuthenticationRequiredError)
        )
          setError("経費申請一覧を取得できませんでした。");
      });
    return () => controller.abort();
  }, [page, status]);
  return (
    <main className="p-4 md:p-8">
      <div className="mx-auto max-w-7xl">
        <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
          <div>
            <h1 className="flex items-center gap-2 text-2xl font-semibold">
              <ReceiptText className="text-primary" />
              経費申請
            </h1>
            <p className="text-sm text-muted-foreground">
              自分の申請を確認・作成します。
            </p>
          </div>
          <div className="flex gap-2">
            <LinkButton href="/top" variant="outline">
              トップへ
            </LinkButton>
            <LinkButton href="/expenses/new">新規申請</LinkButton>
          </div>
        </div>
        <div className="mb-4">
          <select
            aria-label="ステータス"
            className="h-10 rounded-md border bg-background px-3"
            onChange={(event) => {
              setStatus(event.target.value as ExpenseStatus | "");
              setPage(0);
            }}
            value={status}
          >
            <option value="">すべての状態</option>
            {Object.entries(statusLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </div>
        {error && (
          <Card>
            <CardContent className="text-destructive">{error}</CardContent>
          </Card>
        )}
        {!error && !data && (
          <Card>
            <CardContent>読み込んでいます…</CardContent>
          </Card>
        )}
        {data && (
          <Card>
            <CardContent>
              <div className="overflow-x-auto">
                <table className="w-full min-w-3xl text-left text-sm">
                  <thead className="border-b text-muted-foreground">
                    <tr>
                      <th className="p-3">申請番号</th>
                      <th className="p-3">件名</th>
                      <th className="p-3">区分</th>
                      <th className="p-3">金額</th>
                      <th className="p-3">状態</th>
                      <th className="p-3">操作</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y">
                    {data.content.map((item) => (
                      <tr key={item.id}>
                        <td className="p-3">{item.applicationNumber}</td>
                        <td className="p-3 font-medium">{item.title}</td>
                        <td className="p-3">{categoryLabels[item.category]}</td>
                        <td className="p-3">{yen(item.totalAmount)}</td>
                        <td className="p-3">
                          <Badge
                            className={
                              item.status === "APPROVED"
                                ? "bg-emerald-600/10 text-emerald-700 dark:text-emerald-400"
                                : undefined
                            }
                            variant={
                              item.status === "RETURNED"
                                ? "destructive"
                                : "secondary"
                            }
                          >
                            {statusLabels[item.status]}
                          </Badge>
                        </td>
                        <td className="p-3">
                          <LinkButton
                            href={`/expenses/${item.id}`}
                            variant="outline"
                          >
                            詳細を表示
                          </LinkButton>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {data.content.length === 0 && (
                <p className="py-8 text-center text-muted-foreground">
                  申請はありません。
                </p>
              )}
              <div className="mt-4 flex justify-end gap-3">
                <Button
                  disabled={page === 0}
                  onClick={() => setPage((value) => value - 1)}
                  variant="outline"
                >
                  前へ
                </Button>
                <span className="self-center text-sm">
                  {page + 1} / {Math.max(data.totalPages, 1)}
                </span>
                <Button
                  disabled={page + 1 >= data.totalPages}
                  onClick={() => setPage((value) => value + 1)}
                  variant="outline"
                >
                  次へ
                </Button>
              </div>
            </CardContent>
          </Card>
        )}
      </div>
    </main>
  );
}
