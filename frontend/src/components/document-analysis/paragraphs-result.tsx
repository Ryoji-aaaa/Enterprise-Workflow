"use client";

import { Badge } from "@/components/ui/badge";
import type { DocumentAnalysisParagraph } from "@/lib/document-analysis";

export function ParagraphsResult({ paragraphs }: { paragraphs: DocumentAnalysisParagraph[] }) {
  return (
    <div className="min-h-0 flex-1 overflow-auto">
      {paragraphs.length === 0 ? (
        <p className="text-sm text-muted-foreground">Paragraphsはありません。</p>
      ) : (
        <ul className="space-y-3">
          {paragraphs.map((paragraph) => (
            <li className="rounded-md border bg-background p-3" key={paragraph.id}>
              <div className="mb-2 flex flex-wrap items-center gap-2">
                <Badge variant="secondary">{paragraph.role}</Badge>
                <span className="text-xs text-muted-foreground">Page {paragraph.pageNumber}</span>
                <span className="text-xs text-muted-foreground">
                  confidence {(paragraph.confidence * 100).toFixed(1)}%
                </span>
              </div>
              <p className="whitespace-pre-wrap text-sm">{paragraph.content}</p>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

