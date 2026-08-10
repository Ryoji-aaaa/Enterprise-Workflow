"use client";

import { useEffect, useId, useState } from "react";
import { Braces, FileCode2, Pilcrow, Table2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import type { DocumentAnalysisResult } from "@/lib/document-analysis";
import type { DocumentAnalysisJob, DocumentAnalysisRawResult } from "@/lib/document-analysis-api";
import { paragraphsToCsv, tablesToCsv } from "@/lib/document-analysis-copy";
import { cn } from "@/lib/utils";

import { CopyToClipboardButton } from "./copy-to-clipboard-button";
import { MarkdownResult } from "./markdown-result";
import { ParagraphsResult } from "./paragraphs-result";
import { RawResult } from "./raw-result";
import { TablesResult } from "./tables-result";

type ResultTab = "markdown" | "paragraphs" | "tables" | "result";

const tabs: Array<{ id: ResultTab; label: string; icon: typeof FileCode2 }> = [
  { id: "markdown", label: "Markdown", icon: FileCode2 },
  { id: "paragraphs", label: "Paragraphs", icon: Pilcrow },
  { id: "tables", label: "Tables", icon: Table2 },
  { id: "result", label: "Result", icon: Braces },
];

type RawState =
  | { status: "idle"; analysisId: string | null; value: null; error: null }
  | { status: "loading"; analysisId: string; value: null; error: null }
  | { status: "success"; analysisId: string; value: DocumentAnalysisRawResult; error: null }
  | { status: "error"; analysisId: string; value: null; error: string };

export function AnalysisResultTabs({
  job,
  result,
  rawState,
  onLoadRawResult,
}: {
  job: DocumentAnalysisJob | null;
  result: DocumentAnalysisResult | null;
  rawState: RawState;
  onLoadRawResult: () => void;
}) {
  const [activeTab, setActiveTab] = useState<ResultTab>("markdown");
  const baseId = useId();
  const copyAction = activeTab === "markdown"
    ? { label: "Markdownをコピー", text: result?.markdown ?? "", disabled: !result }
    : activeTab === "paragraphs"
      ? { label: "Paragraphsをコピー", text: result ? paragraphsToCsv(result.paragraphs) : "", disabled: !result }
      : activeTab === "tables"
        ? { label: "Tablesをコピー", text: result ? tablesToCsv(result.tables) : "", disabled: !result }
        : {
          label: "Resultをコピー",
          text: rawState.status === "success" ? rawState.value.text : "",
          disabled: rawState.status !== "success",
        };

  useEffect(() => {
    if (activeTab === "result" && result && rawState.status === "idle") {
      onLoadRawResult();
    }
  }, [activeTab, onLoadRawResult, rawState.status, result]);

  return (
    <section className="flex h-full min-h-0 min-w-0 flex-col">
      <div className="border-b px-4 py-3">
        <h2 className="text-sm font-medium">Result</h2>
        <p className="mt-1 text-xs text-muted-foreground">
          {result && job
            ? `${job.modelId} / ${job.providerApiVersion}`
            : "Run Analysis後に表示します。"}
        </p>
      </div>
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden p-4">
        <div aria-label="分析結果タブ" className="mb-3 grid shrink-0 grid-cols-2 gap-2" role="tablist">
          {tabs.map((tab) => {
            const Icon = tab.icon;
            const selected = activeTab === tab.id;
            return (
              <Button
                aria-controls={`${baseId}-${tab.id}-panel`}
                aria-selected={selected}
                className={cn("justify-start", selected && "bg-muted text-foreground")}
                id={`${baseId}-${tab.id}-tab`}
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                role="tab"
                type="button"
                variant="ghost"
              >
                <Icon data-icon="inline-start" />
                {tab.label}
              </Button>
            );
          })}
        </div>
        <div className="mb-3 flex shrink-0 justify-end">
          <CopyToClipboardButton {...copyAction} />
        </div>
        <div
          aria-labelledby={`${baseId}-${activeTab}-tab`}
          className="flex min-h-0 flex-1 flex-col overflow-hidden"
          id={`${baseId}-${activeTab}-panel`}
          role="tabpanel"
          tabIndex={0}
        >
          {!result && (
            <p className="rounded-md border bg-background p-4 text-sm text-muted-foreground">
              分析結果はまだありません。
            </p>
          )}
          {result && activeTab === "markdown" && <MarkdownResult markdown={result.markdown} />}
          {result && activeTab === "paragraphs" && <ParagraphsResult paragraphs={result.paragraphs} />}
          {result && activeTab === "tables" && <TablesResult tables={result.tables} />}
          {result && activeTab === "result" && rawState.status === "loading" && (
            <p className="rounded-md border bg-background p-4 text-sm text-muted-foreground">
              Raw Resultを読み込んでいます…
            </p>
          )}
          {result && activeTab === "result" && rawState.status === "error" && (
            <p className="rounded-md border border-destructive/30 bg-destructive/10 p-4 text-sm text-destructive" role="alert">
              {rawState.error}
            </p>
          )}
          {result && activeTab === "result" && rawState.status === "success" && (
            <RawResult value={rawState.value} />
          )}
        </div>
      </div>
    </section>
  );
}
