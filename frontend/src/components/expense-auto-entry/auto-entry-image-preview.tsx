"use client";

import { useState } from "react";

import type { AutoEntryPageRef } from "@/lib/auto-entry-review";
import type { AutoEntryEvidenceSource } from "@/lib/expense-auto-entry-evidence";

import { AutoEntrySourceOverlay } from "./auto-entry-source-overlay";
import { useRenderedElementSize } from "./use-rendered-element-size";

export function AutoEntryImagePreview({
  name,
  previewUrl,
  page,
  evidence,
}: {
  name: string;
  previewUrl: string;
  page: AutoEntryPageRef | undefined;
  evidence: readonly AutoEntryEvidenceSource[];
}) {
  const { ref, size } = useRenderedElementSize<HTMLImageElement>();
  const [failedUrl, setFailedUrl] = useState<string | null>(null);

  if (failedUrl === previewUrl) {
    return <p className="rounded-md border border-destructive/40 bg-background p-4 text-sm text-destructive">画像を表示できませんでした。ファイルが破損していないか確認してください。</p>;
  }

  return (
    <div className="relative w-fit max-w-full overflow-hidden rounded-md border bg-background">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        alt={`${name}のプレビュー`}
        className="block h-auto max-w-full"
        onError={() => setFailedUrl(previewUrl)}
        onLoad={() => setFailedUrl(null)}
        ref={ref}
        src={previewUrl}
      />
      <AutoEntrySourceOverlay
        evidence={evidence}
        page={page}
        renderedHeight={size.height}
        renderedWidth={size.width}
      />
    </div>
  );
}
