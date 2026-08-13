import { AuthenticationRequiredError, fetchBackend } from "./backend-browser-client.ts";
import {
  expenseErrorMessage,
  type ExpenseApplication,
  type ExpenseStatus,
} from "./expense-application.ts";

type BackendFetch = (
  input: string | URL | Request,
  init?: RequestInit,
) => Promise<Response>;

type ErrorBody = {
  code?: string;
  message?: string;
};

export class ExpenseSubmitResultError extends Error {
  readonly resultUnknown: boolean;
  readonly retryable: boolean;

  constructor(message: string, options: { resultUnknown?: boolean; retryable?: boolean } = {}) {
    super(message);
    this.name = "ExpenseSubmitResultError";
    this.resultUnknown = options.resultUnknown === true;
    this.retryable = options.retryable === true;
  }
}

function isAmbiguousSubmitResponse(response: Response, code: string | undefined): boolean {
  return response.status === 503
    || code === "BACKEND_UNAVAILABLE"
    || (response.status === 409 && code === "EXPENSE_APPLICATION_INVALID_STATUS");
}

function isExpenseStatus(value: unknown): value is ExpenseStatus {
  return value === "DRAFT" || value === "RETURNED" || value === "PENDING_APPROVAL"
    || value === "APPROVED" || value === "CANCELLED";
}

async function reconcileSubmitResult(
  applicationId: string,
  fetchImplementation: BackendFetch,
): Promise<ExpenseApplication> {
  let readResponse: Response;
  try {
    readResponse = await fetchImplementation(
      `/api/backend/expense-applications/${applicationId}`,
      { cache: "no-store" },
    );
  } catch (cause) {
    if (cause instanceof AuthenticationRequiredError) throw cause;
    throw new ExpenseSubmitResultError(
      "申請結果を確認できませんでした。自動的な再申請は行っていません。申請一覧または詳細を確認してください。",
      { resultUnknown: true },
    );
  }
  if (!readResponse.ok) {
    throw new ExpenseSubmitResultError(
      "申請結果を確認できませんでした。自動的な再申請は行っていません。申請一覧または詳細を確認してください。",
      { resultUnknown: true },
    );
  }
  const current = (await readResponse.json().catch(() => ({}))) as Partial<ExpenseApplication>;
  if (!isExpenseStatus(current.status)) {
    throw new ExpenseSubmitResultError(
      "申請結果を確認できませんでした。自動的な再申請は行っていません。申請一覧または詳細を確認してください。",
      { resultUnknown: true },
    );
  }
  if (current.status === "DRAFT" || current.status === "RETURNED") {
    throw new ExpenseSubmitResultError(
      "申請は完了していません。最新の内容を確認してから、もう一度申請してください。",
      { retryable: true },
    );
  }
  return current as ExpenseApplication;
}

export async function submitExpenseApplicationWithReconciliation(
  applicationId: string,
  action: "submit" | "resubmit",
  fetchImplementation: BackendFetch = fetchBackend,
): Promise<ExpenseApplication> {
  const encodedId = encodeURIComponent(applicationId);
  let submitResponse: Response;
  try {
    submitResponse = await fetchImplementation(
      `/api/backend/expense-applications/${encodedId}/${action}`,
      { method: "POST" },
    );
  } catch (cause) {
    if (cause instanceof AuthenticationRequiredError) throw cause;
    return reconcileSubmitResult(encodedId, fetchImplementation);
  }
  const submitBody = (await submitResponse.json().catch(() => ({}))) as
    Partial<ExpenseApplication> & ErrorBody;
  if (submitResponse.ok) return submitBody as ExpenseApplication;
  if (!isAmbiguousSubmitResponse(submitResponse, submitBody.code)) {
    throw new ExpenseSubmitResultError(expenseErrorMessage(
      submitBody.code,
      submitBody.message ?? "申請できませんでした。下書きは保存されています。",
    ));
  }
  return reconcileSubmitResult(encodedId, fetchImplementation);
}
