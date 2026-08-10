import assert from "node:assert/strict";
import test from "node:test";

import {
  DOCUMENT_ANALYSIS_MAX_FILE_SIZE_BYTES,
  DOCUMENT_ANALYSIS_PROVIDER_CONFIGS,
  documentAnalysisJobErrorMessage,
  documentAnalysisReducer,
  initialDocumentAnalysisState,
  mapDocumentAnalysisViewV1,
  serverStatusToDocumentAnalysisStatus,
  validateDocumentFile,
  validateSingleDocumentSelection,
} from "./document-analysis.ts";
import { documentAnalysisSourceUrl, type DocumentAnalysisJob } from "./document-analysis-api.ts";
import { paragraphsToCsv, tablesToCsv } from "./document-analysis-copy.ts";

const job: DocumentAnalysisJob = {
  id: "123e4567-e89b-42d3-a456-426614174000",
  provider: "DOCUMENT_INTELLIGENCE",
  modelId: "prebuilt-layout",
  providerApiVersion: "2024-11-30",
  normalizedSchemaVersion: 1,
  status: "QUEUED",
  originalFileName: "order.pdf",
  contentType: "application/pdf",
  fileSize: 2048,
  attemptCount: 0,
  errorCode: null,
  errorMessage: null,
  createdAt: "2026-08-01T00:00:00Z",
  startedAt: null,
  completedAt: null,
  expiresAt: "2026-08-08T00:00:00Z",
};

test("PDF、JPEG、PNGを受け付ける", () => {
  for (const type of ["application/pdf", "image/jpeg", "image/png"]) {
    assert.deepEqual(validateDocumentFile({ type, size: DOCUMENT_ANALYSIS_MAX_FILE_SIZE_BYTES }), { valid: true });
  }
});

test("対応外MIME typeを拒否する", () => {
  assert.deepEqual(validateDocumentFile({ type: "text/plain", size: 1024 }), {
    valid: false,
    message: "対応形式はPDF、JPEG、PNGです。",
  });
});

test("10 MiB以下を受け付け、10 MiB超を拒否する", () => {
  assert.deepEqual(validateDocumentFile({ type: "application/pdf", size: DOCUMENT_ANALYSIS_MAX_FILE_SIZE_BYTES }), { valid: true });
  assert.deepEqual(validateDocumentFile({ type: "application/pdf", size: DOCUMENT_ANALYSIS_MAX_FILE_SIZE_BYTES + 1 }), {
    valid: false,
    message: "ファイルサイズは10 MiB以下にしてください。",
  });
});

test("複数ファイル選択を拒否する", () => {
  const files = [
    { name: "one.pdf", type: "application/pdf", size: 1024 },
    { name: "two.pdf", type: "application/pdf", size: 1024 },
  ];

  assert.deepEqual(validateSingleDocumentSelection(files), {
    valid: false,
    message: "一度に分析できるファイルは1件です。",
  });
});

test("Provider configが2種類存在する", () => {
  assert.equal(DOCUMENT_ANALYSIS_PROVIDER_CONFIGS.DOCUMENT_INTELLIGENCE.route, "/document-intelligence");
  assert.equal(DOCUMENT_ANALYSIS_PROVIDER_CONFIGS.CONTENT_UNDERSTANDING.route, "/content-understanding");
});

test("選択からjob受付、view取得成功へ状態遷移する", () => {
  const file = { name: "order.pdf", type: "application/pdf", size: 2048 };
  const selected = documentAnalysisReducer(initialDocumentAnalysisState, {
    type: "select",
    file,
    validation: { valid: true },
  });

  assert.equal(selected.status, "selected");
  assert.deepEqual(selected.completedStatuses, ["selected"]);

  const uploading = documentAnalysisReducer(selected, { type: "upload" });
  assert.equal(uploading.status, "uploading");

  const queued = documentAnalysisReducer(uploading, { type: "job", job });
  assert.equal(queued.status, "queued");
  assert.equal(queued.job?.id, job.id);

  const succeeded = documentAnalysisReducer(queued, {
    type: "view",
    job: { ...job, status: "SUCCEEDED" },
    result: {
      analysisId: job.id,
      provider: "DOCUMENT_INTELLIGENCE",
      modelId: "prebuilt-layout",
      providerApiVersion: "2024-11-30",
      markdown: "# 発注書",
      paragraphs: [],
      tables: [],
    },
  });

  assert.equal(succeeded.status, "succeeded");
  assert.equal(succeeded.result?.provider, "DOCUMENT_INTELLIGENCE");
  assert.deepEqual(succeeded.completedStatuses, ["selected", "uploading", "queued", "running", "succeeded"]);
});

test("不正な選択はfailed状態にする", () => {
  const failed = documentAnalysisReducer(initialDocumentAnalysisState, {
    type: "reject",
    message: "対応形式はPDF、JPEG、PNGです。",
  });

  assert.equal(failed.status, "failed");
  assert.equal(failed.error, "対応形式はPDF、JPEG、PNGです。");
  assert.equal(failed.selectedFile, null);
});

test("Backend job statusをUI statusへmappingする", () => {
  assert.equal(serverStatusToDocumentAnalysisStatus("QUEUED"), "queued");
  assert.equal(serverStatusToDocumentAnalysisStatus("RUNNING"), "running");
  assert.equal(serverStatusToDocumentAnalysisStatus("SUCCEEDED"), "succeeded");
  assert.equal(serverStatusToDocumentAnalysisStatus("FAILED"), "failed");
  assert.equal(serverStatusToDocumentAnalysisStatus("FAILED_RECOVERY_REQUIRED"), "failed");
  assert.equal(serverStatusToDocumentAnalysisStatus("EXPIRED"), "failed");
});

test("FAILED_RECOVERY_REQUIREDは復旧が必要な分析として表示する", () => {
  assert.match(
    documentAnalysisJobErrorMessage({ ...job, status: "FAILED_RECOVERY_REQUIRED" }),
    /復旧が必要/,
  );
});

test("Normalized V1をMarkdown、Paragraphs、Tablesへmappingする", () => {
  const result = mapDocumentAnalysisViewV1({
    schemaVersion: 1,
    analysisId: job.id,
    provider: "DOCUMENT_INTELLIGENCE",
    modelId: "prebuilt-layout",
    providerApiVersion: "2024-11-30",
    status: "SUCCEEDED",
    documents: [{
      markdown: "# 発注書",
      paragraphs: [{
        index: 0,
        content: "発注番号: PO-2026-0001",
        role: "sectionHeading",
        pageNumber: 1,
        confidence: 0.99,
        source: { offset: 4, length: 18, polygon: [] },
      }],
      tables: [{
        index: 0,
        rowCount: 2,
        columnCount: 2,
        cells: [{
          rowIndex: 0,
          columnIndex: 0,
          kind: "columnHeader",
          content: "品名",
          pageNumber: 1,
          confidence: 0.98,
        }],
      }],
    }],
  });

  assert.equal(result.markdown, "# 発注書");
  assert.equal(result.paragraphs[0]?.content, "発注番号: PO-2026-0001");
  assert.deepEqual(result.paragraphs[0]?.span, { offset: 4, length: 18 });
  assert.equal(result.tables[0]?.cells[0]?.kind, "columnHeader");
});

test("Paragraphsは全表示項目をRFC 4180形式CSVへ変換する", () => {
  assert.equal(
    paragraphsToCsv([{
      id: "paragraph-0",
      role: "content",
      pageNumber: 2,
      confidence: 0.987,
      content: "値, \"引用\"\n次の行",
    }]),
    "id,role,pageNumber,confidence,content\r\nparagraph-0,content,2,98.7%,\"値, \"\"引用\"\"\r\n次の行\"",
  );
});

test("Tablesは複数表を統合し結合セルと欠損セルを空欄のCSVへ変換する", () => {
  assert.equal(
    tablesToCsv([
      {
        id: "table-0",
        rowCount: 2,
        columnCount: 3,
        cells: [
          { rowIndex: 0, columnIndex: 0, rowSpan: 1, columnSpan: 2, kind: "columnHeader", content: "見出し", pageNumber: 1, confidence: 1 },
          { rowIndex: 0, columnIndex: 2, rowSpan: 1, columnSpan: 1, kind: "columnHeader", content: "C", pageNumber: 1, confidence: 1 },
          { rowIndex: 1, columnIndex: 0, rowSpan: 1, columnSpan: 1, kind: "content", content: "A", pageNumber: 1, confidence: 1 },
          { rowIndex: 1, columnIndex: 2, rowSpan: 1, columnSpan: 1, kind: "content", content: "値,\"Q\"", pageNumber: 1, confidence: 1 },
        ],
      },
      {
        id: "table-1",
        rowCount: 1,
        columnCount: 1,
        cells: [{ rowIndex: 0, columnIndex: 0, rowSpan: 1, columnSpan: 1, kind: "content", content: "次", pageNumber: 2, confidence: 1 }],
      },
    ]),
    "tableId,column1,column2,column3\r\ntable-0,見出し,,C\r\ntable-0,A,,\"値,\"\"Q\"\"\"\r\ntable-1,次,,",
  );
});

test("未知のNormalized schemaを拒否する", () => {
  assert.throws(() => mapDocumentAnalysisViewV1({
    schemaVersion: 2,
    analysisId: job.id,
    provider: "DOCUMENT_INTELLIGENCE",
    modelId: "prebuilt-layout",
    providerApiVersion: "2024-11-30",
    status: "SUCCEEDED",
    documents: [],
  } as never));
});

test("source URLはBFF経由のDocument Analysis pathを返す", () => {
  assert.equal(
    documentAnalysisSourceUrl(job.id),
    `/api/backend/document-analyses/${job.id}/source`,
  );
});
