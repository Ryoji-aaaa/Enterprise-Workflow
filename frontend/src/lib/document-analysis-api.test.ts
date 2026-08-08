import assert from "node:assert/strict";
import test from "node:test";

import {
  documentAnalysisSafeErrorMessage,
  DocumentAnalysisApiError,
  documentAnalysisSourceUrl,
  formatDocumentAnalysisRawResult,
  RAW_RESULT_PRETTY_PRINT_MAX_BYTES,
} from "./document-analysis-api.ts";

const ANALYSIS_ID = "123e4567-e89b-42d3-a456-426614174000";

test("Document Analysis source URLはBFF経由だけを返す", () => {
  assert.equal(
    documentAnalysisSourceUrl(ANALYSIS_ID),
    `/api/backend/document-analyses/${ANALYSIS_ID}/source`,
  );
});

test("Document Analysis API errorはHTTP status、code、安全なmessageを保持する", () => {
  const error = new DocumentAnalysisApiError(
    413,
    "DOCUMENT_ANALYSIS_TOO_LARGE",
    documentAnalysisSafeErrorMessage(
      413,
      "DOCUMENT_ANALYSIS_TOO_LARGE",
      "internal fallback",
    ),
  );

  assert.equal(error.status, 413);
  assert.equal(error.code, "DOCUMENT_ANALYSIS_TOO_LARGE");
  assert.equal(error.message, "ファイルサイズが上限を超えています。");
});

test("Document Analysis API error messageは利用者向けの文言に正規化する", () => {
  assert.equal(
    documentAnalysisSafeErrorMessage(403, "DOCUMENT_ANALYSIS_PROVIDER_FORBIDDEN", "forbidden"),
    "この分析機能を利用する権限がありません。",
  );
  assert.equal(
    documentAnalysisSafeErrorMessage(429, "DOCUMENT_ANALYSIS_CONCURRENCY_LIMIT", "rate"),
    "同時に実行できる分析要求数の上限に達しています。",
  );
  assert.equal(
    documentAnalysisSafeErrorMessage(429, "DOCUMENT_ANALYSIS_RATE_LIMIT", "rate"),
    "分析要求の回数上限に達しています。",
  );
  assert.equal(
    documentAnalysisSafeErrorMessage(503, "BACKEND_UNAVAILABLE", "internal"),
    "現在、分析サービスを利用できません。",
  );
});

test("Raw Resultは小さいJSONだけpretty printする", () => {
  const raw = formatDocumentAnalysisRawResult("{\"source\":\"backend-fake-provider\",\"value\":1}");

  assert.equal(raw.formatted, true);
  assert.equal(raw.byteLength, 44);
  assert.equal(raw.text, "{\n  \"source\": \"backend-fake-provider\",\n  \"value\": 1\n}");
});

test("Raw Resultは大きいJSONをparseせず全文を保持する", () => {
  const largeText = `{"payload":"${"x".repeat(RAW_RESULT_PRETTY_PRINT_MAX_BYTES)}"}`;
  const raw = formatDocumentAnalysisRawResult(largeText);

  assert.equal(raw.formatted, false);
  assert.equal(raw.text, largeText);
  assert.equal(raw.text.length, largeText.length);
  assert.ok(raw.byteLength > RAW_RESULT_PRETTY_PRINT_MAX_BYTES);
});
