"use client";

import { FileSearch, ZoomIn, ZoomOut } from "lucide-react";
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
} from "react";

import { Button } from "@/components/ui/button";
import type { AutoEntryPageRef } from "@/lib/auto-entry-review";
import type { AnalyzableFile } from "@/lib/document-analysis";
import {
  autoEntryPageForNumber,
  getAutoEntryEvidenceSources,
} from "@/lib/expense-auto-entry-evidence";
import type { ResolvedAutoEntryField } from "@/lib/expense-auto-entry";

import { AutoEntryImagePreview } from "./auto-entry-image-preview";
import { AutoEntryPdfPreview } from "./auto-entry-pdf-preview";

const MIN_ZOOM = 0.5;
const MAX_ZOOM = 3;
const ZOOM_STEP = 0.25;

type PreviewPanSession = {
  pointerId: number;
  startX: number;
  startY: number;
  initialScrollLeft: number;
  initialScrollTop: number;
};

function previewCanPan(element: HTMLDivElement): boolean {
  return element.scrollWidth > element.clientWidth || element.scrollHeight > element.clientHeight;
}

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
  const previewScrollContainerRef = useRef<HTMLDivElement>(null);
  const panSessionRef = useRef<PreviewPanSession | null>(null);
  const [zoomState, setZoomState] = useState({ previewUrl: null as string | null, zoom: 1 });
  const [isPanAvailable, setIsPanAvailable] = useState(false);
  const [isPanning, setIsPanning] = useState(false);
  const zoom = zoomState.previewUrl === previewUrl ? zoomState.zoom : 1;
  const zoomAvailable = file !== null && previewUrl !== null;

  const updatePanAvailability = useCallback(() => {
    const element = previewScrollContainerRef.current;
    const nextValue = element !== null && previewCanPan(element);
    setIsPanAvailable((current) => current === nextValue ? current : nextValue);
  }, []);

  useEffect(() => {
    const element = previewScrollContainerRef.current;
    if (!element) return;

    element.scrollLeft = 0;
    element.scrollTop = 0;
    panSessionRef.current = null;
    setIsPanning(false);
    updatePanAvailability();
  }, [previewUrl, updatePanAvailability]);

  useEffect(() => {
    const element = previewScrollContainerRef.current;
    if (!element) return;

    let animationFrame: number | null = null;
    const scheduleUpdate = () => {
      if (animationFrame !== null) window.cancelAnimationFrame(animationFrame);
      animationFrame = window.requestAnimationFrame(() => {
        animationFrame = null;
        updatePanAvailability();
      });
    };
    const resizeObserver = new ResizeObserver(scheduleUpdate);
    const observeDocumentPages = () => {
      resizeObserver.observe(element);
      element.querySelectorAll<HTMLElement>(
        '[data-testid="expense-auto-entry-image-page"], [data-testid="expense-auto-entry-pdf-page"]',
      ).forEach((pageElement) => resizeObserver.observe(pageElement));
      scheduleUpdate();
    };
    const mutationObserver = new MutationObserver(observeDocumentPages);

    mutationObserver.observe(element, { childList: true, subtree: true });
    observeDocumentPages();
    return () => {
      if (animationFrame !== null) window.cancelAnimationFrame(animationFrame);
      mutationObserver.disconnect();
      resizeObserver.disconnect();
    };
  }, [previewUrl, updatePanAvailability, zoom]);

  function finishPanning(event: ReactPointerEvent<HTMLDivElement>) {
    const session = panSessionRef.current;
    if (!session || session.pointerId !== event.pointerId) return;

    panSessionRef.current = null;
    setIsPanning(false);
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  }

  function handlePointerDown(event: ReactPointerEvent<HTMLDivElement>) {
    if (event.pointerType === "touch" || !event.isPrimary || event.button !== 0) return;

    const element = event.currentTarget;
    const canPan = previewCanPan(element);
    setIsPanAvailable(canPan);
    if (!canPan) return;

    const bounds = element.getBoundingClientRect();
    const isOnVerticalScrollbar = event.clientX >= bounds.left + element.clientWidth;
    const isOnHorizontalScrollbar = event.clientY >= bounds.top + element.clientHeight;
    if (isOnVerticalScrollbar || isOnHorizontalScrollbar) return;

    panSessionRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      initialScrollLeft: element.scrollLeft,
      initialScrollTop: element.scrollTop,
    };
    element.setPointerCapture(event.pointerId);
    setIsPanning(true);
    event.preventDefault();
  }

  function handlePointerMove(event: ReactPointerEvent<HTMLDivElement>) {
    const session = panSessionRef.current;
    if (!session || session.pointerId !== event.pointerId) return;

    event.currentTarget.scrollLeft = session.initialScrollLeft - (event.clientX - session.startX);
    event.currentTarget.scrollTop = session.initialScrollTop - (event.clientY - session.startY);
    event.preventDefault();
  }

  function changeZoom(delta: number) {
    if (!zoomAvailable) return;
    setZoomState((current) => {
      const currentZoom = current.previewUrl === previewUrl ? current.zoom : 1;
      return {
        previewUrl,
        zoom: Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, currentZoom + delta)),
      };
    });
  }

  return (
    <section className="flex h-full min-h-0 min-w-0 flex-col">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-3 border-b px-4 py-3">
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-medium">Preview</h2>
          <p className="mt-1 truncate text-xs text-muted-foreground">
            {file ? file.name : "ファイル未選択"}
          </p>
        </div>
        <div aria-label="プレビューの拡大・縮小" className="flex items-center gap-1" role="group">
          <Button
            aria-label="プレビューを縮小"
            disabled={!zoomAvailable || zoom <= MIN_ZOOM}
            onClick={() => changeZoom(-ZOOM_STEP)}
            size="icon-sm"
            type="button"
            variant="outline"
          >
            <ZoomOut />
          </Button>
          <output
            aria-live="polite"
            className="w-12 text-center text-xs tabular-nums"
            data-testid="expense-auto-entry-zoom-value"
          >
            {Math.round(zoom * 100)}%
          </output>
          <Button
            aria-label="プレビューを拡大"
            disabled={!zoomAvailable || zoom >= MAX_ZOOM}
            onClick={() => changeZoom(ZOOM_STEP)}
            size="icon-sm"
            type="button"
            variant="outline"
          >
            <ZoomIn />
          </Button>
        </div>
      </div>
      <div
        aria-label={file ? `${file.name}のプレビュー` : "ファイルプレビュー"}
        className={`min-h-0 min-w-0 flex-1 overflow-auto bg-muted/20 p-4 ${
          isPanning ? "cursor-grabbing select-none" : isPanAvailable ? "cursor-grab" : ""
        }`}
        data-pan-available={isPanAvailable}
        data-panning={isPanning}
        data-testid="expense-auto-entry-preview-content"
        onLostPointerCapture={finishPanning}
        onPointerCancel={finishPanning}
        onPointerDown={handlePointerDown}
        onPointerEnter={updatePanAvailability}
        onPointerMove={handlePointerMove}
        onPointerUp={finishPanning}
        ref={previewScrollContainerRef}
        role="region"
      >
        {!file || !previewUrl ? (
          <div className="grid min-h-full min-w-full place-items-center">
            <div className="text-center text-muted-foreground">
              <FileSearch className="mx-auto mb-3 size-10" />
              <p className="text-sm">ファイルを選択するとプレビューを表示します。</p>
            </div>
          </div>
        ) : file.type === "application/pdf" ? (
          <AutoEntryPdfPreview
            activeFieldPath={activeFieldPath}
            evidence={evidence}
            pages={pages}
            previewUrl={previewUrl}
            scrollContainerRef={previewScrollContainerRef}
            zoom={zoom}
          />
        ) : (
          <AutoEntryImagePreview
            activeFieldPath={activeFieldPath}
            evidence={evidence}
            name={file.name}
            page={autoEntryPageForNumber(pages, 1)}
            previewUrl={previewUrl}
            zoom={zoom}
          />
        )}
      </div>
    </section>
  );
}
