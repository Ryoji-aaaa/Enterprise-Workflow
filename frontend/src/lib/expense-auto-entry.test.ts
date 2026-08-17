import assert from "node:assert/strict";
import test from "node:test";

import type {
  AutoEntryAdjustment,
  AutoEntryDerivedField,
  AutoEntryField,
  AutoEntryFieldStatus,
  AutoEntryReviewResponse,
} from "./auto-entry-review.ts";
import {
  confirmedAutoEntryFieldPaths,
  createExpenseAutoEntryDraftUpdateRequest,
  createExpenseAutoEntryDraftRequest,
  formatExpenseAutoEntryTaxAmount,
  getAutoEntryAttention,
  getAutoEntryLineItemFieldPaths,
  getConfirmedAutoEntryFieldPaths,
  getResolvedAutoEntryFields,
  initializeExpenseAutoEntryForm,
  liveAutoEntryReviewToSource,
  persistedAutoEntryOriginalToSource,
  persistedExpenseAutoEntryDraftToForm,
  reconcileExpenseAutoEntryInvoiceTotal,
  resolveAutoEntryField,
  shouldShowAutoEntryField,
} from "./expense-auto-entry.ts";
import type { ExpenseAutoEntryDraftResponse } from "./expense-auto-entry-api.ts";
import type { ExpenseItem } from "./expense-application.ts";

function field<T>(value: T | null, status: AutoEntryFieldStatus = "OK"): AutoEntryField<T> {
  return { value, status, confidence: null, sources: [], findings: [] };
}

function derived<T>(value: T | null): AutoEntryDerivedField<T> {
  return { value, status: value === null ? "MISSING" : "OK", findings: [] };
}

function adjustment(
  amount: number | null,
  status: AutoEntryFieldStatus = amount === null ? "MISSING" : "OK",
): AutoEntryAdjustment {
  const uncertain = status === "REVIEW";
  return {
    review: { confidence: null, status: "OK", sources: [], findings: [] },
    type: field("DISCOUNT"),
    direction: {
      ...field(uncertain ? "UNKNOWN" : "DEDUCTION", status),
      findings: uncertain ? ["ADJUSTMENT_DIRECTION_UNKNOWN"] : [],
    },
    description: field("調整"),
    rawAmount: field(amount === null ? null : Math.abs(amount)),
    normalizedSignedAmount: {
      value: amount,
      status,
      findings: uncertain ? ["ADJUSTMENT_DIRECTION_UNKNOWN"] : [],
    },
  };
}

function expenseItem(amount: number): ExpenseItem {
  return {
    expenseDate: "2026-08-13",
    description: "明細",
    amount,
    merchantName: "",
    origin: "",
    destination: "",
    transportationType: "",
    participants: "",
  };
}

function review({
  issuerName = field("株式会社ABC", "REVIEW"),
  issuerTaxRegistrationNumber = field<string>(null, "MISSING"),
  totalAmount = field<number>(null, "MISSING"),
  taxAmount = field(0),
  adjustments = field<AutoEntryAdjustment[]>([]),
  taxMode = derived<"TAX_INCLUDED" | "TAX_EXCLUDED" | "UNKNOWN">("UNKNOWN"),
  lineItems = [{ itemDescription: field("業務用備品"), lineAmount: field(1200) }],
}: {
  issuerName?: AutoEntryField<string>;
  issuerTaxRegistrationNumber?: AutoEntryField<string>;
  totalAmount?: AutoEntryField<number>;
  taxAmount?: AutoEntryField<number>;
  adjustments?: AutoEntryField<AutoEntryAdjustment[]>;
  taxMode?: AutoEntryDerivedField<"TAX_INCLUDED" | "TAX_EXCLUDED" | "UNKNOWN">;
  lineItems?: Array<{ itemDescription: AutoEntryField<string>; lineAmount: AutoEntryField<number> }>;
} = {}): AutoEntryReviewResponse {
  return {
    analysisId: "123e4567-e89b-42d3-a456-426614174000",
    schemaVersion: "2.1",
    pages: [],
    summary: { fieldCount: 0, okCount: 0, reviewCount: 0, missingCount: 0 },
    document: {
      issuerName,
      issuerTaxRegistrationNumber,
      totalAmount,
      taxAmount,
      adjustments,
      lineItems: field(lineItems.map((item) => ({
        review: { confidence: null, status: "OK", sources: [], findings: [] },
        itemDescription: item.itemDescription,
        lineAmount: item.lineAmount,
      }))),
    } as unknown as AutoEntryReviewResponse["document"],
    taxMode,
  } as unknown as AutoEntryReviewResponse;
}

function persistedDraft(): ExpenseAutoEntryDraftResponse {
  return {
    application: {
      id: "123e4567-e89b-42d3-a456-426614174000", applicationNumber: "EXP-20260813-000001",
      category: "OTHER", title: "保存済み件名", purpose: "保存済み目的", expenseDate: "2026-08-13",
      totalAmount: 1250, currencyCode: "JPY", remarks: null, status: "DRAFT", version: 7,
      items: [{ id: "123e4567-e89b-42d3-a456-426614174001", displayOrder: 1, sourceLineItemIndex: 4, expenseDate: "2026-08-13", description: "人が修正した品名", amount: 1200, merchantName: "", origin: "", destination: "", transportationType: "", participants: "" }, { id: "123e4567-e89b-42d3-a456-426614174002", displayOrder: 2, sourceLineItemIndex: null, expenseDate: "2026-08-13", description: "手入力明細", amount: 50, merchantName: "", origin: "", destination: "", transportationType: "", participants: "" }],
    },
    autoEntry: {
      analysisId: "123e4567-e89b-42d3-a456-426614174010", contextVersion: 3, contextSchemaVersion: 1, sourceAttachmentId: "123e4567-e89b-42d3-a456-426614174011", schemaVersion: "2.1",
      original: { issuerName: field("AI発行元", "REVIEW"), issuerTaxRegistrationNumber: field<string>(null, "MISSING"), invoiceTotalAmount: field(1200), taxAmount: field(120), taxMode: derived("TAX_EXCLUDED"), adjustments: field<AutoEntryAdjustment[]>([]), lineItems: [{ sourceLineItemIndex: 4, itemDescription: field("AI品名", "REVIEW"), lineAmount: field(1200) }] },
      currentDocument: { issuerName: "人が修正した発行元", issuerTaxRegistrationNumber: null, invoiceTotalAmount: null },
      fields: { "document.issuerName": { resolution: "EDITED" }, "document.lineItems[4].itemDescription": { resolution: "CONFIRMED" }, "document.issuerTaxRegistrationNumber": { resolution: "UNRESOLVED" }, "document.totalAmount": { resolution: "NOT_REQUIRED" } }, unresolvedCount: 1, warnings: [],
    },
  };
}

test("AI値を必要最小限の初期フォームへ転記し、AIがない値を補完しない", () => {
  const form = initializeExpenseAutoEntryForm(review(), "2026-08-13");

  assert.equal(form.application.category, "OTHER");
  assert.equal(form.application.title, "");
  assert.equal(form.application.purpose, "");
  assert.equal(form.application.remarks, "");
  assert.equal(form.application.expenseDate, "2026-08-13");
  assert.equal(form.document.issuerName, "株式会社ABC");
  assert.equal(form.document.issuerTaxRegistrationNumber, "");
  assert.equal(form.document.invoiceTotalAmount, null);
  assert.deepEqual(form.application.items.map((item) => ({
    sourceLineItemIndex: item.sourceLineItemIndex,
    description: item.description,
    amount: item.amount,
    expenseDate: item.expenseDate,
  })), [{ sourceLineItemIndex: 0, description: "業務用備品", amount: 1200, expenseDate: "2026-08-13" }]);
});

test("AI明細がなければ推測せず手入力用の空明細だけを用意する", () => {
  const form = initializeExpenseAutoEntryForm(review({ lineItems: [] }), "2026-08-13");

  assert.deepEqual(form.application.items.map((item) => ({
    sourceLineItemIndex: item.sourceLineItemIndex,
    description: item.description,
    amount: item.amount,
  })), [{ sourceLineItemIndex: null, description: "", amount: 0 }]);
});

test("AI明細field pathは現在の配列位置でなくsourceLineItemIndexを使う", () => {
  const itemsAfterFirstDeletion = [
    { sourceLineItemIndex: 4 },
    { sourceLineItemIndex: null },
  ];

  assert.deepEqual(getAutoEntryLineItemFieldPaths(itemsAfterFirstDeletion[0]!.sourceLineItemIndex), {
    descriptionPath: "document.lineItems[4].itemDescription",
    amountPath: "document.lineItems[4].lineAmount",
  });
  assert.equal(getAutoEntryLineItemFieldPaths(itemsAfterFirstDeletion[1]!.sourceLineItemIndex), null);
});

test("人間の確認状態はBackendと同じ意味で解決する", () => {
  assert.equal(resolveAutoEntryField(field("issuer", "REVIEW"), "issuer", false), "UNRESOLVED");
  assert.equal(resolveAutoEntryField(field("issuer", "REVIEW"), "issuer", true), "CONFIRMED");
  assert.equal(resolveAutoEntryField(field("issuer", "REVIEW"), "edited", false), "EDITED");
  assert.equal(resolveAutoEntryField(field<string>(null, "MISSING"), "", false), "UNRESOLVED");
  assert.equal(resolveAutoEntryField(field<string>(null, "MISSING"), "entered", false), "EDITED");
  assert.equal(resolveAutoEntryField(field<number>(null, "MISSING"), 0, false), "UNRESOLVED");
  assert.equal(resolveAutoEntryField(field<number>(null, "MISSING"), 100, false), "EDITED");
  assert.equal(resolveAutoEntryField(field("issuer"), "issuer", false), "NOT_REQUIRED");
  assert.equal(resolveAutoEntryField(field("issuer"), "edited", false), "EDITED");
  assert.equal(resolveAutoEntryField(field("issuer"), "issuer", false, true), "EDITED");
  assert.equal(resolveAutoEntryField(field("  issuer  ", "REVIEW"), "issuer", true), "CONFIRMED");
});

test("確認済みパスは未編集のREVIEW fieldだけになり、attentionは未解決だけを数える", () => {
  const source = review();
  const form = initializeExpenseAutoEntryForm(source, "2026-08-13");
  const resolved = getResolvedAutoEntryFields(source, form, new Set(["document.issuerName", "unsupported.path"]));

  assert.deepEqual(getConfirmedAutoEntryFieldPaths(resolved), ["document.issuerName"]);
  assert.deepEqual(getAutoEntryAttention(resolved).map((item) => item.path), [
    "document.issuerTaxRegistrationNumber",
    "document.totalAmount",
  ]);

  const edited = { ...form, document: { ...form.document, issuerName: "修正後の会社" } };
  const resolvedAfterEdit = getResolvedAutoEntryFields(source, edited, new Set(["document.issuerName"]));
  assert.deepEqual(getConfirmedAutoEntryFieldPaths(resolvedAfterEdit), []);
  assert.equal(resolvedAfterEdit.find((item) => item.path === "document.issuerName")?.resolution, "EDITED");
});

test("要確認のみでは元から要確認または未取得のAI項目を編集後も表示する", () => {
  const source = review();
  const form = initializeExpenseAutoEntryForm(source, "2026-08-13");
  const initial = getResolvedAutoEntryFields(source, form, new Set());
  const missing = initial.find((item) => item.path === "document.issuerTaxRegistrationNumber");
  const ok = initial.find((item) => item.path === "document.lineItems[0].itemDescription");

  assert.ok(missing);
  assert.ok(ok);
  assert.equal(shouldShowAutoEntryField(missing, true), true);
  assert.equal(shouldShowAutoEntryField(ok, true), false);

  const edited = {
    ...form,
    document: { ...form.document, issuerTaxRegistrationNumber: "T1234567890123" },
  };
  const editedMissing = getResolvedAutoEntryFields(source, edited, new Set())
    .find((item) => item.path === "document.issuerTaxRegistrationNumber");

  assert.equal(editedMissing?.resolution, "EDITED");
  assert.ok(editedMissing);
  assert.equal(shouldShowAutoEntryField(editedMissing, true), true);
});

test("請求書総額は税・調整額とinclusive 1円許容を使って3状態で照合する", () => {
  const noAdjustments = field<AutoEntryAdjustment[]>([]);
  const discount = field([adjustment(-5)]);
  const uncertainAdjustment = field([adjustment(5, "REVIEW")]);
  const missingAdjustments = field<AutoEntryAdjustment[]>(null, "MISSING");
  const excluded = derived<"TAX_INCLUDED" | "TAX_EXCLUDED" | "UNKNOWN">("TAX_EXCLUDED");
  const included = derived<"TAX_INCLUDED" | "TAX_EXCLUDED" | "UNKNOWN">("TAX_INCLUDED");
  const unknown = derived<"TAX_INCLUDED" | "TAX_EXCLUDED" | "UNKNOWN">("UNKNOWN");

  const cases = [
    ["明細 + 税", 110, 100, field(10), noAdjustments, excluded, "MATCHED"],
    ["明細自体", 100, 100, field(10), noAdjustments, excluded, "MATCHED"],
    ["税込表記でも明細 + 税 - discount", 105, 100, field(10), discount, included, "MATCHED"],
    ["明細 + 税 - withholding", 105, 100, field(10), discount, excluded, "MATCHED"],
    ["taxMode UNKNOWN", 110, 100, field(10), noAdjustments, unknown, "MATCHED"],
    ["tax missingを0補完しない", 102, 100, field<number>(null, "MISSING"), noAdjustments, unknown, "UNAVAILABLE"],
    ["adjustment missing", 105, 100, field(10), missingAdjustments, excluded, "UNAVAILABLE"],
    ["符号未確定adjustmentだけで一致", 115, 100, field(10), uncertainAdjustment, excluded, "UNAVAILABLE"],
    ["符号未確定adjustmentなしの候補で一致", 110, 100, field(10), uncertainAdjustment, excluded, "MATCHED"],
    ["+1円", 101, 100, field(0), noAdjustments, unknown, "MATCHED"],
    ["-1円", 99, 100, field(0), noAdjustments, unknown, "MATCHED"],
    ["+2円", 102, 100, field(0), noAdjustments, unknown, "MISMATCH"],
    ["必要データが揃った不一致", 120, 100, field(10), noAdjustments, excluded, "MISMATCH"],
  ] as const;

  for (const [label, invoiceTotal, lineTotal, tax, adjustments, mode, expected] of cases) {
    assert.equal(
      reconcileExpenseAutoEntryInvoiceTotal(
        invoiceTotal,
        [expenseItem(lineTotal)],
        tax,
        adjustments,
        mode,
      ),
      expected,
      label,
    );
  }

  assert.equal(reconcileExpenseAutoEntryInvoiceTotal(
    115,
    [expenseItem(105)],
    field(10),
    noAdjustments,
    excluded,
  ), "MATCHED", "humanが編集した最新明細合計を使う");
  assert.equal(reconcileExpenseAutoEntryInvoiceTotal(
    110,
    [expenseItem(100)],
    field(10),
    noAdjustments,
    excluded,
  ), "MATCHED", "humanが編集した現在のinvoice totalを使う");
  assert.equal(reconcileExpenseAutoEntryInvoiceTotal(
    null,
    [expenseItem(100)],
    field(10),
    noAdjustments,
    excluded,
  ), "UNAVAILABLE");
});

test("消費税のread-only表示はnullを未取得としmetadataをsourceへ維持する", () => {
  const taxAmount = {
    ...field<number>(null, "MISSING"),
    confidence: 0.42,
    findings: ["LOW_CONFIDENCE" as const],
    sources: [{ pageNumber: 1, polygon: [] }],
  };
  const source = liveAutoEntryReviewToSource(review({ taxAmount }));

  assert.equal(formatExpenseAutoEntryTaxAmount(source.taxAmount.value), "未取得");
  assert.notEqual(formatExpenseAutoEntryTaxAmount(source.taxAmount.value), "￥0");
  assert.notEqual(formatExpenseAutoEntryTaxAmount(source.taxAmount.value), "¥0");
  assert.deepEqual(source.taxAmount, taxAmount);
  assert.match(formatExpenseAutoEntryTaxAmount(1000), /1,000/);
});

test("handoff payloadは人間の現在値と有効な確認パスだけを含み、AI metadataを含めない", () => {
  const source = review({ totalAmount: field(1200) });
  const form = initializeExpenseAutoEntryForm(source, "2026-08-13");
  form.application.title = "備品購入";
  form.application.purpose = "業務利用";
  const resolved = getResolvedAutoEntryFields(source, form, new Set(["document.issuerName"]));
  const payload = createExpenseAutoEntryDraftRequest(source.analysisId, form, resolved);

  assert.deepEqual(Object.keys(payload).sort(), ["analysisId", "application", "confirmedFieldPaths", "document"]);
  assert.deepEqual(payload.confirmedFieldPaths, ["document.issuerName"]);
  assert.equal(payload.application.items[0]?.sourceLineItemIndex, 0);
  assert.equal(JSON.stringify(payload).includes("confidence"), false);
  assert.equal(JSON.stringify(payload).includes("findings"), false);
  assert.equal(JSON.stringify(payload).includes("sources"), false);
  assert.equal(JSON.stringify(payload).includes("polygon"), false);
  assert.equal(JSON.stringify(payload).includes("resolution"), false);
});

test("保存済みAUTO_ENTRY下書きは人間の現在値とsourceLineItemIndexを編集フォームへ復元する", () => {
  const form = persistedExpenseAutoEntryDraftToForm(persistedDraft());
  assert.equal(form.document.issuerName, "人が修正した発行元");
  assert.equal(form.document.issuerTaxRegistrationNumber, "");
  assert.equal(form.document.invoiceTotalAmount, null);
  assert.deepEqual(form.application.items.map(({ sourceLineItemIndex, description }) => ({ sourceLineItemIndex, description })), [{ sourceLineItemIndex: 4, description: "人が修正した品名" }, { sourceLineItemIndex: null, description: "手入力明細" }]);
});

test("保存済みの確認状態はCONFIRMEDだけをローカル確認パスへ復元する", () => {
  assert.deepEqual([...confirmedAutoEntryFieldPaths(persistedDraft().autoEntry.fields)], ["document.lineItems[4].itemDescription"]);
});

test("live Reviewと保存済みOriginalは同じ追跡対象へ正規化できる", () => {
  const source = persistedAutoEntryOriginalToSource(persistedDraft().autoEntry.original);
  const live = liveAutoEntryReviewToSource(review({
    issuerName: field("AI発行元", "REVIEW"), issuerTaxRegistrationNumber: field<string>(null, "MISSING"), totalAmount: field(1200),
    taxAmount: field(120), taxMode: derived("TAX_EXCLUDED"), adjustments: field<AutoEntryAdjustment[]>([]),
    lineItems: [{ itemDescription: field("AI品名", "REVIEW"), lineAmount: field(1200) }],
  }));
  assert.deepEqual({ ...source, lineItems: source.lineItems.map(({ itemDescription, lineAmount }) => ({ itemDescription, lineAmount })) }, { ...live, lineItems: live.lineItems.map(({ itemDescription, lineAmount }) => ({ itemDescription, lineAmount })) });
});

test("旧Backend responseで税・調整情報がなくても未取得へ正規化して照合不能にする", () => {
  const original = { ...persistedDraft().autoEntry.original };
  delete original.taxAmount;
  delete original.taxMode;
  delete original.adjustments;

  const source = persistedAutoEntryOriginalToSource(original);
  assert.equal(source.taxAmount.value, null);
  assert.equal(source.adjustments.value, null);
  assert.equal(source.taxMode.value, "UNKNOWN");
  assert.equal(reconcileExpenseAutoEntryInvoiceTotal(
    1300,
    [expenseItem(1200)],
    source.taxAmount,
    source.adjustments,
    source.taxMode,
  ), "UNAVAILABLE");
});

test("AUTO_ENTRY更新payloadは両versionと現在値だけを送り、response専用値とAI metadataを含めない", () => {
  const draft = persistedDraft();
  const form = persistedExpenseAutoEntryDraftToForm(draft);
  const resolved = getResolvedAutoEntryFields(persistedAutoEntryOriginalToSource(draft.autoEntry.original), form, confirmedAutoEntryFieldPaths(draft.autoEntry.fields));
  const payload = createExpenseAutoEntryDraftUpdateRequest(draft, form, resolved);
  assert.deepEqual(Object.keys(payload).sort(), ["application", "applicationVersion", "confirmedFieldPaths", "contextVersion", "document"]);
  assert.equal(payload.applicationVersion, 7);
  assert.equal(payload.contextVersion, 3);
  assert.deepEqual(payload.application.items.map((item) => item.sourceLineItemIndex), [4, null]);
  const json = JSON.stringify(payload);
  for (const forbidden of ["confidence", "findings", "sources", "polygon", "resolution", "original", '"id"', "displayOrder", "status"]) assert.equal(json.includes(forbidden), false);
});
