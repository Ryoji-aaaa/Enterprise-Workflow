"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { Building2, ChevronDown, Crown, Landmark, Network, Pencil, UserRound } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { CurrentUser } from "@/lib/backend-api";
import {
  buildOrganizationChartIndex,
  canEditOrganizationChartUsers,
  organizationChartUserEditPath,
} from "@/lib/organization-chart-tree";
import { cn } from "@/lib/utils";

type Member = {
  userId: string;
  displayName: string;
  email: string;
  positionCode: string | null;
  positionName: string | null;
  isHead: boolean;
  isPrimary: boolean;
};

type Unit = {
  id: string;
  parentUnitId: string | null;
  code: string;
  name: string;
  type:
    | "COMPANY"
    | "DIVISION"
    | "DEPARTMENT"
    | "SECTION"
    | "TEAM"
    | "PROJECT"
    | "OTHER";
  displayOrder: number;
  members: Member[];
};

type Chart = {
  organization: { id: string; code: string; name: string };
  president: Member | null;
  units: Unit[];
};

type State =
  | { kind: "loading" }
  | { kind: "ready"; chart: Chart }
  | { kind: "forbidden" }
  | { kind: "error"; message: string };

const typeLabels: Record<Unit["type"], string> = {
  COMPANY: "会社",
  DIVISION: "本部・事業部",
  DEPARTMENT: "部・室",
  SECTION: "課",
  TEAM: "チーム",
  PROJECT: "プロジェクト",
  OTHER: "統治組織",
};

function MemberSummary({
  member,
  canEditUsers,
  showUserIcon = false,
}: {
  member: Member;
  canEditUsers: boolean;
  showUserIcon?: boolean;
}) {
  return (
    <div className="flex flex-wrap items-center gap-2 rounded-md border bg-background px-3 py-2">
      <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2">
        {showUserIcon && <UserRound className="size-3.5 shrink-0" />}
        <p className="text-sm font-medium">{member.displayName}</p>
        {member.positionName && <p className="text-xs text-muted-foreground">{member.positionName}</p>}
      </div>
      {canEditUsers && (
        <Button
          aria-label={`${member.displayName}のユーザー情報を編集`}
          render={<Link href={organizationChartUserEditPath(member.userId)} />}
          size="sm"
          variant="outline"
        >
          <Pencil data-icon="inline-start" />
          編集
        </Button>
      )}
    </div>
  );
}

function OrganizationNode({
  unit,
  lookup,
  depth,
  canEditUsers,
}: {
  unit: Unit;
  lookup: Map<string | null, Unit[]>;
  depth: number;
  canEditUsers: boolean;
}) {
  const [open, setOpen] = useState(true);
  const childUnits = lookup.get(unit.id) ?? [];
  const head = unit.members.find((member) => member.isHead);
  const members = unit.members.filter((member) => !member.isHead);

  return (
    <li className={cn("relative list-none py-2", depth >= 4 && "min-w-[42rem]")}>
      <span aria-hidden className="absolute -left-5 top-9 w-5 border-t border-border md:-left-8 md:w-8" />
      <Card className={cn(
        "w-full max-w-[44rem] shadow-sm",
        unit.type === "PROJECT" && "border-primary/60 bg-primary/5",
      )}>
        <CardHeader className="gap-2 p-4">
          <div className="flex flex-wrap items-start justify-between gap-2">
            <div className="flex min-w-0 items-center gap-2">
              <Building2 className="size-4 shrink-0 text-muted-foreground" />
              <CardTitle className="truncate text-base">{unit.name}</CardTitle>
            </div>
            <div className="flex items-center gap-2">
              <span className="text-xs text-muted-foreground">{unit.members.length}名</span>
              <Badge variant={unit.type === "PROJECT" ? "default" : "secondary"}>
                {typeLabels[unit.type]}
              </Badge>
              {childUnits.length > 0 && (
                <Button
                  aria-expanded={open}
                  aria-label={`${unit.name}の配下組織を${open ? "閉じる" : "開く"}`}
                  className="size-7"
                  onClick={() => setOpen((value) => !value)}
                  size="icon"
                  type="button"
                  variant="ghost"
                >
                  <ChevronDown className={cn("transition-transform", open && "rotate-180")} />
                </Button>
              )}
            </div>
          </div>
        </CardHeader>
        <CardContent className="space-y-2 px-4 pb-4">
          {head ? (
            <MemberSummary canEditUsers={canEditUsers} member={head} />
          ) : (
            <p className="text-xs text-muted-foreground">責任者未登録</p>
          )}
          {members.length > 0 && (
            <details>
              <summary className="cursor-pointer text-sm text-primary">
                一般ユーザーを表示（{members.length}名）
              </summary>
              <ul className="mt-2 space-y-1 text-sm">
                {members.map((member) => (
                  <li key={member.userId}>
                    <MemberSummary canEditUsers={canEditUsers} member={member} showUserIcon />
                  </li>
                ))}
              </ul>
            </details>
          )}
        </CardContent>
      </Card>
      {open && childUnits.length > 0 && (
        <ul className="relative ml-3 border-l border-border pl-5 md:ml-6 md:pl-8">
          {childUnits.map((child) => (
            <OrganizationTree
              canEditUsers={canEditUsers}
              depth={depth + 1}
              key={child.id}
              lookup={lookup}
              unit={child}
            />
          ))}
        </ul>
      )}
    </li>
  );
}

function OrganizationTree({
  unit,
  lookup,
  depth,
  canEditUsers,
}: {
  unit: Unit;
  lookup: Map<string | null, Unit[]>;
  depth: number;
  canEditUsers: boolean;
}) {
  return <OrganizationNode canEditUsers={canEditUsers} depth={depth} lookup={lookup} unit={unit} />;
}

function GovernancePanel({ units }: { units: Unit[] }) {
  if (units.length === 0) {
    return null;
  }
  return (
    <Card className="mb-6 border-dashed bg-background/80">
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center gap-2 text-base">
          <Landmark className="size-4 text-muted-foreground" />
          <h2>統治機関・会議体</h2>
        </CardTitle>
        <p className="text-xs text-muted-foreground">社長を頂点とする業務執行組織とは別枠で表示しています。</p>
      </CardHeader>
      <CardContent>
        <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {units.map((unit) => (
            <li className="rounded-lg border bg-muted/30 px-4 py-3" key={unit.id}>
              <div className="flex items-center justify-between gap-3">
                <p className="font-medium">{unit.name}</p>
                <Badge variant="outline">統治組織</Badge>
              </div>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  );
}

export default function OrganizationChartPage() {
  const [state, setState] = useState<State>({ kind: "loading" });
  const [permissions, setPermissions] = useState<string[]>([]);

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      fetch("/api/backend/organization-chart", { cache: "no-store", signal: controller.signal }),
      fetch("/api/backend/me", { cache: "no-store", signal: controller.signal }),
    ]).then(async ([chartResponse, meResponse]) => {
      if (meResponse.ok) {
        const me = (await meResponse.json()) as CurrentUser;
        setPermissions(me.permissions);
      }
      if (chartResponse.ok) {
        setState({ kind: "ready", chart: (await chartResponse.json()) as Chart });
      } else if (chartResponse.status === 403) {
        setState({ kind: "forbidden" });
      } else {
        setState({ kind: "error", message: "組織図を取得できませんでした。" });
      }
    })
      .catch(() => {
        if (!controller.signal.aborted) {
          setState({ kind: "error", message: "組織図を取得できませんでした。" });
        }
      });
    return () => controller.abort();
  }, []);

  const chartIndex = useMemo(() => {
    return buildOrganizationChartIndex(state.kind === "ready" ? state.chart.units : []);
  }, [state]);
  const canEditUsers = canEditOrganizationChartUsers(permissions);
  return (
    <main className="min-h-svh bg-muted/30 p-4 md:p-8">
      <div className="mx-auto max-w-7xl">
        <div className="mb-6 flex items-center justify-between gap-4">
          <div>
            <h1 className="flex items-center gap-2 text-2xl font-semibold">
              <Network className="size-6 text-primary" />組織図
            </h1>
            <p className="mt-1 text-sm text-muted-foreground">有効な組織・所属情報を表示しています。</p>
          </div>
          <Button render={<Link href="/top" />} variant="outline">トップへ戻る</Button>
        </div>

        {state.kind === "loading" && <Card><CardContent>組織図を読み込んでいます…</CardContent></Card>}
        {state.kind === "forbidden" && (
          <Card><CardContent className="text-destructive">このアカウントでは組織図を閲覧できません（403）。</CardContent></Card>
        )}
        {state.kind === "error" && <Card><CardContent>{state.message}</CardContent></Card>}
        {state.kind === "ready" && state.chart.units.length === 0 && (
          <Card><CardContent>表示できる組織データがありません。</CardContent></Card>
        )}
        {state.kind === "ready" && state.chart.units.length > 0 && (
          <>
            <GovernancePanel units={chartIndex.governanceUnits} />
            <section aria-labelledby="business-organization-title">
              <div className="mb-3">
                <h2 className="font-semibold" id="business-organization-title">業務執行組織</h2>
                <p className="text-xs text-muted-foreground">社長を最上層として、配下組織を縦方向に表示します。</p>
              </div>
              <div className="overflow-x-auto rounded-xl border bg-background p-4 pb-8 md:p-6">
                <div className="min-w-0">
                  <Card className="w-full max-w-[44rem] border-primary/60 bg-primary/5 shadow-sm">
                    <CardHeader className="gap-2 p-4">
                      <div className="flex items-center justify-between gap-3">
                        <CardTitle className="flex items-center gap-2 text-lg">
                          <Crown className="size-5 text-primary" />社長
                        </CardTitle>
                        <Badge>{state.chart.organization.name}</Badge>
                      </div>
                    </CardHeader>
                    <CardContent className="px-4 pb-4">
                      {state.chart.president ? (
                        <MemberSummary canEditUsers={canEditUsers} member={state.chart.president} />
                      ) : <p className="text-sm text-muted-foreground">社長未登録</p>}
                    </CardContent>
                  </Card>
                  {chartIndex.operationalUnits.length > 0 ? (
                    <ul className="relative ml-3 border-l border-border pl-5 md:ml-6 md:pl-8">
                      {chartIndex.operationalUnits.map((unit) => (
                        <OrganizationTree
                          canEditUsers={canEditUsers}
                          depth={1}
                          key={unit.id}
                          lookup={chartIndex.childrenByParent}
                          unit={unit}
                        />
                      ))}
                    </ul>
                  ) : (
                    <p className="mt-4 text-sm text-muted-foreground">社長配下の組織は登録されていません。</p>
                  )}
                </div>
              </div>
              <p className="mt-2 text-xs text-muted-foreground">5階層目以降は横スクロールして確認できます。</p>
            </section>
          </>
        )}
      </div>
    </main>
  );
}
