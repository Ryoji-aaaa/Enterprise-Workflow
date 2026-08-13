import { fetchBackend } from "./backend-browser-client.ts";
import { expenseErrorMessage } from "./expense-application.ts";
import type { CreateExpenseAutoEntryDraftRequest } from "./expense-auto-entry.ts";

type ErrorBody = {
  code?: string;
};

export type ExpenseAutoEntryDraftResponse = {
  application: {
    id: string;
  };
};

export class ExpenseAutoEntryApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "ExpenseAutoEntryApiError";
    this.status = status;
    this.code = code;
  }
}

export function expenseAutoEntrySafeErrorMessage(status: number, code: string): string {
  if (status === 403) return "この自動入力機能を利用する権限がありません。";
  if (status === 404 || code === "DOCUMENT_ANALYSIS_NOT_FOUND") {
    return "自動入力結果が見つかりません。文書を読み込み直してください。";
  }
  if (status === 410 || code === "DOCUMENT_ANALYSIS_EXPIRED") {
    return "自動入力結果の保持期限が切れています。文書を読み込み直してください。";
  }
  if (status === 503 || code === "BACKEND_UNAVAILABLE") {
    return "現在、サービスを利用できません。しばらくしてからもう一度お試しください。";
  }
  return expenseErrorMessage(code, "自動入力の経費下書きを作成できませんでした。入力内容を確認してもう一度お試しください。");
}

export async function createExpenseAutoEntryDraft(
  request: CreateExpenseAutoEntryDraftRequest,
): Promise<ExpenseAutoEntryDraftResponse> {
  const response = await fetchBackend("/api/backend/expense-applications/from-auto-entry", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
  if (response.ok) return (await response.json()) as ExpenseAutoEntryDraftResponse;

  const error = (await response.json().catch(() => ({}))) as ErrorBody;
  const code = error.code ?? "EXPENSE_AUTO_ENTRY_REQUEST_FAILED";
  throw new ExpenseAutoEntryApiError(
    response.status,
    code,
    expenseAutoEntrySafeErrorMessage(response.status, code),
  );
}
