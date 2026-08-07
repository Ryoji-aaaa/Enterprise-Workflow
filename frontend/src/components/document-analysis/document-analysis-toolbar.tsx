"use client";

import { Play } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { DocumentAnalysisProviderConfig, DocumentAnalysisStatus } from "@/lib/document-analysis";
import { isDocumentAnalysisProcessing } from "@/lib/document-analysis";

export function DocumentAnalysisToolbar({
  config,
  status,
  hasValidFile,
  onRun,
}: {
  config: DocumentAnalysisProviderConfig;
  status: DocumentAnalysisStatus;
  hasValidFile: boolean;
  onRun: () => void;
}) {
  const processing = isDocumentAnalysisProcessing(status);
  const disabled = !hasValidFile || processing;

  return (
    <div className="flex min-w-0 flex-wrap items-center justify-between gap-3 border-b bg-background px-4 py-3">
      <div className="min-w-0">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <h1 className="truncate text-xl font-semibold">{config.title}</h1>
          <Badge variant="secondary">Layout</Badge>
        </div>
        <p className="mt-1 text-sm text-muted-foreground">{config.description}</p>
      </div>
      <Button
        aria-disabled={disabled}
        disabled={disabled}
        onClick={onRun}
        type="button"
      >
        <Play data-icon="inline-start" />
        Run Analysis
      </Button>
    </div>
  );
}

