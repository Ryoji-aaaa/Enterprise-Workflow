import assert from "node:assert/strict";
import test from "node:test";

import { documentAnalysisFixture } from "./document-analysis-fixtures.ts";
import {
  DOCUMENT_ANALYSIS_MAX_FILE_SIZE_BYTES,
  DOCUMENT_ANALYSIS_PROVIDER_CONFIGS,
  documentAnalysisReducer,
  initialDocumentAnalysisState,
  validateDocumentFile,
  validateSingleDocumentSelection,
} from "./document-analysis.ts";

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

test("選択からfixture成功へ状態遷移する", () => {
  const file = { name: "order.pdf", type: "application/pdf", size: 2048 };
  const selected = documentAnalysisReducer(initialDocumentAnalysisState, {
    type: "select",
    file,
    validation: { valid: true },
  });

  assert.equal(selected.status, "selected");
  assert.deepEqual(selected.completedStatuses, ["selected"]);

  const succeeded = documentAnalysisReducer(selected, {
    type: "runFixture",
    result: documentAnalysisFixture("DOCUMENT_INTELLIGENCE"),
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

