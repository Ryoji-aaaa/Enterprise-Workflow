"use client";

import { CheckCircle2, Circle, CircleDashed, LoaderCircle, XCircle } from "lucide-react";

import { cn } from "@/lib/utils";
import {
  isDocumentAnalysisProcessing,
  type DocumentAnalysisState,
  type DocumentAnalysisStatus,
} from "@/lib/document-analysis";

const statusLabels: Record<DocumentAnalysisStatus, string> = {
  idle: "Idle",
  selected: "File selected",
  uploading: "Upload prepared",
  queued: "Queued",
  running: "Running",
  succeeded: "Succeeded",
  failed: "Failed",
};

const statusFlow: DocumentAnalysisStatus[] = [
  "selected",
  "uploading",
  "queued",
  "running",
  "succeeded",
];

function CurrentStatusIndicator({ status }: { status: DocumentAnalysisStatus }) {
  const sharedProps = {
    "aria-hidden": true,
    "data-status": status,
    "data-testid": "document-analysis-status-indicator",
  } as const;

  if (isDocumentAnalysisProcessing(status)) {
    return <LoaderCircle {...sharedProps} className="size-4 animate-spin text-primary" />;
  }
  if (status === "succeeded") {
    return <CheckCircle2 {...sharedProps} className="size-4 text-primary" />;
  }
  if (status === "failed") {
    return <XCircle {...sharedProps} className="size-4 text-destructive" />;
  }
  return null;
}

export function AnalysisStatus({
  state,
  viewLoading = false,
}: {
  state: DocumentAnalysisState;
  viewLoading?: boolean;
}) {
  return (
    <section aria-label="分析状態" className="space-y-3">
      <div>
        <div className="flex items-center gap-2">
          <h2 className="text-sm font-medium">Status</h2>
          <CurrentStatusIndicator status={state.status} />
        </div>
        <p aria-label="現在の分析状態" className="text-xs text-muted-foreground">
          {statusLabels[state.status]}
        </p>
        {viewLoading ? (
          <p className="mt-1 text-xs text-muted-foreground">分析結果を読み込んでいます…</p>
        ) : null}
      </div>
      {state.status === "failed" && (
        <p className="flex items-start gap-2 text-sm text-destructive" role="alert">
          <XCircle className="mt-0.5 size-4 shrink-0" />
          {state.error}
        </p>
      )}
      <ol className="space-y-2">
        {statusFlow.map((status) => {
          const completed = state.completedStatuses.includes(status);
          const current = state.status === status;
          const Icon = completed ? CheckCircle2 : current ? CircleDashed : Circle;
          return (
            <li
              className={cn(
                "flex items-center gap-2 text-xs",
                completed || current ? "text-foreground" : "text-muted-foreground",
              )}
              key={status}
            >
              <Icon className={cn("size-3.5", completed && "text-primary")} />
              <span>{statusLabels[status]}</span>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
