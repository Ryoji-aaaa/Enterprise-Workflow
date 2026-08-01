"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { ChevronDown, Network, UserRound } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

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

function OrganizationNode({
  unit,
  childUnits,
  lookup,
}: {
  unit: Unit;
  childUnits: Unit[];
  lookup: Map<string | null, Unit[]>;
}) {
  const [open, setOpen] = useState(unit.parentUnitId === null);
  const head = unit.members.find((member) => member.isHead);
  const members = unit.members.filter((member) => !member.isHead);

  return (
    <li className="min-w-64 list-none">
      <Card className={unit.type === "PROJECT" ? "border-primary/60 bg-primary/5" : ""}>
        <CardHeader className="gap-2">
          <div className="flex items-start justify-between gap-2">
            <CardTitle>{unit.name}</CardTitle>
            <Badge variant={unit.type === "PROJECT" ? "default" : "secondary"}>
              {typeLabels[unit.type]}
            </Badge>
          </div>
          <p className="text-xs text-muted-foreground">所属 {unit.members.length}名</p>
        </CardHeader>
        <CardContent className="space-y-3">
          {head ? (
            <div className="rounded-lg border bg-background p-3">
              <p className="font-medium">{head.displayName}</p>
              <p className="text-xs text-muted-foreground">{head.positionName}</p>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">責任者未登録</p>
          )}
          {members.length > 0 && (
            <details>
              <summary className="cursor-pointer text-sm text-primary">
                一般ユーザーを表示（{members.length}名）
              </summary>
              <ul className="mt-2 space-y-1 text-sm">
                {members.map((member) => (
                  <li className="flex items-center gap-2" key={member.userId}>
                    <UserRound className="size-3.5" />
                    {member.displayName}
                  </li>
                ))}
              </ul>
            </details>
          )}
          {childUnits.length > 0 && (
            <Button
              aria-expanded={open}
              className="w-full"
              onClick={() => setOpen((value) => !value)}
              type="button"
              variant="outline"
            >
              <ChevronDown className={open ? "rotate-180 transition-transform" : "transition-transform"} />
              配下組織 {childUnits.length}件
            </Button>
          )}
        </CardContent>
      </Card>
      {open && childUnits.length > 0 && (
        <ul className="mt-5 grid items-start gap-5 border-l-2 border-muted pl-5 lg:grid-flow-col lg:auto-cols-[18rem]">
          {childUnits.map((child) => (
            <OrganizationTree key={child.id} lookup={lookup} unit={child} />
          ))}
        </ul>
      )}
    </li>
  );
}

function OrganizationTree({
  unit,
  lookup,
}: {
  unit: Unit;
  lookup: Map<string | null, Unit[]>;
}) {
  return <OrganizationNode childUnits={lookup.get(unit.id) ?? []} lookup={lookup} unit={unit} />;
}

export default function OrganizationChartPage() {
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    const controller = new AbortController();
    fetch("/api/backend/organization-chart", { cache: "no-store", signal: controller.signal })
      .then(async (response) => {
        if (response.ok) {
          setState({ kind: "ready", chart: (await response.json()) as Chart });
        } else if (response.status === 403) {
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

  const lookup = useMemo(() => {
    const value = new Map<string | null, Unit[]>();
    if (state.kind === "ready") {
      for (const unit of state.chart.units) {
        const siblings = value.get(unit.parentUnitId) ?? [];
        siblings.push(unit);
        value.set(unit.parentUnitId, siblings);
      }
      for (const siblings of value.values()) {
        siblings.sort((left, right) => left.displayOrder - right.displayOrder);
      }
    }
    return value;
  }, [state]);
  return (
    <main className="min-h-svh bg-muted/30 p-4 md:p-8">
      <div className="mx-auto max-w-[110rem]">
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
          <div className="overflow-x-auto pb-6">
            <div className="min-w-max">
              <Card className="mb-6 max-w-sm border-primary/60">
                <CardHeader><CardTitle>{state.chart.organization.name}</CardTitle></CardHeader>
                <CardContent>
                  {state.chart.president ? (
                    <div><p className="font-medium">{state.chart.president.displayName}</p><p className="text-sm text-muted-foreground">{state.chart.president.positionName}</p></div>
                  ) : <p className="text-sm text-muted-foreground">社長未登録</p>}
                </CardContent>
              </Card>
              <ul className="grid items-start gap-5 md:grid-flow-col md:auto-cols-[18rem]">
                {(lookup.get(null) ?? []).map((unit) => <OrganizationTree key={unit.id} lookup={lookup} unit={unit} />)}
              </ul>
            </div>
          </div>
        )}
      </div>
    </main>
  );
}
