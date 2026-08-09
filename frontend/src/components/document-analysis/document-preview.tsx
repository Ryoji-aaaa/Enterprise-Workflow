"use client";

import { FileSearch } from "lucide-react";

import type { AnalyzableFile } from "@/lib/document-analysis";

export function DocumentPreview({
  file,
  objectUrl,
  serverUrl,
}: {
  file: AnalyzableFile | null;
  objectUrl: string | null;
  serverUrl: string | null;
}) {
  const previewUrl = objectUrl ?? serverUrl;

  return (
    <section className="flex min-h-0 min-w-0 flex-col">
      <div className="border-b px-4 py-3">
        <h2 className="text-sm font-medium">Preview</h2>
        <p className="mt-1 truncate text-xs text-muted-foreground">
          {file ? file.name : "ファイル未選択"}
        </p>
      </div>
      <div
        aria-label={file ? `${file.name}のプレビュー` : "ファイルプレビュー"}
        className="grid min-h-0 min-w-0 flex-1 place-items-center overflow-auto bg-muted/20 p-4"
        role="region"
      >
        {!file || !previewUrl ? (
          <div className="text-center text-muted-foreground">
            <FileSearch className="mx-auto mb-3 size-10" />
            <p className="text-sm">ファイルを選択するとプレビューを表示します。</p>
          </div>
        ) : file.type === "application/pdf" ? (
          <iframe
            className="h-full min-h-[34rem] w-full rounded-md border bg-background"
            src={previewUrl}
            title={`${file.name}のPDFプレビュー`}
          />
        ) : (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            alt={`${file.name}のプレビュー`}
            className="max-h-full max-w-full object-contain"
            src={previewUrl}
          />
        )}
      </div>
    </section>
  );
}
