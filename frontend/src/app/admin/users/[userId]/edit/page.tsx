"use client";

import { FormEvent, useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import type { CurrentUser } from "@/lib/backend-api";

type User = {
  id: string;
  employeeCode: string | null;
  email: string;
  displayName: string;
  employmentType: CurrentUser["employmentType"];
  accountStatus: string;
  validFrom: string;
  validUntil: string | null;
  version: number;
};

type Assignment = {
  id: string;
  organizationUnitId: string;
  positionId: string | null;
  managerUserId: string | null;
  assignmentType: "PRIMARY" | "CONCURRENT" | "TEMPORARY" | "ACTING";
  isPrimary: boolean;
  validFrom: string;
  validUntil: string | null;
  version: number;
};

type RoleAssignment = {
  id: string;
  roleId: string;
  validFrom: string;
  validUntil: string | null;
  version: number;
};

type Unit = { id: string; unitCode: string; unitName: string; enabled: boolean };
type Position = { id: string; code: string; name: string };
type Role = { id: string; code: string; name: string };
type UsersPage = { content: User[] };

const employmentOptions: Array<[CurrentUser["employmentType"], string]> = [
  ["REGULAR_EMPLOYEE", "正社員"],
  ["ASSOCIATE_EMPLOYEE", "準社員"],
  ["PART_TIME", "パート"],
  ["CONTRACT_EMPLOYEE", "嘱託"],
  ["SYSTEM", "SYSTEM"],
];

async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...init,
    cache: "no-store",
    headers: init?.body ? { "Content-Type": "application/json", ...init.headers } : init?.headers,
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { message?: string };
    const error = new Error(body.message ?? "操作に失敗しました。") as Error & { status?: number };
    error.status = response.status;
    throw error;
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

async function loadEditorData(userId: string) {
  // /api/me records lastLoginAt and advances the AppUser optimistic-lock version.
  // Finish it before reading the editable user so self-editing never starts stale.
  const me = await api<CurrentUser>("/api/backend/me");
  const [user, assignments, roleAssignments, units, positions, roles, users] = await Promise.all([
    api<User>(`/api/backend/admin/users/${userId}`),
    api<Assignment[]>(`/api/backend/admin/users/${userId}/organization-assignments`),
    api<RoleAssignment[]>(`/api/backend/admin/users/${userId}/roles`),
    api<Unit[]>("/api/backend/admin/organization-units"),
    api<Position[]>("/api/backend/admin/positions"),
    api<Role[]>("/api/backend/admin/roles"),
    api<UsersPage>("/api/backend/admin/users?size=200&sort=email"),
  ]);
  return { me, user, assignments, roleAssignments, units, positions, roles, users: users.content };
}

export default function EditUserPage() {
  const { userId } = useParams<{ userId: string }>();
  const [me, setMe] = useState<CurrentUser | null>(null);
  const [user, setUser] = useState<User | null>(null);
  const [assignments, setAssignments] = useState<Assignment[]>([]);
  const [roleAssignments, setRoleAssignments] = useState<RoleAssignment[]>([]);
  const [units, setUnits] = useState<Unit[]>([]);
  const [positions, setPositions] = useState<Position[]>([]);
  const [roles, setRoles] = useState<Role[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  function applyData(data: Awaited<ReturnType<typeof loadEditorData>>) {
    setMe(data.me); setUser(data.user); setAssignments(data.assignments);
    setRoleAssignments(data.roleAssignments); setUnits(data.units.filter((value) => value.enabled));
    setPositions(data.positions); setRoles(data.roles); setUsers(data.users);
    setError(null);
  }

  async function reload() {
    try {
      applyData(await loadEditorData(userId));
    } catch (reason) {
      const failure = reason as Error & { status?: number };
      setError(failure.status === 403 ? "この情報を管理する権限がありません（403）。" : failure.message);
    }
  }

  useEffect(() => {
    let active = true;
    void loadEditorData(userId).then((data) => {
      if (!active) return;
      setMe(data.me); setUser(data.user); setAssignments(data.assignments);
      setRoleAssignments(data.roleAssignments);
      setUnits(data.units.filter((value) => value.enabled));
      setPositions(data.positions); setRoles(data.roles); setUsers(data.users);
      setError(null);
    }).catch((reason: unknown) => {
      if (!active) return;
      const failure = reason as Error & { status?: number };
      setError(failure.status === 403 ? "この情報を管理する権限がありません（403）。" : failure.message);
    });
    return () => { active = false; };
  }, [userId]);

  function showFailure(reason: unknown) {
    const failure = reason as Error & { status?: number };
    setMessage(null);
    setError(failure.status === 409
      ? "他のユーザーによって更新されています。最新情報を再読込してください。"
      : failure.message);
  }

  async function updateProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!user) return;
    const form = new FormData(event.currentTarget);
    const validFrom = new Date(String(form.get("validFrom")));
    const validUntil = form.get("validUntil")
      ? new Date(String(form.get("validUntil"))) : null;
    if (validUntil && validUntil <= validFrom) {
      setMessage(null);
      setError("利用終了日時は利用開始日時より後を指定してください。");
      return;
    }
    try {
      const updated = await api<User>(`/api/backend/admin/users/${userId}`, {
        method: "PATCH",
        body: JSON.stringify({
          employeeCode: form.get("employeeCode") || null,
          displayName: form.get("displayName"),
          employmentType: form.get("employmentType"),
          validFrom: validFrom.toISOString(),
          validUntil: validUntil?.toISOString() ?? null,
          version: user.version,
        }),
      });
      setUser(updated); setMessage("基本情報を更新しました。"); setError(null);
    } catch (reason) { showFailure(reason); }
  }

  async function changeStatus(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    try {
      const updated = await api<User>(`/api/backend/admin/users/${userId}/status`, {
        method: "PATCH",
        body: JSON.stringify({ status: form.get("status"), reasonCode: "ADMIN_UI", reasonText: form.get("reason"), effectiveAt: new Date().toISOString() }),
      });
      setUser(updated); setMessage("アカウント状態を更新しました。"); setError(null);
    } catch (reason) { showFailure(reason); }
  }

  async function addAssignment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const primary = form.get("assignmentType") === "PRIMARY";
    try {
      await api(`/api/backend/admin/users/${userId}/organization-assignments`, {
        method: "POST",
        body: JSON.stringify({
          organizationUnitId: form.get("organizationUnitId"), positionId: form.get("positionId") || null,
          assignmentType: form.get("assignmentType"), isPrimary: primary,
          managerUserId: form.get("managerUserId") || null, validFrom: form.get("validFrom"), validUntil: null,
        }),
      });
      setError(null); await reload(); setMessage("所属を追加しました。");
    } catch (reason) { showFailure(reason); }
  }

  async function endAssignment(assignment: Assignment) {
    try {
      await api(`/api/backend/admin/users/${userId}/organization-assignments/${assignment.id}?version=${assignment.version}&reason=${encodeURIComponent("管理画面から所属終了")}`, { method: "DELETE" });
      setError(null); await reload(); setMessage("所属を終了しました。");
    } catch (reason) { showFailure(reason); }
  }

  async function updateAssignment(
    event: FormEvent<HTMLFormElement>,
    assignment: Assignment,
  ) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const assignmentType = String(form.get("assignmentType"));
    try {
      await api(`/api/backend/admin/users/${userId}/organization-assignments/${assignment.id}`, {
        method: "PATCH",
        body: JSON.stringify({
          organizationUnitId: form.get("organizationUnitId"),
          positionId: form.get("positionId") || null,
          assignmentType,
          isPrimary: assignmentType === "PRIMARY",
          managerUserId: form.get("managerUserId") || null,
          validFrom: form.get("validFrom"),
          validUntil: form.get("validUntil") || null,
          version: assignment.version,
          reason: "管理画面から所属情報更新",
        }),
      });
      setError(null); await reload(); setMessage("所属情報を更新しました。");
    } catch (reason) { showFailure(reason); }
  }

  async function addRole(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    try {
      await api(`/api/backend/admin/users/${userId}/roles`, {
        method: "POST",
        body: JSON.stringify({ roleId: form.get("roleId"), organizationUnitId: null, validFrom: new Date().toISOString(), validUntil: null, assignmentReason: "管理画面から付与" }),
      });
      setError(null); await reload(); setMessage("ロールを付与しました。");
    } catch (reason) { showFailure(reason); }
  }

  async function revokeRole(assignmentId: string) {
    try {
      await api(`/api/backend/admin/users/${userId}/roles/${assignmentId}?reason=${encodeURIComponent("管理画面から剥奪")}`, { method: "DELETE" });
      setError(null); await reload(); setMessage("ロールを剥奪しました。");
    } catch (reason) { showFailure(reason); }
  }

  const permissions = me?.permissions ?? [];
  const unitName = (id: string) => units.find((unit) => unit.id === id)?.unitName ?? id;
  const positionName = (id: string | null) => positions.find((position) => position.id === id)?.name ?? "役職なし";
  const roleName = (id: string) => roles.find((role) => role.id === id)?.name ?? id;

  return (
    <main className="min-h-svh bg-muted/30 p-4 md:p-8">
      <div className="mx-auto max-w-5xl space-y-5">
        <div className="flex items-center justify-between"><h1 className="text-2xl font-semibold">ユーザー情報編集</h1><Button render={<Link href="/admin/users" />} variant="outline">一覧へ戻る</Button></div>
        {message && <p className="rounded-lg bg-primary/10 p-3 text-sm text-primary">{message}</p>}
        {error && <p className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">{error}</p>}
        {!user && !error && <Card><CardContent>ユーザー情報を読み込んでいます…</CardContent></Card>}
        {user && (
          <>
            <Card><CardHeader><CardTitle>基本情報・有効期間・雇用区分</CardTitle></CardHeader><CardContent>
              <form className="grid gap-4 md:grid-cols-2" onSubmit={updateProfile}>
                <label className="grid gap-1 text-sm">email（変更不可）<Input name="email" readOnly value={user.email} /></label>
                <label className="grid gap-1 text-sm">社員番号<Input defaultValue={user.employeeCode ?? ""} disabled={!permissions.includes("USER_UPDATE")} maxLength={50} name="employeeCode" /></label>
                <label className="grid gap-1 text-sm">表示名<Input defaultValue={user.displayName} disabled={!permissions.includes("USER_UPDATE")} name="displayName" required /></label>
                <label className="grid gap-1 text-sm">雇用区分<select className="h-9 rounded-md border bg-background px-3" defaultValue={user.employmentType} disabled={!permissions.includes("USER_UPDATE")} name="employmentType">{employmentOptions.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
                <label className="grid gap-1 text-sm">利用開始<Input defaultValue={user.validFrom.slice(0, 16)} disabled={!permissions.includes("USER_UPDATE")} name="validFrom" type="datetime-local" /></label>
                <label className="grid gap-1 text-sm">利用終了<Input defaultValue={user.validUntil?.slice(0, 16) ?? ""} disabled={!permissions.includes("USER_UPDATE")} name="validUntil" type="datetime-local" /></label>
                {permissions.includes("USER_UPDATE") && <Button className="md:col-span-2" type="submit">基本情報を保存</Button>}
              </form>
            </CardContent></Card>

            <Card><CardHeader><CardTitle>アカウント状態 <Badge variant="secondary">{user.accountStatus}</Badge></CardTitle></CardHeader><CardContent>
              {permissions.includes("USER_STATUS_CHANGE") ? <form className="flex flex-wrap gap-3" onSubmit={changeStatus}><select className="h-9 rounded-md border bg-background px-3" defaultValue={user.accountStatus} name="status">{["ACTIVE", "SUSPENDED", "DISABLED", "RETIRED"].map((value) => <option key={value}>{value}</option>)}</select><Input className="max-w-md" name="reason" placeholder="変更理由" required /><Button type="submit">状態を変更</Button></form> : <p className="text-sm text-muted-foreground">状態変更権限がありません。</p>}
            </CardContent></Card>

            <Card><CardHeader><CardTitle>所属・役職・直属上司</CardTitle></CardHeader><CardContent className="space-y-4">
              <div className="space-y-3">{assignments.map((assignment) => permissions.includes("ORGANIZATION_MANAGE") && !assignment.validUntil ? (
                <form className="grid gap-3 rounded-lg border p-3 md:grid-cols-2" key={assignment.id} onSubmit={(event) => void updateAssignment(event, assignment)}>
                  <select className="h-9 rounded-md border bg-background px-3" defaultValue={assignment.organizationUnitId} name="organizationUnitId" required>{units.map((unit) => <option key={unit.id} value={unit.id}>{unit.unitName}</option>)}</select>
                  <select className="h-9 rounded-md border bg-background px-3" defaultValue={assignment.positionId ?? ""} name="positionId"><option value="">役職なし</option>{positions.map((position) => <option key={position.id} value={position.id}>{position.name}</option>)}</select>
                  <select className="h-9 rounded-md border bg-background px-3" defaultValue={assignment.managerUserId ?? ""} name="managerUserId"><option value="">上司なし</option>{users.filter((value) => value.id !== userId).map((value) => <option key={value.id} value={value.id}>{value.displayName}</option>)}</select>
                  <select className="h-9 rounded-md border bg-background px-3" defaultValue={assignment.assignmentType} name="assignmentType"><option value="PRIMARY">主所属</option><option value="CONCURRENT">兼務</option><option value="TEMPORARY">一時所属</option><option value="ACTING">代行</option></select>
                  <Input defaultValue={assignment.validFrom} name="validFrom" required type="date" />
                  <Input defaultValue={assignment.validUntil ?? ""} name="validUntil" type="date" />
                  <div className="flex gap-2 md:col-span-2"><Button type="submit">所属情報を保存</Button><Button onClick={() => void endAssignment(assignment)} type="button" variant="destructive">所属終了</Button></div>
                </form>
              ) : (
                <div className="rounded-lg border p-3" key={assignment.id}><p className="font-medium">{unitName(assignment.organizationUnitId)} / {positionName(assignment.positionId)}</p><p className="text-xs text-muted-foreground">{assignment.assignmentType}・上司: {users.find((value) => value.id === assignment.managerUserId)?.displayName ?? "なし"}・{assignment.validFrom}〜{assignment.validUntil ?? "継続中"}</p></div>
              ))}</div>
              {permissions.includes("ORGANIZATION_MANAGE") && <form className="grid gap-3 md:grid-cols-2" onSubmit={addAssignment}><select className="h-9 rounded-md border bg-background px-3" name="organizationUnitId" required><option value="">組織を選択</option>{units.map((unit) => <option key={unit.id} value={unit.id}>{unit.unitName}</option>)}</select><select className="h-9 rounded-md border bg-background px-3" name="positionId"><option value="">役職なし</option>{positions.map((position) => <option key={position.id} value={position.id}>{position.name}</option>)}</select><select className="h-9 rounded-md border bg-background px-3" name="managerUserId"><option value="">上司なし</option>{users.filter((value) => value.id !== userId).map((value) => <option key={value.id} value={value.id}>{value.displayName}</option>)}</select><select className="h-9 rounded-md border bg-background px-3" name="assignmentType"><option value="PRIMARY">主所属</option><option value="CONCURRENT">兼務</option><option value="TEMPORARY">一時所属</option><option value="ACTING">代行</option></select><Input defaultValue={new Date().toISOString().slice(0, 10)} name="validFrom" type="date" /><Button type="submit">所属を追加</Button></form>}
            </CardContent></Card>

            {permissions.includes("ROLE_READ") && <Card><CardHeader><CardTitle>ロール</CardTitle></CardHeader><CardContent className="space-y-4"><div className="flex flex-wrap gap-2">{roleAssignments.map((assignment) => <div className="flex items-center gap-2 rounded-lg border p-2" key={assignment.id}><Badge variant="secondary">{roleName(assignment.roleId)}</Badge>{permissions.includes("ROLE_REVOKE") && !assignment.validUntil && <Button onClick={() => void revokeRole(assignment.id)} size="sm" variant="destructive">剥奪</Button>}</div>)}</div>{permissions.includes("ROLE_ASSIGN") && <form className="flex gap-3" onSubmit={addRole}><select className="h-9 flex-1 rounded-md border bg-background px-3" name="roleId" required><option value="">ロールを選択</option>{roles.map((role) => <option key={role.id} value={role.id}>{role.name}</option>)}</select><Button type="submit">ロール付与</Button></form>}</CardContent></Card>}
          </>
        )}
      </div>
    </main>
  );
}
