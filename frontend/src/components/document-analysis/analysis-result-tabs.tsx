"use client";

import { useId, useState } from "react";
import { Braces, FileCode2, Pilcrow, Table2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import type { DocumentAnalysisResult } from "@/lib/document-analysis";
import { cn } from "@/lib/utils";

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

export function AnalysisResultTabs({ result }: { result: DocumentAnalysisResult | null }) {
  const [activeTab, setActiveTab] = useState<ResultTab>("markdown");
  const baseId = useId();

  return (
    <section className="flex min-h-0 min-w-0 flex-col">
      <div className="border-b px-4 py-3">
        <h2 className="text-sm font-medium">Result</h2>
        <p className="mt-1 text-xs text-muted-foreground">
          {result ? "Frontend fixture result" : "Run Analysis後に表示します。"}
        </p>
      </div>
      <div className="flex min-h-0 flex-1 flex-col p-4">
        <div aria-label="分析結果タブ" className="mb-3 grid grid-cols-2 gap-2" role="tablist">
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
        <div
          aria-labelledby={`${baseId}-${activeTab}-tab`}
          className="flex min-h-0 flex-1 flex-col"
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
          {result && activeTab === "result" && <RawResult value={result.rawResult} />}
        </div>
      </div>
    </section>
  );
}

