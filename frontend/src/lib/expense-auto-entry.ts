import type {
  AutoEntryField,
  AutoEntryFieldStatus,
  AutoEntryReviewResponse,
} from "./auto-entry-review.ts";
import {
  totalExpenseAmount,
  type ExpenseCategory,
  type ExpenseItem,
} from "./expense-application.ts";

export type ExpenseAutoEntryItem = ExpenseItem & {
  sourceLineItemIndex: number | null;
};

export type ExpenseAutoEntryApplication = {
  category: ExpenseCategory;
  title: string;
  purpose: string;
  expenseDate: string;
  remarks: string;
  items: ExpenseAutoEntryItem[];
};

export type ExpenseAutoEntryDocument = {
  issuerName: string;
  issuerTaxRegistrationNumber: string;
  invoiceTotalAmount: number | null;
};

export type ExpenseAutoEntryForm = {
  application: ExpenseAutoEntryApplication;
  document: ExpenseAutoEntryDocument;
};

export type AutoEntryHumanResolution =
  | "NOT_REQUIRED"
  | "UNRESOLVED"
  | "CONFIRMED"
  | "EDITED";

export type AutoEntryTrackedField = {
  path: string;
  label: string;
  field: AutoEntryField<string | number>;
  currentValue: string | number | null;
  deleted: boolean;
};

export type ResolvedAutoEntryField = AutoEntryTrackedField & {
  resolution: AutoEntryHumanResolution;
};

export type CreateExpenseAutoEntryDraftRequest = {
  analysisId: string;
  application: ExpenseAutoEntryApplication;
  document: ExpenseAutoEntryDocument;
  confirmedFieldPaths: string[];
};

function emptyItem(expenseDate: string): ExpenseAutoEntryItem {
  return {
    sourceLineItemIndex: null,
    expenseDate,
    description: "",
    amount: 0,
    merchantName: "",
    origin: "",
    destination: "",
    transportationType: "",
    participants: "",
  };
}

export function createManualExpenseAutoEntryItem(expenseDate: string): ExpenseAutoEntryItem {
  return emptyItem(expenseDate);
}

export function initializeExpenseAutoEntryForm(
  review: AutoEntryReviewResponse,
  expenseDate: string,
): ExpenseAutoEntryForm {
  const lineItems = review.document.lineItems.value ?? [];
  const items = lineItems.length === 0
    ? [emptyItem(expenseDate)]
    : lineItems.map((lineItem, sourceLineItemIndex) => ({
      sourceLineItemIndex,
      expenseDate,
      description: lineItem.itemDescription.value ?? "",
      amount: lineItem.lineAmount.value ?? 0,
      merchantName: "",
      origin: "",
      destination: "",
      transportationType: "",
      participants: "",
    }));

  return {
    application: {
      category: "OTHER",
      title: "",
      purpose: "",
      expenseDate,
      remarks: "",
      items,
    },
    document: {
      issuerName: review.document.issuerName.value ?? "",
      issuerTaxRegistrationNumber: review.document.issuerTaxRegistrationNumber.value ?? "",
      invoiceTotalAmount: review.document.totalAmount.value,
    },
  };
}

function normalizeString(value: string | null): string | null {
  if (value === null) return null;
  const normalized = value.trim();
  return normalized === "" ? null : normalized;
}

export function areAutoEntryValuesEqual(
  original: string | number | null,
  current: string | number | null,
): boolean {
  if (typeof original === "string" && (typeof current === "string" || current === null)) {
    return normalizeString(original) === normalizeString(current);
  }
  if (original === null && (typeof current === "string" || current === null)) {
    return normalizeString(current) === null;
  }
  if (original === null && current === 0) {
    return true;
  }
  return original === current;
}

export function resolveAutoEntryField(
  field: Pick<AutoEntryField<string | number>, "value" | "status">,
  currentValue: string | number | null,
  confirmed: boolean,
  deleted = false,
): AutoEntryHumanResolution {
  if (deleted || !areAutoEntryValuesEqual(field.value, currentValue)) return "EDITED";
  if (field.status === "MISSING") return "UNRESOLVED";
  if (field.status === "OK") return "NOT_REQUIRED";
  return confirmed ? "CONFIRMED" : "UNRESOLVED";
}

function lineItemDescriptionPath(index: number): string {
  return `document.lineItems[${index}].itemDescription`;
}

function lineItemAmountPath(index: number): string {
  return `document.lineItems[${index}].lineAmount`;
}

function sourceItem(
  items: readonly ExpenseAutoEntryItem[],
  sourceLineItemIndex: number,
): ExpenseAutoEntryItem | undefined {
  return items.find((item) => item.sourceLineItemIndex === sourceLineItemIndex);
}

export function getTrackedAutoEntryFields(
  review: AutoEntryReviewResponse,
  form: ExpenseAutoEntryForm,
): AutoEntryTrackedField[] {
  const tracked: AutoEntryTrackedField[] = [
    {
      path: "document.issuerName",
      label: "請求社 / 発行元",
      field: review.document.issuerName,
      currentValue: form.document.issuerName,
      deleted: false,
    },
    {
      path: "document.issuerTaxRegistrationNumber",
      label: "インボイス登録番号",
      field: review.document.issuerTaxRegistrationNumber,
      currentValue: form.document.issuerTaxRegistrationNumber,
      deleted: false,
    },
    {
      path: "document.totalAmount",
      label: "総請求額",
      field: review.document.totalAmount,
      currentValue: form.document.invoiceTotalAmount,
      deleted: false,
    },
  ];

  for (const [sourceLineItemIndex, lineItem] of (review.document.lineItems.value ?? []).entries()) {
    const currentItem = sourceItem(form.application.items, sourceLineItemIndex);
    tracked.push(
      {
        path: lineItemDescriptionPath(sourceLineItemIndex),
        label: `明細 ${sourceLineItemIndex + 1}・品名`,
        field: lineItem.itemDescription,
        currentValue: currentItem?.description ?? null,
        deleted: currentItem === undefined,
      },
      {
        path: lineItemAmountPath(sourceLineItemIndex),
        label: `明細 ${sourceLineItemIndex + 1}・金額`,
        field: lineItem.lineAmount,
        currentValue: currentItem?.amount ?? null,
        deleted: currentItem === undefined,
      },
    );
  }
  return tracked;
}

export function getResolvedAutoEntryFields(
  review: AutoEntryReviewResponse,
  form: ExpenseAutoEntryForm,
  confirmedPaths: ReadonlySet<string>,
): ResolvedAutoEntryField[] {
  return getTrackedAutoEntryFields(review, form).map((field) => ({
    ...field,
    resolution: resolveAutoEntryField(
      field.field,
      field.currentValue,
      confirmedPaths.has(field.path),
      field.deleted,
    ),
  }));
}

export function getConfirmedAutoEntryFieldPaths(
  resolvedFields: readonly ResolvedAutoEntryField[],
): string[] {
  return resolvedFields
    .filter((field) => field.field.status === "REVIEW" && field.resolution === "CONFIRMED")
    .map((field) => field.path);
}

export function getAutoEntryAttention(
  resolvedFields: readonly ResolvedAutoEntryField[],
): ResolvedAutoEntryField[] {
  return resolvedFields.filter((field) => field.resolution === "UNRESOLVED");
}

export function shouldShowAutoEntryField(
  field: ResolvedAutoEntryField,
  showAttentionOnly: boolean,
): boolean {
  return !showAttentionOnly || field.resolution === "UNRESOLVED";
}

export function hasInvoiceTotalMismatch(
  invoiceTotalAmount: number | null,
  items: readonly ExpenseItem[],
): boolean {
  return invoiceTotalAmount !== null && invoiceTotalAmount !== totalExpenseAmount([...items]);
}

export function createExpenseAutoEntryDraftRequest(
  analysisId: string,
  form: ExpenseAutoEntryForm,
  resolvedFields: readonly ResolvedAutoEntryField[],
): CreateExpenseAutoEntryDraftRequest {
  return {
    analysisId,
    application: {
      ...form.application,
      items: form.application.items.map((item) => ({ ...item })),
    },
    document: { ...form.document },
    confirmedFieldPaths: getConfirmedAutoEntryFieldPaths(resolvedFields),
  };
}

export function autoEntryStatusLabel(status: AutoEntryFieldStatus): string {
  if (status === "OK") return "OK";
  if (status === "REVIEW") return "要確認";
  return "未取得";
}
