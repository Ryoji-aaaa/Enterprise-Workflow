"use client";

import { FileSearch } from "lucide-react";
import { useMemo } from "react";

import type { AutoEntryPageRef } from "@/lib/auto-entry-review";
import type { AnalyzableFile } from "@/lib/document-analysis";
import {
  autoEntryPageForNumber,
  getAutoEntryEvidenceSources,
} from "@/lib/expense-auto-entry-evidence";
import type { ResolvedAutoEntryField } from "@/lib/expense-auto-entry";

import { AutoEntryImagePreview } from "./auto-entry-image-preview";
import { AutoEntryPdfPreview } from "./auto-entry-pdf-preview";

export function ExpenseAutoEntryDocumentPreview({
  file,
  objectUrl,
  serverUrl,
  pages,
  resolvedFields,
  activeFieldPath,
}: {
  file: AnalyzableFile | null;
  objectUrl: string | null;
  serverUrl: string | null;
  pages: readonly AutoEntryPageRef[];
  resolvedFields: readonly ResolvedAutoEntryField[];
  activeFieldPath: string | null;
}) {
  const previewUrl = objectUrl ?? serverUrl;
  const evidence = useMemo(
    () => getAutoEntryEvidenceSources(resolvedFields),
    [resolvedFields],
  );

  return (
    <section className="flex h-full min-h-0 min-w-0 flex-col">
      <div className="border-b px-4 py-3">
        <h2 className="text-sm font-medium">Preview</h2>
        <p className="mt-1 truncate text-xs text-muted-foreground">
          {file ? file.name : "ファイル未選択"}
        </p>
      </div>
      <div
        aria-label={file ? `${file.name}のプレビュー` : "ファイルプレビュー"}
        className="grid min-h-0 min-w-0 flex-1 place-items-center overflow-auto bg-muted/20 p-4"
        data-testid="expense-auto-entry-preview-content"
        role="region"
      >
        {!file || !previewUrl ? (
          <div className="text-center text-muted-foreground">
            <FileSearch className="mx-auto mb-3 size-10" />
            <p className="text-sm">ファイルを選択するとプレビューを表示します。</p>
          </div>
        ) : file.type === "application/pdf" ? (
          <AutoEntryPdfPreview activeFieldPath={activeFieldPath} evidence={evidence} pages={pages} previewUrl={previewUrl} />
        ) : (
          <AutoEntryImagePreview
            activeFieldPath={activeFieldPath}
            evidence={evidence}
            name={file.name}
            page={autoEntryPageForNumber(pages, 1)}
            previewUrl={previewUrl}
          />
        )}
      </div>
    </section>
  );
}
