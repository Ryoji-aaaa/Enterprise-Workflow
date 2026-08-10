import type { DocumentAnalysisParagraph, DocumentAnalysisTable, DocumentAnalysisTableCell } from "./document-analysis.ts";

function normalizeCsvField(value: string): string {
  return value.replace(/\r\n|\r|\n/g, "\r\n");
}

function csvField(value: string): string {
  const normalized = normalizeCsvField(value);
  return /[",\r\n]/.test(normalized) ? `"${normalized.replaceAll('"', '""')}"` : normalized;
}

function csv(rows: string[][]): string {
  return rows.map((row) => row.map(csvField).join(",")).join("\r\n");
}

function cellAt(
  table: DocumentAnalysisTable,
  rowIndex: number,
  columnIndex: number,
): DocumentAnalysisTableCell | undefined {
  return table.cells.find((cell) => cell.rowIndex === rowIndex && cell.columnIndex === columnIndex);
}

export function paragraphsToCsv(paragraphs: DocumentAnalysisParagraph[]): string {
  return csv([
    ["id", "role", "pageNumber", "confidence", "content"],
    ...paragraphs.map((paragraph) => [
      paragraph.id,
      paragraph.role,
      String(paragraph.pageNumber),
      `${(paragraph.confidence * 100).toFixed(1)}%`,
      paragraph.content,
    ]),
  ]);
}

export function tablesToCsv(tables: DocumentAnalysisTable[]): string {
  const maximumColumnCount = Math.max(0, ...tables.map((table) => table.columnCount));
  const rows = tables.flatMap((table) =>
    Array.from({ length: table.rowCount }, (_, rowIndex) => [
      table.id,
      ...Array.from({ length: maximumColumnCount }, (_, columnIndex) =>
        columnIndex < table.columnCount ? cellAt(table, rowIndex, columnIndex)?.content ?? "" : ""),
    ]),
  );

  return csv([
    ["tableId", ...Array.from({ length: maximumColumnCount }, (_, index) => `column${index + 1}`)],
    ...rows,
  ]);
}
