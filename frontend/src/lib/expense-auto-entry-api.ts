import { fetchBackend } from "./backend-browser-client.ts";
import { expenseErrorMessage } from "./expense-application.ts";
import type {
  AutoEntryAdjustment,
  AutoEntryDerivedField,
  AutoEntryField,
} from "./auto-entry-review.ts";
import type {
  AutoEntryHumanResolution,
  CreateExpenseAutoEntryDraftRequest,
  ExpenseAutoEntryApplication,
  ExpenseAutoEntryDocument,
} from "./expense-auto-entry.ts";

type ErrorBody = {
  code?: string;
};

type BackendFetch = (
  input: string | URL | Request,
  init?: RequestInit,
) => Promise<Response>;

export type ExpenseAutoEntryDraftItem = {
  id: string;
  displayOrder: number;
  sourceLineItemIndex: number | null;
  expenseDate: string;
  description: string;
  amount: number;
  merchantName: string;
  origin: string;
  destination: string;
  transportationType: string;
  participants: string;
};

export type ExpenseAutoEntryDraftApplication = {
  id: string;
  applicationNumber: string;
  category: ExpenseAutoEntryApplication["category"];
  title: string;
  purpose: string;
  expenseDate: string;
  totalAmount: number;
  currencyCode: "JPY";
  remarks: string | null;
  status: "DRAFT" | "RETURNED" | "PENDING_APPROVAL" | "APPROVED" | "CANCELLED";
  version: number;
  items: ExpenseAutoEntryDraftItem[];
};

export type ExpenseAutoEntryOriginalLineItem = {
  sourceLineItemIndex: number;
  itemDescription: AutoEntryField<string>;
  lineAmount: AutoEntryField<number>;
};

export type ExpenseAutoEntryOriginal = {
  issuerName: AutoEntryField<string>;
  issuerTaxRegistrationNumber: AutoEntryField<string>;
  invoiceTotalAmount: AutoEntryField<number>;
  taxAmount?: AutoEntryField<number>;
  taxMode?: AutoEntryDerivedField<"TAX_INCLUDED" | "TAX_EXCLUDED" | "UNKNOWN">;
  adjustments?: AutoEntryField<AutoEntryAdjustment[]>;
  lineItems: ExpenseAutoEntryOriginalLineItem[];
};

export type ExpenseAutoEntryFieldState = {
  resolution: AutoEntryHumanResolution;
};

export type ExpenseAutoEntryPersistedDocument = {
  issuerName: string | null;
  issuerTaxRegistrationNumber: string | null;
  invoiceTotalAmount: number | null;
};

export type ExpenseAutoEntryDraftContext = {
  analysisId: string;
  contextVersion: number;
  contextSchemaVersion: number;
  sourceAttachmentId: string;
  schemaVersion: string;
  original: ExpenseAutoEntryOriginal;
  currentDocument: ExpenseAutoEntryPersistedDocument;
  fields: Record<string, ExpenseAutoEntryFieldState>;
  unresolvedCount: number;
  warnings: Array<"INVOICE_TOTAL_DIFFERS_FROM_DRAFT_TOTAL">;
};

export type ExpenseAutoEntryDraftResponse = {
  application: ExpenseAutoEntryDraftApplication;
  autoEntry: ExpenseAutoEntryDraftContext;
};

export type UpdateExpenseAutoEntryDraftRequest = {
  applicationVersion: number;
  contextVersion: number;
  application: ExpenseAutoEntryApplication;
  document: ExpenseAutoEntryDocument;
  confirmedFieldPaths: string[];
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

export function expenseAutoEntrySafeErrorMessage(
  status: number,
  code: string,
  fallback = "自動入力の経費下書きを作成できませんでした。入力内容を確認してもう一度お試しください。",
): string {
  if (status === 403) return "この自動入力機能を利用する権限がありません。";
  if (code === "DOCUMENT_ANALYSIS_NOT_FOUND") {
    return "自動入力結果が見つかりません。文書を読み込み直してください。";
  }
  if (status === 404 || code === "EXPENSE_AUTO_ENTRY_DRAFT_NOT_FOUND") {
    return "自動入力の経費下書きが見つかりません。";
  }
  if (status === 409 && code === "OPTIMISTIC_LOCK_CONFLICT") {
    return "他の更新と競合しました。最新内容を再読み込みしてください。";
  }
  if (status === 409 && code === "EXPENSE_AUTO_ENTRY_DRAFT_NOT_EDITABLE") {
    return "この経費申請は現在編集できません。";
  }
  if (status === 410 || code === "DOCUMENT_ANALYSIS_EXPIRED") {
    return "自動入力結果の保持期限が切れています。文書を読み込み直してください。";
  }
  if (status === 503 || code === "BACKEND_UNAVAILABLE") {
    return "現在、サービスを利用できません。しばらくしてからもう一度お試しください。";
  }
  return expenseErrorMessage(code, fallback);
}

async function readAutoEntryError(response: Response, fallback: string): Promise<never> {
  const error = (await response.json().catch(() => ({}))) as ErrorBody;
  const code = error.code ?? "EXPENSE_AUTO_ENTRY_REQUEST_FAILED";
  throw new ExpenseAutoEntryApiError(
    response.status,
    code,
    expenseAutoEntrySafeErrorMessage(response.status, code, fallback),
  );
}

export async function createExpenseAutoEntryDraft(
  request: CreateExpenseAutoEntryDraftRequest,
  fetchImplementation: BackendFetch = fetchBackend,
): Promise<ExpenseAutoEntryDraftResponse> {
  const response = await fetchImplementation("/api/backend/expense-applications/from-auto-entry", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
  if (response.ok) return (await response.json()) as ExpenseAutoEntryDraftResponse;

  return readAutoEntryError(response, "自動入力の経費下書きを作成できませんでした。");
}

export async function getExpenseAutoEntryDraft(
  applicationId: string,
  signal?: AbortSignal,
  fetchImplementation: BackendFetch = fetchBackend,
): Promise<ExpenseAutoEntryDraftResponse> {
  const response = await fetchImplementation(
    `/api/backend/expense-applications/${encodeURIComponent(applicationId)}/auto-entry-draft`,
    { cache: "no-store", signal },
  );
  if (response.ok) return (await response.json()) as ExpenseAutoEntryDraftResponse;
  return readAutoEntryError(response, "申請内容を読み込めませんでした。");
}

export async function updateExpenseAutoEntryDraft(
  applicationId: string,
  request: UpdateExpenseAutoEntryDraftRequest,
  fetchImplementation: BackendFetch = fetchBackend,
): Promise<ExpenseAutoEntryDraftResponse> {
  const response = await fetchImplementation(
    `/api/backend/expense-applications/${encodeURIComponent(applicationId)}/auto-entry-draft`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(request),
    },
  );
  if (response.ok) return (await response.json()) as ExpenseAutoEntryDraftResponse;
  return readAutoEntryError(response, "自動入力の経費下書きを保存できませんでした。");
}
