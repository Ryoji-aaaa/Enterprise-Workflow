import type { AutoEntryPageRef } from "@/lib/auto-entry-review";
import {
  autoEntryEvidencePointsAttribute,
  isAutoEntryEvidenceSourceActive,
  renderAutoEntryPageEvidence,
  type AutoEntryEvidenceSource,
} from "@/lib/expense-auto-entry-evidence";

export function AutoEntrySourceOverlay({
  page,
  evidence,
  activeFieldPath,
  renderedWidth,
  renderedHeight,
}: {
  page: AutoEntryPageRef | undefined;
  evidence: readonly AutoEntryEvidenceSource[];
  activeFieldPath: string | null;
  renderedWidth: number;
  renderedHeight: number;
}) {
  const renderedEvidence = renderAutoEntryPageEvidence(
    page,
    evidence,
    renderedWidth,
    renderedHeight,
  );
  if (renderedEvidence.length === 0) return null;

  return (
    <svg
      aria-hidden="true"
      className="pointer-events-none absolute inset-0"
      data-evidence-count={renderedEvidence.length}
      data-page-number={page?.pageNumber}
      data-testid="expense-auto-entry-source-overlay"
      height={renderedHeight}
      viewBox={`0 0 ${renderedWidth} ${renderedHeight}`}
      width={renderedWidth}
    >
      {renderedEvidence.map((source) => {
        const active = isAutoEntryEvidenceSourceActive(source, activeFieldPath);
        return (
          <polygon
            data-active={active}
            data-field-path={source.fieldPath}
            data-page-number={source.pageNumber}
            data-source-index={source.sourceIndex}
            fill={active ? "rgba(220, 38, 38, 0.06)" : "rgba(37, 99, 235, 0.04)"}
            key={`${source.fieldPath}-${source.pageNumber}-${source.sourceIndex}`}
            points={autoEntryEvidencePointsAttribute(source.points)}
            stroke={active ? "#dc2626" : "#2563eb"}
            strokeLinejoin="round"
            strokeWidth="2"
            vectorEffect="non-scaling-stroke"
          />
        );
      })}
    </svg>
  );
}
