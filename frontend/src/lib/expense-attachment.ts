export const MAX_EXPENSE_ATTACHMENT_BYTES = 10 * 1024 * 1024;
export const MAX_EXPENSE_ATTACHMENTS = 10;
export const MAX_EXPENSE_ATTACHMENT_TOTAL_BYTES = 30 * 1024 * 1024;
export const EXPENSE_ATTACHMENT_ACCEPT =
  ".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png";

const allowedExtensions = new Set(["pdf", "jpg", "jpeg", "png"]);
const allowedContentTypes = new Set(["application/pdf", "image/jpeg", "image/png"]);

export type ExpenseAttachment = {
  id: string;
  originalFileName: string;
  contentType: "application/pdf" | "image/jpeg" | "image/png";
  fileSize: number;
  uploadedByName: string;
  uploadedAt: string;
  previewable: boolean;
  deletable: boolean;
};

export function canEditExpenseAttachments(status: string, owner: boolean): boolean {
  return owner && (status === "DRAFT" || status === "RETURNED");
}

export function expenseAttachmentFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}

export function expenseAttachmentTypeLabel(contentType: ExpenseAttachment["contentType"]): string {
  if (contentType === "application/pdf") return "PDF";
  if (contentType === "image/jpeg") return "JPEG";
  return "PNG";
}

export function isPreviewableExpenseAttachment(contentType: string): boolean {
  return allowedContentTypes.has(contentType);
}

export function validateExpenseAttachmentFile(
  file: Pick<File, "name" | "size" | "type">,
  current: readonly Pick<ExpenseAttachment, "fileSize">[],
): string | null {
  const extension = file.name.includes(".")
    ? file.name.slice(file.name.lastIndexOf(".") + 1).toLowerCase()
    : "";
  if (!allowedExtensions.has(extension) || !allowedContentTypes.has(file.type.toLowerCase())) {
    return "対応していない形式です。PDF、JPEG、PNGを選択してください。";
  }
  if (file.size <= 0) return "空のファイルは添付できません。";
  if (file.size > MAX_EXPENSE_ATTACHMENT_BYTES) {
    return "ファイルが大きすぎます。1ファイル10 MiB以下にしてください。";
  }
  if (current.length >= MAX_EXPENSE_ATTACHMENTS) {
    return "添付件数の上限（10件）に達しています。";
  }
  const currentSize = current.reduce((total, attachment) => total + attachment.fileSize, 0);
  if (currentSize + file.size > MAX_EXPENSE_ATTACHMENT_TOTAL_BYTES) {
    return "添付ファイルの合計サイズ上限（30 MiB）を超えています。";
  }
  return null;
}

const errorMessages: Record<string, string> = {
  EXPENSE_ATTACHMENT_REQUIRED: "添付ファイルを選択してください。",
  EXPENSE_ATTACHMENT_EMPTY: "空のファイルは添付できません。",
  EXPENSE_ATTACHMENT_TOO_LARGE: "ファイルが大きすぎます。1ファイル10 MiB以下にしてください。",
  EXPENSE_ATTACHMENT_INVALID_FILE_NAME: "ファイル名が不正です。",
  EXPENSE_ATTACHMENT_UNSUPPORTED_EXTENSION: "対応していない拡張子です。",
  EXPENSE_ATTACHMENT_UNSUPPORTED_MEDIA_TYPE: "対応していない形式です。",
  EXPENSE_ATTACHMENT_MAGIC_NUMBER_MISMATCH: "ファイル内容と拡張子が一致しません。",
  EXPENSE_ATTACHMENT_NOT_EDITABLE: "現在の申請状態では添付ファイルを変更できません。",
  EXPENSE_ATTACHMENT_COUNT_EXCEEDED: "添付件数の上限（10件）に達しています。",
  EXPENSE_ATTACHMENT_TOTAL_SIZE_EXCEEDED: "添付ファイルの合計サイズ上限（30 MiB）を超えています。",
  EXPENSE_ATTACHMENT_STORAGE_UNAVAILABLE: "添付ファイルのストレージへ接続できません。",
};

export function expenseAttachmentErrorMessage(
  code: string | undefined,
  fallback: string,
): string {
  return code ? (errorMessages[code] ?? fallback) : fallback;
}
