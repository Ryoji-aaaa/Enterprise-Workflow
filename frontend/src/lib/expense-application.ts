export const expenseCategories = [
  "MEAL",
  "TRANSPORTATION",
  "TRAINING",
  "CERTIFICATION",
  "OTHER",
] as const;

export type ExpenseCategory = (typeof expenseCategories)[number];
export type ExpenseStatus =
  | "DRAFT"
  | "PENDING_APPROVAL"
  | "RETURNED"
  | "APPROVED"
  | "CANCELLED";

export const categoryLabels: Record<ExpenseCategory, string> = {
  MEAL: "会食費",
  TRANSPORTATION: "交通費",
  TRAINING: "研修費",
  CERTIFICATION: "資格受験費",
  OTHER: "その他経費",
};

export const statusLabels: Record<ExpenseStatus, string> = {
  DRAFT: "下書き",
  PENDING_APPROVAL: "承認待ち",
  RETURNED: "差戻し",
  APPROVED: "承認済み",
  CANCELLED: "取下げ",
};

export type ExpenseItem = {
  id?: string;
  displayOrder?: number;
  expenseDate: string;
  description: string;
  amount: number;
  merchantName: string;
  origin: string;
  destination: string;
  transportationType: string;
  participants: string;
};

export type ExpenseApplication = {
  id: string;
  applicationNumber: string;
  applicantUserId: string;
  applicantName: string;
  applicantEmail: string;
  organizationUnitName: string;
  divisionUnitName: string;
  category: ExpenseCategory;
  title: string;
  purpose: string;
  expenseDate: string;
  totalAmount: number;
  currencyCode: "JPY";
  remarks: string | null;
  status: ExpenseStatus;
  submittedAt: string | null;
  approvedAt: string | null;
  returnedAt: string | null;
  cancelledAt: string | null;
  returnReason: string | null;
  version: number;
  editable: boolean;
  cancellable: boolean;
  pendingStepId: string | null;
  canApprove: boolean;
  items: ExpenseItem[];
  approvalRun: {
    runNumber: number;
    status: string;
    startedAt: string;
    steps: Array<{
      id: string;
      order: number;
      type: "DEPARTMENT_MANAGER" | "ACCOUNTING";
      targetOrganizationUnitName: string;
      status: string;
      processedBy: string | null;
      processedAt: string | null;
      comment: string | null;
    }>;
  } | null;
};

export type ExpenseSummary = Pick<
  ExpenseApplication,
  "id" | "applicationNumber" | "applicantName" | "category" | "title"
  | "totalAmount" | "currencyCode" | "status" | "submittedAt"
> & { createdAt: string };

export type ExpensePage = {
  content: ExpenseSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export function canShowExpenseApprovalActions(
  application: Pick<ExpenseApplication, "canApprove" | "pendingStepId">,
): boolean {
  return application.canApprove && application.pendingStepId !== null;
}

export function totalExpenseAmount(items: ExpenseItem[]): number {
  return items.reduce((total, item) => total + (Number.isFinite(item.amount) ? item.amount : 0), 0);
}

export function isExpenseInputValid(
  category: ExpenseCategory,
  title: string,
  purpose: string,
  expenseDate: string,
  items: ExpenseItem[],
): boolean {
  if (!title.trim() || !purpose.trim() || !expenseDate || items.length === 0) return false;
  return items.every((item) => {
    if (!item.expenseDate || !item.description.trim()
        || !Number.isInteger(item.amount) || item.amount <= 0) return false;
    if (category === "MEAL") {
      return Boolean(item.merchantName?.trim() && item.participants?.trim());
    }
    if (category === "TRANSPORTATION") {
      return Boolean(item.transportationType?.trim() && item.origin?.trim()
        && item.destination?.trim());
    }
    if (category === "TRAINING" || category === "CERTIFICATION") {
      return Boolean(item.merchantName?.trim());
    }
    return true;
  });
}

export function categoryFieldLabels(category: ExpenseCategory): string[] {
  switch (category) {
    case "MEAL": return ["店舗名", "参加者"];
    case "TRANSPORTATION": return ["交通手段", "出発地", "到着地"];
    case "TRAINING": return ["研修名", "主催者", "開催日"];
    case "CERTIFICATION": return ["資格名", "試験実施団体", "受験予定日または受験日"];
    case "OTHER": return ["内容"];
  }
}

const errorMessages: Record<string, string> = {
  PRIMARY_ASSIGNMENT_NOT_FOUND: "有効な主所属が登録されていないため申請できません。",
  DIVISION_NOT_FOUND: "所属事業部を特定できないため申請できません。",
  DEPARTMENT_MANAGER_NOT_FOUND: "部門承認者が登録されていないため申請できません。",
  ACCOUNTING_UNIT_NOT_FOUND: "経理課が登録されていないため申請できません。",
  ACCOUNTING_APPROVER_NOT_FOUND: "経理承認者が登録されていないため申請できません。",
  EXPENSE_APPLICATION_CATEGORY_FIELD_REQUIRED: "カテゴリ別の必須項目を入力してください。",
  APPROVAL_STEP_NOT_PENDING: "この承認は既に処理されています。最新情報を再読込してください。",
  APPROVAL_NOT_ALLOWED: "この申請を承認する権限がありません。",
  SELF_APPROVAL_NOT_ALLOWED: "自分自身の申請は承認できません。",
  RETURN_REASON_REQUIRED: "差戻し理由を入力してください。",
  OPTIMISTIC_LOCK_CONFLICT: "他の更新と競合しました。最新情報を再読込してください。",
  EXPENSE_AUTO_ENTRY_SOURCE_MAPPING_INVALID: "自動入力結果と経費明細の対応が不正です。文書を読み込み直してください。",
  EXPENSE_AUTO_ENTRY_CURRENCY_UNSUPPORTED: "JPY以外の文書は経費申請へ変換できません。",
  DOCUMENT_ANALYSIS_NOT_FOUND: "自動入力結果が見つかりません。文書を読み込み直してください。",
  DOCUMENT_ANALYSIS_EXPIRED: "自動入力結果の保持期限が切れています。文書を読み込み直してください。",
  DOCUMENT_ANALYSIS_RESULT_NOT_READY: "分析結果の準備が完了していません。しばらくしてからもう一度お試しください。",
  DOCUMENT_ANALYSIS_STORAGE_UNAVAILABLE: "自動入力結果を現在読み込めません。しばらくしてからもう一度お試しください。",
  EXPENSE_ATTACHMENT_STORAGE_UNAVAILABLE: "証憑を現在保存できません。しばらくしてからもう一度お試しください。",
  BACKEND_UNAVAILABLE: "現在、サービスを利用できません。しばらくしてからもう一度お試しください。",
};

export function expenseErrorMessage(code: string | undefined, fallback: string): string {
  return code ? (errorMessages[code] ?? fallback) : fallback;
}

export function yen(amount: number): string {
  return new Intl.NumberFormat("ja-JP", { style: "currency", currency: "JPY", maximumFractionDigits: 0 }).format(amount);
}
