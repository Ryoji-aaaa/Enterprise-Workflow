"use client";

import type { DocumentAnalysisRawResult } from "@/lib/document-analysis-api";

export function RawResult({ value }: { value: DocumentAnalysisRawResult }) {
  return (
    <div className="flex min-h-0 flex-1 flex-col gap-2">
      {!value.formatted && (
        <p className="rounded-md border bg-background p-3 text-xs text-muted-foreground">
          Raw Resultが大きいため整形せず表示しています。
        </p>
      )}
      <pre className="min-h-0 flex-1 overflow-auto whitespace-pre-wrap rounded-md border bg-muted/30 p-3 text-xs leading-6">
        {value.text}
      </pre>
    </div>
  );
}
