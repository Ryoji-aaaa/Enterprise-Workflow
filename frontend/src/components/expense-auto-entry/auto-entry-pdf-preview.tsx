"use client";

import { useCallback, useEffect, useState } from "react";
import type { PDFDocumentLoadingTask, PDFDocumentProxy, RenderTask } from "pdfjs-dist";

import type { AutoEntryPageRef } from "@/lib/auto-entry-review";
import {
  autoEntryPageForNumber,
  type AutoEntryEvidenceSource,
} from "@/lib/expense-auto-entry-evidence";

import { AutoEntrySourceOverlay } from "./auto-entry-source-overlay";
import { useRenderedElementSize } from "./use-rendered-element-size";

const PDF_WORKER_URL = new URL(
  "pdfjs-dist/build/pdf.worker.min.mjs",
  import.meta.url,
).toString();

function AutoEntryPdfPage({
  document,
  pageNumber,
  page,
  evidence,
  onRenderError,
}: {
  document: PDFDocumentProxy;
  pageNumber: number;
  page: AutoEntryPageRef | undefined;
  evidence: readonly AutoEntryEvidenceSource[];
  onRenderError: () => void;
}) {
  const { ref: containerRef, size: containerSize } = useRenderedElementSize<HTMLDivElement>();
  const [canvas, setCanvas] = useState<HTMLCanvasElement | null>(null);
  const [renderedSize, setRenderedSize] = useState({ width: 0, height: 0 });

  useEffect(() => {
    if (!canvas || containerSize.width <= 0) return;
    let disposed = false;
    let renderTask: RenderTask | undefined;

    void document.getPage(pageNumber).then((pdfPage) => {
      if (disposed) return;
      const baseViewport = pdfPage.getViewport({ scale: 1 });
      const viewport = pdfPage.getViewport({ scale: containerSize.width / baseViewport.width });
      const outputScale = window.devicePixelRatio || 1;
      canvas.width = Math.floor(viewport.width * outputScale);
      canvas.height = Math.floor(viewport.height * outputScale);
      canvas.style.width = `${viewport.width}px`;
      canvas.style.height = `${viewport.height}px`;
      setRenderedSize({ width: viewport.width, height: viewport.height });
      renderTask = pdfPage.render({
        canvas,
        viewport,
        transform: outputScale === 1 ? undefined : [outputScale, 0, 0, outputScale, 0, 0],
      });
      return renderTask.promise.finally(() => pdfPage.cleanup());
    }).catch((cause: unknown) => {
      if (!disposed && !(cause instanceof Error && cause.name === "RenderingCancelledException")) {
        onRenderError();
      }
    });

    return () => {
      disposed = true;
      renderTask?.cancel();
    };
  }, [canvas, containerSize.width, document, onRenderError, pageNumber]);

  return (
    <div className="w-full" ref={containerRef}>
      <div
        className="relative overflow-hidden rounded-md border bg-background shadow-sm"
        data-page-number={pageNumber}
        data-testid="expense-auto-entry-pdf-page"
        style={renderedSize.width > 0 ? { height: renderedSize.height, width: renderedSize.width } : undefined}
      >
        <canvas className="block" ref={setCanvas} />
        <AutoEntrySourceOverlay
          evidence={evidence}
          page={page}
          renderedHeight={renderedSize.height}
          renderedWidth={renderedSize.width}
        />
      </div>
    </div>
  );
}

export function AutoEntryPdfPreview({
  previewUrl,
  pages,
  evidence,
}: {
  previewUrl: string;
  pages: readonly AutoEntryPageRef[];
  evidence: readonly AutoEntryEvidenceSource[];
}) {
  const [loadState, setLoadState] = useState<{
    previewUrl: string;
    document: PDFDocumentProxy | null;
    error: boolean;
  }>({ previewUrl, document: null, error: false });
  const document = loadState.previewUrl === previewUrl ? loadState.document : null;
  const error = loadState.previewUrl === previewUrl && loadState.error;
  const handleRenderError = useCallback(() => {
    setLoadState({ previewUrl, document: null, error: true });
  }, [previewUrl]);

  useEffect(() => {
    let disposed = false;
    let loadingTask: PDFDocumentLoadingTask | undefined;

    void import("pdfjs-dist").then((pdfjs) => {
      if (disposed) return;
      pdfjs.GlobalWorkerOptions.workerSrc = PDF_WORKER_URL;
      loadingTask = pdfjs.getDocument({ url: previewUrl });
      return loadingTask.promise;
    }).then((loadedDocument) => {
      if (!loadedDocument) return;
      if (disposed) {
        return;
      }
      setLoadState({ previewUrl, document: loadedDocument, error: false });
    }).catch(() => {
      if (!disposed) setLoadState({ previewUrl, document: null, error: true });
    });

    return () => {
      disposed = true;
      void loadingTask?.destroy();
    };
  }, [previewUrl]);

  if (error) {
    return <p className="rounded-md border border-destructive/40 bg-background p-4 text-sm text-destructive">PDFを表示できませんでした。ファイルが破損していないか確認してください。</p>;
  }
  if (!document) {
    return <p className="text-sm text-muted-foreground">PDFを読み込んでいます…</p>;
  }

  return (
    <div className="w-full space-y-4">
      {Array.from({ length: document.numPages }, (_, index) => {
        const pageNumber = index + 1;
        return (
          <AutoEntryPdfPage
            document={document}
            evidence={evidence}
            key={pageNumber}
            onRenderError={handleRenderError}
            page={autoEntryPageForNumber(pages, pageNumber)}
            pageNumber={pageNumber}
          />
        );
      })}
    </div>
  );
}
