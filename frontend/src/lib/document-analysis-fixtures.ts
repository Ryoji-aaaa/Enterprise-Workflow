import type {
  DocumentAnalysisProvider,
  DocumentAnalysisResult,
  DocumentAnalysisTable,
} from "./document-analysis";

const orderTable: DocumentAnalysisTable = {
  id: "table-purchase-order-items",
  rowCount: 4,
  columnCount: 5,
  cells: [
    { rowIndex: 0, columnIndex: 0, rowSpan: 1, columnSpan: 1, kind: "columnHeader", content: "No.", pageNumber: 1, confidence: 0.99 },
    { rowIndex: 0, columnIndex: 1, rowSpan: 1, columnSpan: 1, kind: "columnHeader", content: "品名", pageNumber: 1, confidence: 0.99 },
    { rowIndex: 0, columnIndex: 2, rowSpan: 1, columnSpan: 1, kind: "columnHeader", content: "数量", pageNumber: 1, confidence: 0.99 },
    { rowIndex: 0, columnIndex: 3, rowSpan: 1, columnSpan: 1, kind: "columnHeader", content: "単価", pageNumber: 1, confidence: 0.99 },
    { rowIndex: 0, columnIndex: 4, rowSpan: 1, columnSpan: 1, kind: "columnHeader", content: "金額", pageNumber: 1, confidence: 0.99 },
    { rowIndex: 1, columnIndex: 0, rowSpan: 1, columnSpan: 1, kind: "content", content: "1", pageNumber: 1, confidence: 0.98 },
    { rowIndex: 1, columnIndex: 1, rowSpan: 1, columnSpan: 1, kind: "content", content: "ワークフロー利用ライセンス", pageNumber: 1, confidence: 0.97 },
    { rowIndex: 1, columnIndex: 2, rowSpan: 1, columnSpan: 1, kind: "content", content: "12", pageNumber: 1, confidence: 0.98 },
    { rowIndex: 1, columnIndex: 3, rowSpan: 1, columnSpan: 1, kind: "content", content: "8,000", pageNumber: 1, confidence: 0.97 },
    { rowIndex: 1, columnIndex: 4, rowSpan: 1, columnSpan: 1, kind: "content", content: "96,000", pageNumber: 1, confidence: 0.97 },
    { rowIndex: 2, columnIndex: 0, rowSpan: 1, columnSpan: 1, kind: "content", content: "2", pageNumber: 1, confidence: 0.98 },
    { rowIndex: 2, columnIndex: 1, rowSpan: 1, columnSpan: 1, kind: "content", content: "初期設定支援", pageNumber: 1, confidence: 0.96 },
    { rowIndex: 2, columnIndex: 2, rowSpan: 1, columnSpan: 1, kind: "content", content: "1", pageNumber: 1, confidence: 0.98 },
    { rowIndex: 2, columnIndex: 3, rowSpan: 1, columnSpan: 1, kind: "content", content: "50,000", pageNumber: 1, confidence: 0.96 },
    { rowIndex: 2, columnIndex: 4, rowSpan: 1, columnSpan: 1, kind: "content", content: "50,000", pageNumber: 1, confidence: 0.96 },
    { rowIndex: 3, columnIndex: 0, rowSpan: 1, columnSpan: 4, kind: "content", content: "合計", pageNumber: 1, confidence: 0.99 },
    { rowIndex: 3, columnIndex: 4, rowSpan: 1, columnSpan: 1, kind: "content", content: "146,000", pageNumber: 1, confidence: 0.99 },
  ],
};

export function documentAnalysisFixture(provider: DocumentAnalysisProvider): DocumentAnalysisResult {
  return {
    provider,
    markdown: [
      "# 発注書",
      "",
      "- 発注番号: PO-2026-0807",
      "- 発行日: 2026年8月7日",
      "- 発注先: 株式会社サンプルテクノロジー",
      "- 合計金額: 146,000円",
      "",
      "| No. | 品名 | 数量 | 単価 | 金額 |",
      "| --- | --- | ---: | ---: | ---: |",
      "| 1 | ワークフロー利用ライセンス | 12 | 8,000 | 96,000 |",
      "| 2 | 初期設定支援 | 1 | 50,000 | 50,000 |",
      "| 合計 |  |  |  | 146,000 |",
    ].join("\n"),
    paragraphs: [
      {
        id: "paragraph-title",
        content: "発注書",
        role: "title",
        pageNumber: 1,
        confidence: 0.99,
        span: { offset: 0, length: 3 },
      },
      {
        id: "paragraph-order-number",
        content: "発注番号: PO-2026-0807",
        role: "keyValuePair",
        pageNumber: 1,
        confidence: 0.97,
        span: { offset: 5, length: 21 },
      },
      {
        id: "paragraph-issued-date",
        content: "発行日: 2026年8月7日",
        role: "keyValuePair",
        pageNumber: 1,
        confidence: 0.96,
        span: { offset: 28, length: 18 },
      },
      {
        id: "paragraph-vendor",
        content: "発注先: 株式会社サンプルテクノロジー",
        role: "keyValuePair",
        pageNumber: 1,
        confidence: 0.95,
        span: { offset: 48, length: 23 },
      },
      {
        id: "paragraph-total",
        content: "合計金額: 146,000円",
        role: "keyValuePair",
        pageNumber: 1,
        confidence: 0.98,
        span: { offset: 73, length: 15 },
      },
    ],
    tables: [orderTable],
    rawResult: {
      provider,
      documentType: "purchaseOrder",
      pages: [{ pageNumber: 1, width: 8.27, height: 11.69, unit: "inch" }],
      fields: {
        title: { value: "発注書", confidence: 0.99 },
        orderNumber: { value: "PO-2026-0807", confidence: 0.97 },
        issuedDate: { value: "2026-08-07", confidence: 0.96 },
        vendor: { value: "株式会社サンプルテクノロジー", confidence: 0.95 },
        totalAmount: { value: 146000, currency: "JPY", confidence: 0.98 },
      },
      tables: [orderTable],
      source: "frontend-fixture",
    },
  };
}

