import type {
  AutoEntryPageRef,
  AutoEntryPoint,
} from "./auto-entry-review.ts";
import type { ResolvedAutoEntryField } from "./expense-auto-entry.ts";

export type AutoEntryEvidenceSource = {
  fieldPath: string;
  pageNumber: number;
  sourceIndex: number;
  polygon: AutoEntryPoint[];
};

export type RenderedAutoEntryEvidenceSource = AutoEntryEvidenceSource & {
  points: AutoEntryPoint[];
};

export function getAutoEntryEvidenceSources(
  fields: readonly ResolvedAutoEntryField[],
): AutoEntryEvidenceSource[] {
  const evidence: AutoEntryEvidenceSource[] = [];

  for (const field of fields) {
    if (field.deleted) continue;
    field.field.sources.forEach((source, sourceIndex) => {
      if (source.polygon.length < 3) return;
      evidence.push({
        fieldPath: field.path,
        pageNumber: source.pageNumber,
        sourceIndex,
        polygon: source.polygon,
      });
    });
  }

  return evidence;
}

export function getAutoEntryPageEvidence(
  evidence: readonly AutoEntryEvidenceSource[],
  pageNumber: number,
): AutoEntryEvidenceSource[] {
  return evidence.filter((source) => source.pageNumber === pageNumber);
}

export function isAutoEntryEvidenceSourceActive(
  source: AutoEntryEvidenceSource,
  activeFieldPath: string | null,
): boolean {
  return activeFieldPath !== null && source.fieldPath === activeFieldPath;
}

export function getAutoEntryActiveEvidencePageNumber(
  evidence: readonly AutoEntryEvidenceSource[],
  activeFieldPath: string | null,
): number | null {
  if (activeFieldPath === null) return null;
  return evidence.find((source) => source.fieldPath === activeFieldPath)?.pageNumber ?? null;
}

export function getAutoEntryPreviewScrollTop({
  containerTop,
  containerBottom,
  targetTop,
  targetBottom,
  currentScrollTop,
}: {
  containerTop: number;
  containerBottom: number;
  targetTop: number;
  targetBottom: number;
  currentScrollTop: number;
}): number | null {
  const targetIsVisible = targetBottom > containerTop && targetTop < containerBottom;
  if (targetIsVisible) return null;
  return Math.max(0, currentScrollTop + targetTop - containerTop);
}

export function scaleAutoEntryPolygon(
  polygon: readonly AutoEntryPoint[],
  sourceWidth: number,
  sourceHeight: number,
  renderedWidth: number,
  renderedHeight: number,
): AutoEntryPoint[] {
  if (sourceWidth <= 0 || sourceHeight <= 0 || renderedWidth <= 0 || renderedHeight <= 0) {
    return [];
  }

  const scaleX = renderedWidth / sourceWidth;
  const scaleY = renderedHeight / sourceHeight;
  return polygon.map(({ x, y }) => ({ x: x * scaleX, y: y * scaleY }));
}

export function renderAutoEntryPageEvidence(
  page: AutoEntryPageRef | undefined,
  evidence: readonly AutoEntryEvidenceSource[],
  renderedWidth: number,
  renderedHeight: number,
): RenderedAutoEntryEvidenceSource[] {
  if (!page) return [];
  return getAutoEntryPageEvidence(evidence, page.pageNumber).flatMap((source) => {
    const points = scaleAutoEntryPolygon(
      source.polygon,
      page.width,
      page.height,
      renderedWidth,
      renderedHeight,
    );
    return points.length >= 3 ? [{ ...source, points }] : [];
  });
}

export function autoEntryEvidencePointsAttribute(points: readonly AutoEntryPoint[]): string {
  return points.map(({ x, y }) => `${x},${y}`).join(" ");
}

export function autoEntryPageForNumber(
  pages: readonly AutoEntryPageRef[],
  pageNumber: number,
): AutoEntryPageRef | undefined {
  return pages.find((page) => page.pageNumber === pageNumber);
}
