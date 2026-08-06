import assert from "node:assert/strict";
import test from "node:test";

import {
  canEditExpenseAttachments,
  expenseAttachmentErrorMessage,
  expenseAttachmentFileSize,
  isPreviewableExpenseAttachment,
  MAX_EXPENSE_ATTACHMENT_BYTES,
  validateExpenseAttachmentFile,
} from "./expense-attachment.ts";

test("所有者だけが下書きと差戻しで添付を変更できる", () => {
  assert.equal(canEditExpenseAttachments("DRAFT", true), true);
  assert.equal(canEditExpenseAttachments("RETURNED", true), true);
  assert.equal(canEditExpenseAttachments("PENDING_APPROVAL", true), false);
  assert.equal(canEditExpenseAttachments("APPROVED", true), false);
  assert.equal(canEditExpenseAttachments("DRAFT", false), false);
});

test("ファイルサイズとpreview可否を表示用に変換する", () => {
  assert.equal(expenseAttachmentFileSize(42), "42 B");
  assert.equal(expenseAttachmentFileSize(1536), "1.5 KiB");
  assert.equal(expenseAttachmentFileSize(2 * 1024 * 1024), "2.0 MiB");
  assert.equal(isPreviewableExpenseAttachment("application/pdf"), true);
  assert.equal(isPreviewableExpenseAttachment("image/png"), true);
  assert.equal(isPreviewableExpenseAttachment("text/html"), false);
});

test("拡張子MIMEサイズ件数合計サイズをクライアントで事前検査する", () => {
  assert.equal(validateExpenseAttachmentFile(
    { name: "receipt.PDF", type: "application/pdf", size: 1024 }, [],
  ), null);
  assert.match(validateExpenseAttachmentFile(
    { name: "receipt.svg", type: "image/svg+xml", size: 1024 }, [],
  ) ?? "", /対応していない/);
  assert.match(validateExpenseAttachmentFile(
    { name: "receipt.pdf", type: "application/pdf", size: MAX_EXPENSE_ATTACHMENT_BYTES + 1 }, [],
  ) ?? "", /大きすぎ/);
  assert.match(validateExpenseAttachmentFile(
    { name: "receipt.pdf", type: "application/pdf", size: 1 },
    Array.from({ length: 10 }, () => ({ fileSize: 1 })),
  ) ?? "", /件数/);
  assert.match(validateExpenseAttachmentFile(
    { name: "receipt.pdf", type: "application/pdf", size: 1 },
    [{ fileSize: 30 * 1024 * 1024 }],
  ) ?? "", /合計サイズ/);
});

test("添付エラーコードを日本語へ変換する", () => {
  assert.match(expenseAttachmentErrorMessage(
    "EXPENSE_ATTACHMENT_MAGIC_NUMBER_MISMATCH", "fallback",
  ), /ファイル内容/);
  assert.match(expenseAttachmentErrorMessage(
    "EXPENSE_ATTACHMENT_STORAGE_UNAVAILABLE", "fallback",
  ), /ストレージ/);
  assert.equal(expenseAttachmentErrorMessage("UNKNOWN", "fallback"), "fallback");
});
