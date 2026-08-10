"use client";

import type { DocumentAnalysisTable, DocumentAnalysisTableCell } from "@/lib/document-analysis";

function cellAt(table: DocumentAnalysisTable, rowIndex: number, columnIndex: number): DocumentAnalysisTableCell | undefined {
  return table.cells.find((cell) => cell.rowIndex === rowIndex && cell.columnIndex === columnIndex);
}

function coveredBySpan(table: DocumentAnalysisTable, rowIndex: number, columnIndex: number): boolean {
  return table.cells.some((cell) => {
    if (cell.rowIndex === rowIndex && cell.columnIndex === columnIndex) {
      return false;
    }
    return rowIndex >= cell.rowIndex
      && rowIndex < cell.rowIndex + cell.rowSpan
      && columnIndex >= cell.columnIndex
      && columnIndex < cell.columnIndex + cell.columnSpan;
  });
}

export function TablesResult({ tables }: { tables: DocumentAnalysisTable[] }) {
  return (
    <div className="min-h-0 flex-1 space-y-4 overflow-auto" data-testid="document-analysis-tables-content">
      {tables.length === 0 ? (
        <p className="text-sm text-muted-foreground">Tablesはありません。</p>
      ) : (
        tables.map((table) => (
          <section className="space-y-2" key={table.id}>
            <h3 className="text-sm font-medium">{table.id}</h3>
            <div className="overflow-auto rounded-md border">
              <table className="w-full min-w-[32rem] border-collapse bg-background text-left text-xs">
                <tbody>
                  {Array.from({ length: table.rowCount }, (_, rowIndex) => (
                    <tr className="border-b last:border-b-0" key={rowIndex}>
                      {Array.from({ length: table.columnCount }, (_, columnIndex) => {
                        if (coveredBySpan(table, rowIndex, columnIndex)) {
                          return null;
                        }
                        const cell = cellAt(table, rowIndex, columnIndex);
                        if (!cell) {
                          return <td className="border-r p-2 last:border-r-0" key={columnIndex} />;
                        }
                        const Tag = cell.kind === "columnHeader" ? "th" : "td";
                        return (
                          <Tag
                            className="border-r p-2 align-top last:border-r-0 data-[kind=columnHeader]:bg-muted data-[kind=columnHeader]:font-medium"
                            colSpan={cell.columnSpan}
                            data-kind={cell.kind}
                            key={columnIndex}
                            rowSpan={cell.rowSpan}
                            title={`Page ${cell.pageNumber}, confidence ${(cell.confidence * 100).toFixed(1)}%`}
                          >
                            {cell.content}
                          </Tag>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        ))
      )}
    </div>
  );
}
