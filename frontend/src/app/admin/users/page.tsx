"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Users } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { CurrentUser } from "@/lib/backend-api";
import { AuthenticationRequiredError, fetchBackend } from "@/lib/backend-browser-client";

type AdminUser = {
  id: string;
  employeeCode: string | null;
  email: string;
  displayName: string;
  employmentType: CurrentUser["employmentType"];
  accountStatus: string;
  validFrom: string;
  validUntil: string | null;
  version: number;
  currentOrganizationAssignment: {
    organizationUnitName: string;
    positionName: string | null;
  } | null;
};

type UserPage = {
  content: AdminUser[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

const employmentLabels: Record<CurrentUser["employmentType"], string> = {
  SYSTEM: "SYSTEM",
  REGULAR_EMPLOYEE: "正社員",
  ASSOCIATE_EMPLOYEE: "準社員",
  PART_TIME: "パート",
  CONTRACT_EMPLOYEE: "嘱託",
};

export default function AdminUsersPage() {
  const [page, setPage] = useState(0);
  const [me, setMe] = useState<CurrentUser | null>(null);
  const [users, setUsers] = useState<UserPage | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      fetchBackend("/api/backend/me", { cache: "no-store", signal: controller.signal }),
      fetchBackend(`/api/backend/admin/users?page=${page}&size=25&sort=email`, {
        cache: "no-store",
        signal: controller.signal,
      }),
    ]).then(async ([meResponse, usersResponse]) => {
      if (meResponse.ok && usersResponse.ok) {
        setMe((await meResponse.json()) as CurrentUser);
        setUsers((await usersResponse.json()) as UserPage);
        setError(null);
      } else if (usersResponse.status === 403) {
        setError("ユーザー情報を参照する権限がありません（403）。");
      } else {
        setError("ユーザー一覧を取得できませんでした。");
      }
    }).catch((cause) => {
      if (!controller.signal.aborted && !(cause instanceof AuthenticationRequiredError)) setError("ユーザー一覧を取得できませんでした。");
    });
    return () => controller.abort();
  }, [page]);

  const canUpdate = me?.permissions.includes("USER_UPDATE") ?? false;

  return (
    <main className="min-h-svh bg-muted/30 p-4 md:p-8">
      <div className="mx-auto max-w-7xl">
        <div className="mb-6 flex items-center justify-between">
          <div>
            <h1 className="flex items-center gap-2 text-2xl font-semibold"><Users className="size-6 text-primary" />ユーザー管理</h1>
            <p className="mt-1 text-sm text-muted-foreground">ユーザーの状態、所属、役職、ロールを管理します。</p>
          </div>
          <Button render={<Link href="/top" />} variant="outline">トップへ戻る</Button>
        </div>
        {error && <Card><CardContent className="text-destructive">{error}</CardContent></Card>}
        {!error && !users && <Card><CardContent>ユーザーを読み込んでいます…</CardContent></Card>}
        {users && (
          <Card>
            <CardHeader><CardTitle>ユーザー一覧（{users.totalElements}件）</CardTitle></CardHeader>
            <CardContent>
              <div className="overflow-x-auto">
                <table className="w-full min-w-4xl text-left text-sm">
                  <thead className="border-b text-muted-foreground"><tr><th className="p-3">表示名</th><th className="p-3">email</th><th className="p-3">雇用区分</th><th className="p-3">状態</th><th className="p-3">主所属・役職</th><th className="p-3">操作</th></tr></thead>
                  <tbody className="divide-y">
                    {users.content.map((user) => (
                      <tr key={user.id}>
                        <td className="p-3 font-medium">{user.displayName}</td>
                        <td className="p-3">{user.email}</td>
                        <td className="p-3">{employmentLabels[user.employmentType]}</td>
                        <td className="p-3"><Badge variant="secondary">{user.accountStatus}</Badge></td>
                        <td className="p-3 text-muted-foreground">
                          {user.currentOrganizationAssignment
                            ? `${user.currentOrganizationAssignment.organizationUnitName} / ${user.currentOrganizationAssignment.positionName ?? "役職なし"}`
                            : "未所属"}
                        </td>
                        <td className="p-3">
                          {canUpdate ? <Button render={<Link href={`/admin/users/${user.id}/edit`} />} variant="outline">編集</Button> : <span className="text-muted-foreground">参照のみ</span>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {users.content.length === 0 && <p className="py-8 text-center text-muted-foreground">ユーザーがいません。</p>}
              <div className="mt-4 flex items-center justify-end gap-3">
                <Button disabled={users.page === 0} onClick={() => setPage((value) => value - 1)} variant="outline">前へ</Button>
                <span className="text-sm">{users.page + 1} / {Math.max(users.totalPages, 1)}</span>
                <Button disabled={users.page + 1 >= users.totalPages} onClick={() => setPage((value) => value + 1)} variant="outline">次へ</Button>
              </div>
            </CardContent>
          </Card>
        )}
      </div>
    </main>
  );
}
