import {
  BarChart3,
  Bell,
  Boxes,
  ChevronDown,
  CircleHelp,
  ClipboardList,
  FileChartColumn,
  FileText,
  Grid2X2,
  LayoutDashboard,
  MoreHorizontal,
  Plus,
  Search,
  Settings,
  Shapes,
  SlidersHorizontal,
  Sparkles,
  Users,
} from "lucide-react";
import Link from "next/link";

import { LogoutForm } from "@/components/logout-form";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardAction,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Separator } from "@/components/ui/separator";
import { cn } from "@/lib/utils";
import { canViewOrganizationChart, type CurrentUser } from "@/lib/backend-api";

const menuItems = [
  { label: "モック文字１", icon: LayoutDashboard, active: true },
  { label: "モック文字２", icon: BarChart3 },
  { label: "モック文字３", icon: FileChartColumn },
  { label: "モック文字４", icon: ClipboardList },
  { label: "モック文字５", icon: Users },
  { label: "モック文字６", icon: Shapes },
];

const chartLegend = [
  { label: "サンプル文字列１", color: "bg-cyan-500" },
  { label: "サンプル文字列２", color: "bg-blue-400" },
  { label: "サンプル文字列３", color: "bg-amber-400" },
  { label: "サンプル文字列４", color: "bg-emerald-500" },
];

function MockCard({
  children,
  className,
  title,
}: {
  children: React.ReactNode;
  className?: string;
  title: string;
}) {
  return (
    <Card
      className={cn(
        "min-w-0 gap-0 py-0 shadow-sm",
        className,
      )}
    >
      <CardHeader className="h-12 grid-cols-[1fr_auto] items-center gap-2 border-b py-0">
        <CardTitle className="truncate">
          <h2>{title}</h2>
        </CardTitle>
        <CardAction>
          <Button
            aria-label="サンプル操作１"
            size="icon-sm"
            type="button"
            variant="ghost"
          >
            <MoreHorizontal />
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="p-0">{children}</CardContent>
    </Card>
  );
}

function BarMock() {
  return (
    <div className="p-4">
      <div className="mb-4 flex items-center justify-between text-xs text-muted-foreground">
        <span>サンプル文字列５</span>
        <span>12 / 20</span>
      </div>
      <svg
        aria-label="サンプルグラフ１"
        className="h-52 w-full"
        role="img"
        viewBox="0 0 440 220"
      >
        {[35, 80, 125, 170].map((y) => (
          <line
            key={y}
            stroke="currentColor"
            strokeOpacity="0.12"
            x1="28"
            x2="428"
            y1={y}
            y2={y}
          />
        ))}
        <line
          stroke="currentColor"
          strokeOpacity="0.3"
          x1="28"
          x2="428"
          y1="190"
          y2="190"
        />
        {[
          { height: 64, x: 62 },
          { height: 112, x: 136 },
          { height: 148, x: 210 },
          { height: 132, x: 284 },
          { height: 82, x: 358 },
        ].map((bar, index) => (
          <g key={bar.x}>
            <rect
              className="fill-primary/80"
              height={bar.height}
              rx="4"
              width="42"
              x={bar.x}
              y={190 - bar.height}
            />
            <text
              className="fill-muted-foreground text-[10px]"
              textAnchor="middle"
              x={bar.x + 21}
              y="208"
            >
              {index + 1}
            </text>
          </g>
        ))}
      </svg>
    </div>
  );
}

function DonutMock() {
  return (
    <div className="grid gap-5 p-4 sm:grid-cols-[10rem_1fr] sm:items-center">
      <div className="relative mx-auto size-36 rounded-full bg-[conic-gradient(var(--color-primary)_0_26%,var(--color-chart-2)_26%_48%,var(--color-chart-3)_48%_70%,var(--color-chart-4)_70%_86%,var(--color-chart-5)_86%_100%)]">
        <div className="absolute inset-8 grid place-items-center rounded-full bg-card text-center">
          <span className="text-2xl font-bold">42</span>
          <span className="-mt-2 text-[10px] text-muted-foreground">
            モック文字７
          </span>
        </div>
      </div>
      <div className="grid gap-2">
        {chartLegend.map((item) => (
          <div
            className="flex items-center gap-2 text-xs text-muted-foreground"
            key={item.label}
          >
            <span className={cn("size-2 rounded-full", item.color)} />
            <span>{item.label}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function TrendMock() {
  return (
    <div className="p-4">
      <div className="mb-2 flex items-end justify-between">
        <div>
          <p className="text-xs text-muted-foreground">サンプル文字列６</p>
          <p className="mt-1 text-2xl font-semibold">1,234</p>
        </div>
        <Badge className="bg-cyan-50 text-cyan-700" variant="secondary">
          +12.4%
        </Badge>
      </div>
      <svg
        aria-label="サンプルグラフ２"
        className="h-32 w-full"
        role="img"
        viewBox="0 0 440 140"
      >
        <defs>
          <linearGradient id="mock-area" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stopColor="var(--color-primary)" stopOpacity="0.3" />
            <stop offset="100%" stopColor="var(--color-primary)" stopOpacity="0" />
          </linearGradient>
        </defs>
        <path
          d="M8 108 C45 96, 70 102, 104 76 S165 93, 202 60 S262 70, 302 38 S360 54, 432 18 L432 132 L8 132 Z"
          fill="url(#mock-area)"
        />
        <path
          d="M8 108 C45 96, 70 102, 104 76 S165 93, 202 60 S262 70, 302 38 S360 54, 432 18"
          fill="none"
          stroke="var(--color-primary)"
          strokeLinecap="round"
          strokeWidth="3"
        />
      </svg>
    </div>
  );
}

function ListMock() {
  return (
    <div className="divide-y">
      {[1, 2, 3, 4].map((item) => (
        <div className="flex items-center gap-3 px-4 py-3" key={item}>
          <div className="grid size-9 shrink-0 place-items-center rounded-lg bg-muted text-muted-foreground">
            <FileText className="size-4" />
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium">モック文字{item + 7}</p>
            <p className="truncate text-xs text-muted-foreground">
              サンプル文字列{item + 6}
            </p>
          </div>
          <span className="text-xs tabular-nums text-muted-foreground">
            0{item}:2{item}
          </span>
        </div>
      ))}
    </div>
  );
}

export function MockDashboard({
  user,
}: {
  user: CurrentUser;
}) {
  const { displayName, permissions } = user;
  const initials = displayName.trim().slice(0, 2) || "仮";

  return (
    <div className="min-h-svh bg-muted/30 text-foreground">
      <header className="sticky top-0 z-30 flex h-16 items-center border-b bg-background/95 px-4 backdrop-blur md:px-6">
        <div className="flex min-w-0 items-center gap-3 md:w-60">
          <div className="grid size-9 shrink-0 place-items-center rounded-xl bg-primary text-primary-foreground shadow-sm">
            <Sparkles className="size-5" />
          </div>
          <span className="hidden truncate font-heading text-base font-semibold sm:block">
            モック文字１
          </span>
        </div>

        <nav aria-label="モバイルナビゲーション" className="ml-2 flex items-center gap-1 md:hidden">
          {canViewOrganizationChart(user) && (
            <Button
              aria-label="組織図"
              render={<Link href="/organization-chart" />}
              size="icon-lg"
              variant="ghost"
            >
              <Shapes />
            </Button>
          )}
          {permissions.includes("USER_READ") && (
            <Button
              aria-label="ユーザー管理"
              render={<Link href="/admin/users" />}
              size="icon-lg"
              variant="ghost"
            >
              <Users />
            </Button>
          )}
        </nav>

        <label className="relative mx-4 hidden max-w-xl flex-1 items-center lg:flex">
          <Search className="pointer-events-none absolute left-3 z-10 size-4 text-muted-foreground" />
          <Input
            className="h-9 bg-muted/30 pl-9"
            placeholder="サンプル文字列１"
            type="search"
          />
        </label>

        <div className="ml-auto flex items-center gap-1 sm:gap-2">
          <Button
            aria-label="サンプル操作２"
            className="text-muted-foreground"
            size="icon-lg"
            type="button"
            variant="ghost"
          >
            <Bell />
          </Button>
          <Button
            aria-label="サンプル操作３"
            className="hidden text-muted-foreground sm:inline-flex"
            size="icon-lg"
            type="button"
            variant="ghost"
          >
            <CircleHelp />
          </Button>
          <Separator
            className="mx-1 hidden h-7 sm:block"
            orientation="vertical"
          />
          <div className="flex min-w-0 items-center gap-2 rounded-lg py-1 pl-1 pr-2">
            <Avatar>
              <AvatarFallback className="bg-primary/10 text-xs font-semibold text-primary">
                {initials}
              </AvatarFallback>
            </Avatar>
            <div className="hidden min-w-0 text-left sm:block">
              <p className="max-w-36 truncate text-sm font-medium">
                {displayName}
              </p>
              <p className="text-[11px] text-muted-foreground">
                サンプル文字列２
              </p>
            </div>
            <ChevronDown className="hidden size-4 text-muted-foreground sm:block" />
          </div>
          <LogoutForm compact />
        </div>
      </header>

      <div className="grid min-h-[calc(100svh-4rem)] md:grid-cols-[15rem_minmax(0,1fr)]">
        <aside className="hidden border-r bg-sidebar md:flex md:flex-col">
          <nav className="flex-1 space-y-1 p-3">
            {canViewOrganizationChart(user) && (
              <Button
                className="h-auto w-full justify-start gap-3 px-3 py-2.5 text-left text-sm text-sidebar-foreground/70"
                render={<Link href="/organization-chart" />}
                variant="ghost"
              >
                <Shapes className="size-4.5" />
                <span>組織図</span>
              </Button>
            )}
            {permissions.includes("USER_READ") && (
              <Button
                className="h-auto w-full justify-start gap-3 px-3 py-2.5 text-left text-sm text-sidebar-foreground/70"
                render={<Link href="/admin/users" />}
                variant="ghost"
              >
                <Users className="size-4.5" />
                <span>ユーザー管理</span>
              </Button>
            )}
            {menuItems.map((item) => {
              const Icon = item.icon;
              return (
                <Button
                  className={cn(
                    "h-auto w-full justify-start gap-3 px-3 py-2.5 text-left text-sm",
                    item.active
                      ? "bg-sidebar-accent font-medium text-sidebar-accent-foreground"
                      : "text-sidebar-foreground/70",
                  )}
                  key={item.label}
                  type="button"
                  variant="ghost"
                >
                  <Icon className="size-4.5" />
                  <span>{item.label}</span>
                </Button>
              );
            })}
          </nav>
          <div className="border-t p-3">
            <Button
              className="h-auto w-full justify-start gap-3 px-3 py-2.5 text-sm text-sidebar-foreground/70"
              type="button"
              variant="ghost"
            >
              <Settings className="size-4.5" />
              <span>モック文字７</span>
            </Button>
          </div>
        </aside>

        <main className="min-w-0 p-4 md:p-6 lg:p-8">
          <div className="mx-auto max-w-7xl">
            <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
              <div>
                <div className="flex items-center gap-2">
                  <span className="h-6 w-1 rounded-full bg-primary" />
                  <h1 className="text-xl font-semibold tracking-tight">
                    モック文字８
                  </h1>
                </div>
                <p className="mt-1 pl-3 text-sm text-muted-foreground">
                  サンプル文字列３
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                <Button size="lg" type="button" variant="outline">
                  <SlidersHorizontal data-icon="inline-start" />
                  モック操作１
                </Button>
                <Button size="lg" type="button">
                  <Plus data-icon="inline-start" />
                  モック操作２
                </Button>
              </div>
            </div>

            <div className="mb-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
              {[
                { label: "サンプル文字列４", value: "128", icon: Grid2X2 },
                { label: "サンプル文字列５", value: "42", icon: Boxes },
                { label: "サンプル文字列６", value: "76%", icon: FileChartColumn },
                { label: "サンプル文字列７", value: "2,024", icon: Users },
              ].map((item) => {
                const Icon = item.icon;
                return (
                  <Card className="gap-0 py-0 shadow-sm" key={item.label}>
                    <CardContent className="flex items-center gap-3 p-4">
                      <div className="grid size-10 place-items-center rounded-lg bg-primary/10 text-primary">
                        <Icon className="size-5" />
                      </div>
                      <div>
                        <p className="text-xs text-muted-foreground">
                          {item.label}
                        </p>
                        <p className="mt-0.5 text-xl font-semibold tabular-nums">
                          {item.value}
                        </p>
                      </div>
                    </CardContent>
                  </Card>
                );
              })}
            </div>

            <div className="grid gap-4 lg:grid-cols-2">
              <MockCard title="モック文字９">
                <BarMock />
              </MockCard>
              <MockCard title="モック文字１０">
                <DonutMock />
              </MockCard>
              <MockCard title="モック文字１１">
                <TrendMock />
              </MockCard>
              <MockCard title="モック文字１２">
                <ListMock />
              </MockCard>
            </div>
          </div>
        </main>
      </div>
    </div>
  );
}
