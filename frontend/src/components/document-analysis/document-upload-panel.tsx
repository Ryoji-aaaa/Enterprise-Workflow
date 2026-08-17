"use client";

import { ChangeEvent, DragEvent, KeyboardEvent, RefObject } from "react";
import { FileUp, RotateCcw } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  DOCUMENT_ANALYSIS_ACCEPT,
  type AnalyzableFile,
} from "@/lib/document-analysis";
import type { DocumentAnalysisJob } from "@/lib/document-analysis-api";
import { cn } from "@/lib/utils";

export function DocumentUploadPanel({
  inputRef,
  inputId,
  selectedFile,
  selectionError,
  recentAnalyses,
  recentLoading,
  onSelectFiles,
  onSelectRecentAnalysis,
  onClear,
}: {
  inputRef: RefObject<HTMLInputElement | null>;
  inputId: string;
  selectedFile: AnalyzableFile | null;
  selectionError: string | null;
  recentAnalyses: DocumentAnalysisJob[];
  recentLoading: boolean;
  onSelectFiles: (files: FileList) => void;
  onSelectRecentAnalysis: (job: DocumentAnalysisJob) => void;
  onClear: () => void;
}) {
  function browse() {
    inputRef.current?.click();
  }

  function change(event: ChangeEvent<HTMLInputElement>) {
    if (event.target.files) {
      onSelectFiles(event.target.files);
    }
    event.target.value = "";
  }

  function drop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault();
    onSelectFiles(event.dataTransfer.files);
  }

  function keyboard(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      browse();
    }
  }

  return (
    <aside className="flex h-full min-h-0 min-w-0 flex-col gap-5 overflow-y-auto p-4" data-testid="document-analysis-file-pane">
      <section className="space-y-3">
        <div>
          <h2 className="text-sm font-medium">File</h2>
          <p className="mt-1 text-xs text-muted-foreground">PDF、JPEG、PNGを1件選択できます。</p>
        </div>
        <Input
          accept={DOCUMENT_ANALYSIS_ACCEPT}
          aria-describedby={`${inputId}-help`}
          className="sr-only"
          id={inputId}
          onChange={change}
          ref={inputRef}
          type="file"
        />
        <div
          aria-label="分析するファイルを選択"
          className={cn(
            "grid min-h-40 cursor-pointer place-items-center rounded-md border border-dashed bg-muted/20 p-4 text-center outline-none transition-colors",
            "hover:bg-muted/40 focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/30",
          )}
          onClick={browse}
          onDragOver={(event) => event.preventDefault()}
          onDrop={drop}
          onKeyDown={keyboard}
          role="button"
          tabIndex={0}
        >
          <div className="space-y-2">
            <FileUp className="mx-auto size-8 text-primary" />
            <label
              className="block cursor-pointer text-sm font-medium"
              htmlFor={inputId}
              onClick={(event) => event.preventDefault()}
            >
              ファイルを選択またはドロップ
            </label>
            <p className="text-xs text-muted-foreground" id={`${inputId}-help`}>
              最大10 MiB
            </p>
          </div>
        </div>
        {selectionError && (
          <p className="rounded-md border border-destructive/30 bg-destructive/10 p-3 text-sm text-destructive" role="alert">
            {selectionError}
          </p>
        )}
        {selectedFile && (
          <div className="rounded-md border bg-background p-3 text-sm">
            <p className="break-all font-medium">{selectedFile.name}</p>
            <p className="mt-1 text-xs text-muted-foreground">
              {selectedFile.type} / {(selectedFile.size / 1024 / 1024).toFixed(2)} MiB
            </p>
            <Button className="mt-3" onClick={onClear} size="sm" type="button" variant="outline">
              <RotateCcw data-icon="inline-start" />
              選択を解除
            </Button>
          </div>
        )}
      </section>

      <section className="space-y-2">
        <h2 className="text-sm font-medium">Recent analyses</h2>
        {recentLoading ? (
          <p className="rounded-md border bg-background p-3 text-xs text-muted-foreground">
            分析履歴を読み込んでいます…
          </p>
        ) : recentAnalyses.length === 0 ? (
          <p className="rounded-md border bg-background p-3 text-xs text-muted-foreground">
            分析履歴はありません。
          </p>
        ) : (
          <ul className="space-y-2">
            {recentAnalyses.map((job) => (
              <li key={job.id}>
                <button
                  className="w-full rounded-md border bg-background p-3 text-left text-xs transition-colors hover:bg-muted/50 focus-visible:border-ring focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/30"
                  onClick={() => onSelectRecentAnalysis(job)}
                  type="button"
                >
                  <span className="block break-all font-medium">{job.originalFileName}</span>
                  <span className="mt-1 block text-muted-foreground">{job.status}</span>
                  <span className="mt-1 block text-muted-foreground">
                    {new Date(job.createdAt).toLocaleString("ja-JP")}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </aside>
  );
}
