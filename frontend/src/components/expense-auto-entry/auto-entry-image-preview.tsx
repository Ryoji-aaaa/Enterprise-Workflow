"use client";

import { useEffect, useState } from "react";

import type { AutoEntryPageRef } from "@/lib/auto-entry-review";
import type { AutoEntryEvidenceSource } from "@/lib/expense-auto-entry-evidence";

import { AutoEntrySourceOverlay } from "./auto-entry-source-overlay";
import { useRenderedElementSize } from "./use-rendered-element-size";

export function AutoEntryImagePreview({
  name,
  previewUrl,
  zoom,
  page,
  evidence,
  activeFieldPath,
  onLayoutChange,
}: {
  name: string;
  previewUrl: string;
  zoom: number;
  page: AutoEntryPageRef | undefined;
  evidence: readonly AutoEntryEvidenceSource[];
  activeFieldPath: string | null;
  onLayoutChange: () => void;
}) {
  const { ref: containerRef, size: containerSize } = useRenderedElementSize<HTMLDivElement>();
  const [imageSize, setImageSize] = useState<{
    previewUrl: string;
    naturalWidth: number;
    naturalHeight: number;
  } | null>(null);
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const currentImageSize = imageSize?.previewUrl === previewUrl ? imageSize : null;
  const fitWidth = currentImageSize
    ? Math.min(currentImageSize.naturalWidth, containerSize.width)
    : 0;
  const renderedWidth = fitWidth * zoom;
  const renderedHeight = currentImageSize && currentImageSize.naturalWidth > 0
    ? renderedWidth * currentImageSize.naturalHeight / currentImageSize.naturalWidth
    : 0;

  useEffect(() => {
    if (renderedWidth <= 0 || renderedHeight <= 0) return;
    onLayoutChange();
  }, [onLayoutChange, renderedHeight, renderedWidth]);

  if (failedUrl === previewUrl) {
    return <p className="rounded-md border border-destructive/40 bg-background p-4 text-sm text-destructive">画像を表示できませんでした。ファイルが破損していないか確認してください。</p>;
  }

  return (
    <div className="flex min-h-full w-full min-w-0" ref={containerRef}>
      <div
        className="relative m-auto shrink-0 overflow-hidden rounded-md bg-background ring-1 ring-foreground/10"
        data-rendered-zoom={renderedWidth > 0 ? zoom : undefined}
        data-testid="expense-auto-entry-image-page"
        style={renderedWidth > 0 ? { height: renderedHeight, width: renderedWidth } : undefined}
      >
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          alt={`${name}のプレビュー`}
          className="block max-w-none"
          draggable={false}
          onError={() => setFailedUrl(previewUrl)}
          onLoad={(event) => {
            setFailedUrl(null);
            setImageSize({
              previewUrl,
              naturalHeight: event.currentTarget.naturalHeight,
              naturalWidth: event.currentTarget.naturalWidth,
            });
          }}
          src={previewUrl}
          style={renderedWidth > 0 ? { height: renderedHeight, width: renderedWidth } : undefined}
        />
        <AutoEntrySourceOverlay
          activeFieldPath={activeFieldPath}
          evidence={evidence}
          page={page}
          renderedHeight={renderedHeight}
          renderedWidth={renderedWidth}
        />
      </div>
    </div>
  );
}
