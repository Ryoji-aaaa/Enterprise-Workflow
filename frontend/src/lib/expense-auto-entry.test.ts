import assert from "node:assert/strict";
import test from "node:test";

import type {
  AutoEntryField,
  AutoEntryFieldStatus,
  AutoEntryReviewResponse,
} from "./auto-entry-review.ts";
import {
  createExpenseAutoEntryDraftRequest,
  getAutoEntryAttention,
  getConfirmedAutoEntryFieldPaths,
  getResolvedAutoEntryFields,
  hasInvoiceTotalMismatch,
  initializeExpenseAutoEntryForm,
  resolveAutoEntryField,
} from "./expense-auto-entry.ts";

function field<T>(value: T | null, status: AutoEntryFieldStatus = "OK"): AutoEntryField<T> {
  return { value, status, confidence: null, sources: [], findings: [] };
}

function review({
  issuerName = field("株式会社ABC", "REVIEW"),
  issuerTaxRegistrationNumber = field<string>(null, "MISSING"),
  totalAmount = field<number>(null, "MISSING"),
  lineItems = [{ itemDescription: field("業務用備品"), lineAmount: field(1200) }],
}: {
  issuerName?: AutoEntryField<string>;
  issuerTaxRegistrationNumber?: AutoEntryField<string>;
  totalAmount?: AutoEntryField<number>;
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
      lineItems: field(lineItems.map((item) => ({
        review: { confidence: null, status: "OK", sources: [], findings: [] },
        itemDescription: item.itemDescription,
        lineAmount: item.lineAmount,
      }))),
    } as unknown as AutoEntryReviewResponse["document"],
  } as unknown as AutoEntryReviewResponse;
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

test("請求書総額との差異はnon-blocking warning用にだけ判定する", () => {
  const form = initializeExpenseAutoEntryForm(review({ totalAmount: field(1200) }), "2026-08-13");
  assert.equal(hasInvoiceTotalMismatch(form.document.invoiceTotalAmount, form.application.items), false);
  assert.equal(hasInvoiceTotalMismatch(1300, form.application.items), true);
  assert.equal(hasInvoiceTotalMismatch(null, form.application.items), false);
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
