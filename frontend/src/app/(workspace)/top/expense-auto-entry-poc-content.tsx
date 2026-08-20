import {
  ArrowRight,
  CheckCircle2,
  Download,
  Eye,
  FileCheck2,
  FileSearch,
  PencilLine,
  ScanLine,
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { AnchorButton, LinkButton } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

const operationSteps = [
  {
    title: "文書を読み込む",
    description: "PDF・JPEG・PNGの請求書または注文書を1件選択します。",
  },
  {
    title: "AIで文書を分析",
    description:
      "Azure AI Content UnderstandingのAUTO_ENTRY分析が自動で始まります。",
  },
  {
    title: "抽出結果を確認",
    description: "原文プレビューとAIが抽出した値を並べて確認します。",
  },
  {
    title: "必要な値を修正・確認",
    description:
      "要確認項目を中心に、原文と照合しながら利用者がレビューします。",
  },
  {
    title: "「決定」で下書きを作成",
    description: "経費申請下書きを作成し、最終確認・編集画面へ進みます。",
  },
] as const;

const sampleInvoices = [
  {
    href: "/poc/expense-auto-entry/invoice-sample-01.png",
    download: "請求書サンプル_01.png",
  },
  {
    href: "/poc/expense-auto-entry/invoice-sample-02.jpg",
    download: "請求書サンプル_02.jpg",
  },
] as const;

const pocPoints = [
  {
    title: "構造化データ抽出",
    description: "請求書・注文書から経費入力に必要な値を抽出します。",
    icon: ScanLine,
  },
  {
    title: "要確認項目のレビュー",
    description: "OK・REVIEW・MISSINGを手掛かりに確認対象を絞り込みます。",
    icon: CheckCircle2,
  },
  {
    title: "原文との照合",
    description: "原文プレビューと抽出値を同じ画面で比較します。",
    icon: Eye,
  },
  {
    title: "抽出元位置の表示",
    description: "入力欄に対応する原文上の位置をOverlayで確認できます。",
    icon: FileSearch,
  },
  {
    title: "人による修正",
    description: "AIの抽出値を利用者が確認し、必要に応じて修正します。",
    icon: PencilLine,
  },
  {
    title: "経費下書きへの引き継ぎ",
    description: "レビュー済みの内容から経費申請下書きを作成します。",
    icon: FileCheck2,
  },
] as const;

const technologyGroups = [
  {
    title: "Frontend",
    technologies: [
      "Next.js",
      "TypeScript",
      "App Router",
      "Tailwind CSS",
      "shadcn/ui",
    ],
  },
  {
    title: "Backend",
    technologies: ["Java 21", "Spring Boot", "PostgreSQL"],
  },
  {
    title: "Authentication",
    technologies: ["Better Auth", "Keycloak", "OpenID Connect"],
  },
  {
    title: "Document AI / Storage",
    technologies: ["Azure AI Content Understanding", "Azure Blob Storage"],
  },
  {
    title: "Development / Infrastructure",
    technologies: ["Docker Compose", "Azure Container Apps"],
  },
] as const;

export function ExpenseAutoEntryPocContent() {
  return (
    <main className="p-4 md:p-6 lg:p-8">
      <div className="mx-auto max-w-7xl">
        <section
          aria-labelledby="expense-auto-entry-poc-title"
          className=""
        >
          <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <div className="flex items-center gap-2">
                <span className="h-6 w-1 rounded-full bg-primary" />
                <h1 className="text-xl font-semibold tracking-tight">
                  AI+OCRを利用した請求書・注文書からの経費申請自動入力 PoC
                </h1>
              </div>
              <p className="mt-1 pl-3 text-sm text-muted-foreground">
              Azure AI Content Understandingの活用
            </p>
            </div>
          </div>
          <p className="mt-4 text-sm leading-7 text-muted-foreground md:text-base">
            請求書・注文書などを読み込み、AIで解析した内容を利用者が確認・修正して、
            経費申請下書きへ引き継ぐための検証画面です。
          </p>
          <div className="mt-6 mb-6">
            <LinkButton href="/expenses/auto-entry" size="lg">
              自動入力を試す
              <ArrowRight data-icon="inline-end" />
            </LinkButton>
          </div>
        </section>

        <section aria-labelledby="sample-invoices-title">
          <Card className="shadow-sm mb-6">
            <CardHeader>
              <CardTitle>
                <h2 className="text-base" id="sample-invoices-title">
                  動作確認用データ（請求書サンプル）
                </h2>
              </CardTitle>
              <CardDescription>
                PoCをすぐに試せるよう、分析用のサンプル請求書を用意しています。
                ダウンロード後、「自動入力を試す」から読み込んでください。
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-wrap gap-2">
              {sampleInvoices.map((sample) => (
                <AnchorButton
                  download={sample.download}
                  href={sample.href}
                  key={sample.href}
                  size="lg"
                  variant="outline"
                >
                  <Download data-icon="inline-start" />
                  {sample.download}
                </AnchorButton>
              ))}
            </CardContent>
          </Card>
        </section>

        <section aria-labelledby="operation-guide-title">
          <div className="mb-4">
            <h2 className="text-xl font-semibold" id="operation-guide-title">
              操作方法
            </h2>
            <p className="mt-1 text-sm text-muted-foreground">
              文書の選択から経費申請下書きの最終確認まで、次の流れで操作します。
            </p>
          </div>
          <ol className="grid gap-3 md:grid-cols-2 xl:grid-cols-5">
            {operationSteps.map((step, index) => (
              <li
                className="rounded-lg border bg-card p-4 shadow-sm"
                key={step.title}
              >
                <span className="grid size-7 place-items-center rounded-full bg-primary text-xs font-semibold text-primary-foreground">
                  {index + 1}
                </span>
                <h3 className="mt-3 text-sm font-semibold">{step.title}</h3>
                <p className="mt-2 text-xs leading-5 text-muted-foreground">
                  {step.description}
                </p>
              </li>
            ))}
          </ol>
        </section>

        <section aria-labelledby="poc-points-title">
          <div className="mb-4">
            <h2 className="text-xl font-semibold" id="poc-points-title">
              PoCのポイント
            </h2>
            <p className="mt-1 text-sm text-muted-foreground">
              AIによる抽出と人による確認を組み合わせ、下書き作成を支援します。
            </p>
          </div>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {pocPoints.map((point) => {
              const Icon = point.icon;
              return (
                <Card className="shadow-sm" key={point.title}>
                  <CardHeader>
                    <div className="mb-2 grid size-9 place-items-center rounded-lg bg-primary/10 text-primary">
                      <Icon className="size-4.5" />
                    </div>
                    <CardTitle>
                      <h3>{point.title}</h3>
                    </CardTitle>
                    <CardDescription>{point.description}</CardDescription>
                  </CardHeader>
                </Card>
              );
            })}
          </div>
        </section>

        <section aria-labelledby="technology-title">
          <div className="mb-4">
            <h2 className="text-xl font-semibold" id="technology-title">
              利用技術
            </h2>
            <p className="mt-1 text-sm text-muted-foreground">
              ブラウザからの業務通信はNext.js BFFを経由し、Spring
              Bootがデータと外部サービスを扱います。
            </p>
          </div>
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-5">
            {technologyGroups.map((group) => (
              <Card className="shadow-sm" key={group.title}>
                <CardHeader>
                  <CardTitle>
                    <h3>{group.title}</h3>
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <ul className="space-y-1.5 text-xs text-muted-foreground">
                    {group.technologies.map((technology) => (
                      <li key={technology}>{technology}</li>
                    ))}
                  </ul>
                </CardContent>
              </Card>
            ))}
          </div>
          <Card className="mt-3 shadow-sm">
            <CardHeader>
              <CardTitle>
                <h3>アーキテクチャ境界</h3>
              </CardTitle>
              <CardDescription>
                ブラウザはBackend、データベース、ストレージ、Document
                AIへ直接接続しません。
              </CardDescription>
            </CardHeader>
            <CardContent>
              <pre
                className="overflow-x-auto rounded-md bg-muted p-4 text-xs leading-6"
                aria-label="システム構成"
              >
                {`Browser
  ↓
Next.js BFF
  ↓
Spring Boot
  ├─ PostgreSQL
  ├─ Azure Blob Storage
  └─ Azure AI Content Understanding`}
              </pre>
            </CardContent>
          </Card>
        </section>
      </div>
    </main>
  );
}
