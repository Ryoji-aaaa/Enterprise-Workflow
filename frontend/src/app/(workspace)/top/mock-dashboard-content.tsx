import {
  Boxes,
  FileChartColumn,
  FileText,
  Grid2X2,
  MoreHorizontal,
  Plus,
  SlidersHorizontal,
  Users,
} from "lucide-react";
import type { ReactNode } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardAction,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { cn } from "@/lib/utils";

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
  children: ReactNode;
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

export function MockDashboardContent() {
  return (
    <main className="p-4 md:p-6 lg:p-8">
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
  );
}
