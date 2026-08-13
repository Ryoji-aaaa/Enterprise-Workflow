import type {
  AutoEntryAdjustment,
  AutoEntryDerivedField,
  AutoEntryField,
  AutoEntryFieldStatus,
  AutoEntryReviewResponse,
} from "./auto-entry-review.ts";
import {
  totalExpenseAmount,
  type ExpenseCategory,
  type ExpenseItem,
  yen,
} from "./expense-application.ts";
import type {
  ExpenseAutoEntryDraftResponse,
  ExpenseAutoEntryOriginal,
  UpdateExpenseAutoEntryDraftRequest,
} from "./expense-auto-entry-api.ts";

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

export type ExpenseAutoEntryReviewSource = {
  issuerName: AutoEntryField<string>;
  issuerTaxRegistrationNumber: AutoEntryField<string>;
  invoiceTotalAmount: AutoEntryField<number>;
  taxAmount: AutoEntryField<number>;
  taxMode: AutoEntryDerivedField<"TAX_INCLUDED" | "TAX_EXCLUDED" | "UNKNOWN">;
  adjustments: AutoEntryField<AutoEntryAdjustment[]>;
  lineItems: Array<{
    sourceLineItemIndex: number;
    itemDescription: AutoEntryField<string>;
    lineAmount: AutoEntryField<number>;
  }>;
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

export function liveAutoEntryReviewToSource(
  review: AutoEntryReviewResponse,
): ExpenseAutoEntryReviewSource {
  return {
    issuerName: review.document.issuerName,
    issuerTaxRegistrationNumber: review.document.issuerTaxRegistrationNumber,
    invoiceTotalAmount: review.document.totalAmount,
    taxAmount: review.document.taxAmount,
    taxMode: review.taxMode,
    adjustments: review.document.adjustments,
    lineItems: (review.document.lineItems.value ?? []).map((item, sourceLineItemIndex) => ({
      sourceLineItemIndex,
      itemDescription: item.itemDescription,
      lineAmount: item.lineAmount,
    })),
  };
}

export function persistedAutoEntryOriginalToSource(
  original: ExpenseAutoEntryOriginal,
): ExpenseAutoEntryReviewSource {
  return {
    issuerName: original.issuerName,
    issuerTaxRegistrationNumber: original.issuerTaxRegistrationNumber,
    invoiceTotalAmount: original.invoiceTotalAmount,
    taxAmount: original.taxAmount ?? {
      value: null,
      confidence: null,
      status: "MISSING",
      sources: [],
      findings: [],
    },
    taxMode: original.taxMode ?? {
      value: "UNKNOWN",
      status: "MISSING",
      findings: [],
    },
    adjustments: original.adjustments ?? {
      value: null,
      confidence: null,
      status: "MISSING",
      sources: [],
      findings: [],
    },
    lineItems: original.lineItems.map((item) => ({ ...item })),
  };
}

export function persistedExpenseAutoEntryDraftToForm(
  draft: ExpenseAutoEntryDraftResponse,
): ExpenseAutoEntryForm {
  return {
    application: {
      category: draft.application.category,
      title: draft.application.title,
      purpose: draft.application.purpose,
      expenseDate: draft.application.expenseDate,
      remarks: draft.application.remarks ?? "",
      items: draft.application.items.map((item) => ({
        sourceLineItemIndex: item.sourceLineItemIndex,
        expenseDate: item.expenseDate,
        description: item.description,
        amount: item.amount,
        merchantName: item.merchantName,
        origin: item.origin,
        destination: item.destination,
        transportationType: item.transportationType,
        participants: item.participants,
      })),
    },
    document: {
      issuerName: draft.autoEntry.currentDocument.issuerName ?? "",
      issuerTaxRegistrationNumber: draft.autoEntry.currentDocument.issuerTaxRegistrationNumber ?? "",
      invoiceTotalAmount: draft.autoEntry.currentDocument.invoiceTotalAmount,
    },
  };
}

export function confirmedAutoEntryFieldPaths(
  fields: Record<string, { resolution: AutoEntryHumanResolution }>,
): Set<string> {
  return new Set(Object.entries(fields)
    .filter(([, field]) => field.resolution === "CONFIRMED")
    .map(([path]) => path));
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
  source: ExpenseAutoEntryReviewSource,
  form: ExpenseAutoEntryForm,
): AutoEntryTrackedField[] {
  const tracked: AutoEntryTrackedField[] = [
    {
      path: "document.issuerName",
      label: "請求社 / 発行元",
      field: source.issuerName,
      currentValue: form.document.issuerName,
      deleted: false,
    },
    {
      path: "document.issuerTaxRegistrationNumber",
      label: "インボイス登録番号",
      field: source.issuerTaxRegistrationNumber,
      currentValue: form.document.issuerTaxRegistrationNumber,
      deleted: false,
    },
    {
      path: "document.totalAmount",
      label: "総請求額",
      field: source.invoiceTotalAmount,
      currentValue: form.document.invoiceTotalAmount,
      deleted: false,
    },
  ];

  for (const lineItem of source.lineItems) {
    const sourceLineItemIndex = lineItem.sourceLineItemIndex;
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
  source: ExpenseAutoEntryReviewSource | AutoEntryReviewResponse,
  form: ExpenseAutoEntryForm,
  confirmedPaths: ReadonlySet<string>,
): ResolvedAutoEntryField[] {
  const normalizedSource = "document" in source ? liveAutoEntryReviewToSource(source) : source;
  return getTrackedAutoEntryFields(normalizedSource, form).map((field) => ({
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
  return !showAttentionOnly || field.field.status !== "OK";
}

export type ExpenseAutoEntryInvoiceTotalReconciliationStatus =
  | "MATCHED"
  | "MISMATCH"
  | "UNAVAILABLE";

export function isSafeExpenseAutoEntryAdjustment(
  adjustment: AutoEntryAdjustment,
): boolean {
  return adjustment.normalizedSignedAmount.value !== null
    && adjustment.normalizedSignedAmount.status === "OK";
}

export function reconcileExpenseAutoEntryInvoiceTotal(
  invoiceTotalAmount: number | null,
  items: readonly ExpenseItem[],
  taxAmount: AutoEntryField<number>,
  adjustments: AutoEntryField<AutoEntryAdjustment[]>,
  taxMode: AutoEntryDerivedField<"TAX_INCLUDED" | "TAX_EXCLUDED" | "UNKNOWN">,
): ExpenseAutoEntryInvoiceTotalReconciliationStatus {
  if (invoiceTotalAmount === null) return "UNAVAILABLE";

  const draftLineTotal = totalExpenseAmount([...items]);
  const adjustmentValues = adjustments.value?.map(
    (adjustment) => isSafeExpenseAutoEntryAdjustment(adjustment)
      ? adjustment.normalizedSignedAmount.value
      : null,
  );
  const adjustmentAvailable = adjustmentValues !== undefined
    && adjustmentValues.every((value): value is number => value !== null);
  const adjustmentTotal = adjustmentAvailable
    ? adjustmentValues.reduce((total, value) => total + value, 0)
    : null;

  const withoutTax = [draftLineTotal];
  if (adjustmentTotal !== null) withoutTax.push(draftLineTotal + adjustmentTotal);
  const withTax: number[] = [];
  if (taxAmount.value !== null) {
    withTax.push(draftLineTotal + taxAmount.value);
    if (adjustmentTotal !== null) {
      withTax.push(draftLineTotal + taxAmount.value + adjustmentTotal);
    }
  }
  const candidates = taxMode.value === "TAX_EXCLUDED"
    ? [...withTax, ...withoutTax]
    : [...withoutTax, ...withTax];
  if (candidates.some((candidate) => Math.abs(invoiceTotalAmount - candidate) <= 1)) {
    return "MATCHED";
  }
  return taxAmount.value === null || adjustmentTotal === null ? "UNAVAILABLE" : "MISMATCH";
}

export function formatExpenseAutoEntryTaxAmount(taxAmount: number | null): string {
  return taxAmount === null ? "未取得" : yen(taxAmount);
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

export function createExpenseAutoEntryDraftUpdateRequest(
  draft: ExpenseAutoEntryDraftResponse,
  form: ExpenseAutoEntryForm,
  resolvedFields: readonly ResolvedAutoEntryField[],
): UpdateExpenseAutoEntryDraftRequest {
  return {
    applicationVersion: draft.application.version,
    contextVersion: draft.autoEntry.contextVersion,
    application: {
      category: form.application.category,
      title: form.application.title,
      purpose: form.application.purpose,
      expenseDate: form.application.expenseDate,
      remarks: form.application.remarks,
      items: form.application.items.map((item) => ({
        sourceLineItemIndex: item.sourceLineItemIndex,
        expenseDate: item.expenseDate,
        description: item.description,
        amount: item.amount,
        merchantName: item.merchantName,
        origin: item.origin,
        destination: item.destination,
        transportationType: item.transportationType,
        participants: item.participants,
      })),
    },
    document: {
      issuerName: form.document.issuerName,
      issuerTaxRegistrationNumber: form.document.issuerTaxRegistrationNumber,
      invoiceTotalAmount: form.document.invoiceTotalAmount,
    },
    confirmedFieldPaths: getConfirmedAutoEntryFieldPaths(resolvedFields),
  };
}

export function autoEntryStatusLabel(status: AutoEntryFieldStatus): string {
  if (status === "OK") return "OK";
  if (status === "REVIEW") return "要確認";
  return "未取得";
}
