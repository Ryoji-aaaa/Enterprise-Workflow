"use client";

import type { ReactNode } from "react";

import { Badge } from "@/components/ui/badge";
import type {
  AutoEntryDerivedField,
  AutoEntryField,
  AutoEntryFieldStatus,
  AutoEntryLineItem,
  AutoEntryObjectReview,
  AutoEntryReviewResponse,
} from "@/lib/auto-entry-review";
import {
  formatAutoEntryConfidence,
  formatAutoEntryFinding,
  formatAutoEntryFieldValue,
  formatAutoEntrySources,
  getAutoEntryArrayDisplayState,
} from "@/lib/auto-entry-review-display";
import { cn } from "@/lib/utils";

const statusLabels: Record<AutoEntryFieldStatus, string> = {
  OK: "OK",
  REVIEW: "要確認",
  MISSING: "未取得",
};

const statusClasses: Record<AutoEntryFieldStatus, string> = {
  OK: "border-emerald-600/30 bg-emerald-600/10 text-emerald-700 dark:text-emerald-400",
  REVIEW: "border-amber-600/30 bg-amber-600/10 text-amber-700 dark:text-amber-400",
  MISSING: "border-destructive/30 bg-destructive/10 text-destructive",
};

function StatusBadge({ status }: { status: AutoEntryFieldStatus }) {
  return (
    <Badge className={cn("shrink-0 border", statusClasses[status])} variant="outline">
      {statusLabels[status]}
    </Badge>
  );
}

function ReviewMeta({
  confidence,
  sources,
  findings,
}: Pick<AutoEntryField<unknown>, "confidence" | "sources" | "findings">) {
  const parts = [
    formatAutoEntryConfidence(confidence),
    formatAutoEntrySources(sources),
    ...findings.map(formatAutoEntryFinding),
  ].filter((value): value is string => value !== null);

  return parts.length === 0 ? null : (
    <p className="mt-1 text-xs text-muted-foreground">{parts.join(" · ")}</p>
  );
}

function ObjectReviewMeta({ review }: { review: AutoEntryObjectReview }) {
  return <ReviewMeta confidence={review.confidence} findings={review.findings} sources={review.sources} />;
}

function AutoEntryFieldValue({ field }: { field: AutoEntryField<string | number> }) {
  return (
    <div className="min-w-28">
      <div className="flex min-w-0 items-start gap-2">
        <span className={cn("min-w-0 break-words text-sm", field.value === null && "text-muted-foreground")}>
          {formatAutoEntryFieldValue(field)}
        </span>
        <StatusBadge status={field.status} />
      </div>
      <ReviewMeta confidence={field.confidence} findings={field.findings} sources={field.sources} />
    </div>
  );
}

function AutoEntryFieldRow({ label, field }: { label: string; field: AutoEntryField<string | number> }) {
  return (
    <div className="grid gap-1 border-b py-3 last:border-b-0 sm:grid-cols-[10rem_minmax(0,1fr)] sm:gap-4">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd><AutoEntryFieldValue field={field} /></dd>
    </div>
  );
}

function DerivedFieldValue({ field }: { field: AutoEntryDerivedField<string | number> }) {
  return (
    <div className="min-w-28">
      <div className="flex items-start gap-2">
        <span className={cn("break-words text-sm", field.value === null && "text-muted-foreground")}>
          {formatAutoEntryFieldValue(field)}
        </span>
        <StatusBadge status={field.status} />
      </div>
      {field.findings.length > 0 ? (
        <p className="mt-1 text-xs text-muted-foreground">
          {field.findings.map(formatAutoEntryFinding).join(" · ")}
        </p>
      ) : null}
    </div>
  );
}

function ReviewSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-md border bg-background p-4">
      <h3 className="text-sm font-semibold">{title}</h3>
      <dl className="mt-2">{children}</dl>
    </section>
  );
}

function ArrayFieldMeta({ field }: { field: AutoEntryField<unknown> }) {
  return (
    <div className="mb-3 flex items-start gap-2">
      <StatusBadge status={field.status} />
      <ReviewMeta confidence={field.confidence} findings={field.findings} sources={field.sources} />
    </div>
  );
}

function ReviewTable({ children }: { children: ReactNode }) {
  return <div className="overflow-x-auto"><table className="w-full min-w-[52rem] text-left text-sm">{children}</table></div>;
}

function LineItemRow({ item }: { item: AutoEntryLineItem }) {
  return (
    <tr className="border-b align-top">
      <td className="p-2"><AutoEntryFieldValue field={item.itemDate} /></td>
      <td className="p-2"><AutoEntryFieldValue field={item.productCode} /></td>
      <td className="p-2"><AutoEntryFieldValue field={item.itemDescription} /></td>
      <td className="p-2"><AutoEntryFieldValue field={item.quantity} /></td>
      <td className="p-2"><AutoEntryFieldValue field={item.unit} /></td>
      <td className="p-2"><AutoEntryFieldValue field={item.unitPriceAmount} /></td>
      <td className="p-2"><AutoEntryFieldValue field={item.taxIndicator} /></td>
      <td className="p-2"><AutoEntryFieldValue field={item.taxRatePercent} /></td>
      <td className="p-2"><AutoEntryFieldValue field={item.taxCategory} /></td>
      <td className="p-2"><AutoEntryFieldValue field={item.lineAmount} /></td>
    </tr>
  );
}

export function AutoEntryReviewPanel({ review }: { review: AutoEntryReviewResponse | null }) {
  if (!review) {
    return (
      <div className="grid h-full place-items-center p-6 text-center text-sm text-muted-foreground">
        分析が完了すると、自動入力の確認結果を表示します。
      </div>
    );
  }

  const { document, summary } = review;
  const lineItems = document.lineItems.value ?? [];
  const taxBreakdown = document.taxBreakdown.value ?? [];
  const adjustments = document.adjustments.value ?? [];
  const lineItemsDisplay = getAutoEntryArrayDisplayState(document.lineItems);
  const taxBreakdownDisplay = getAutoEntryArrayDisplayState(document.taxBreakdown);
  const adjustmentsDisplay = getAutoEntryArrayDisplayState(document.adjustments);
  const bank = document.bankTransferDestination.value;

  return (
    <div className="h-full overflow-y-auto p-4" data-testid="auto-entry-review-panel">
      <div className="space-y-4 pb-4">
        <section className="rounded-md border bg-muted/20 p-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <h2 className="text-base font-semibold">自動入力結果</h2>
              <p className="mt-1 text-xs text-muted-foreground">Review API の判定結果を表示しています。</p>
            </div>
            <Badge variant="outline">schema {review.schemaVersion}</Badge>
          </div>
          <dl className="mt-4 grid grid-cols-2 gap-2 text-sm sm:grid-cols-4">
            <div className="rounded-md bg-background p-3"><dt className="text-xs text-muted-foreground">項目数</dt><dd className="mt-1 font-semibold">{summary.fieldCount}</dd></div>
            <div className="rounded-md bg-background p-3"><dt className="text-xs text-muted-foreground">正常</dt><dd className="mt-1 font-semibold">{summary.okCount}</dd></div>
            <div className="rounded-md bg-background p-3"><dt className="text-xs text-muted-foreground">要確認</dt><dd className="mt-1 font-semibold">{summary.reviewCount}</dd></div>
            <div className="rounded-md bg-background p-3"><dt className="text-xs text-muted-foreground">未取得</dt><dd className="mt-1 font-semibold">{summary.missingCount}</dd></div>
          </dl>
        </section>

        <ReviewSection title="基本情報">
          <AutoEntryFieldRow field={document.documentType} label="文書種別" />
          <AutoEntryFieldRow field={document.documentNumber} label="文書番号" />
          <AutoEntryFieldRow field={document.issueDate} label="発行日" />
          <AutoEntryFieldRow field={document.subject} label="件名" />
          <AutoEntryFieldRow field={document.currencyCode} label="通貨" />
        </ReviewSection>

        <ReviewSection title="宛先">
          <AutoEntryFieldRow field={document.recipientName} label="会社名" />
          <AutoEntryFieldRow field={document.recipientDepartment} label="部署" />
          <AutoEntryFieldRow field={document.recipientContactPerson} label="担当者" />
          <AutoEntryFieldRow field={document.recipientPostalCode} label="郵便番号" />
          <AutoEntryFieldRow field={document.recipientAddress} label="住所" />
        </ReviewSection>

        <ReviewSection title="発行元">
          <AutoEntryFieldRow field={document.issuerName} label="会社名" />
          <AutoEntryFieldRow field={document.issuerDepartment} label="部署" />
          <AutoEntryFieldRow field={document.issuerContactPerson} label="担当者" />
          <AutoEntryFieldRow field={document.issuerPostalCode} label="郵便番号" />
          <AutoEntryFieldRow field={document.issuerAddress} label="住所" />
          <AutoEntryFieldRow field={document.issuerPhoneNumber} label="電話" />
          <AutoEntryFieldRow field={document.issuerEmail} label="メール" />
          <AutoEntryFieldRow field={document.issuerTaxRegistrationNumber} label="登録番号" />
        </ReviewSection>

        <ReviewSection title="金額">
          <AutoEntryFieldRow field={document.subtotalAmount} label="小計" />
          <AutoEntryFieldRow field={document.taxAmount} label="税額" />
          <AutoEntryFieldRow field={document.totalAmount} label="合計" />
          <AutoEntryFieldRow field={document.taxInclusionNotation} label="税込・税抜表記" />
          <div className="grid gap-1 border-b py-3 last:border-b-0 sm:grid-cols-[10rem_minmax(0,1fr)] sm:gap-4">
            <dt className="text-sm text-muted-foreground">税込・税抜判定</dt>
            <dd><DerivedFieldValue field={review.taxMode} /></dd>
          </div>
        </ReviewSection>

        <section className="rounded-md border bg-background p-4">
          <h3 className="text-sm font-semibold">明細</h3>
          <ArrayFieldMeta field={document.lineItems} />
          {lineItemsDisplay === "missing" ? <p className="text-sm text-muted-foreground">明細は未取得です。</p> : lineItemsDisplay === "empty" ? <p className="text-sm text-muted-foreground">明細はありません。</p> : (
            <ReviewTable>
              <thead className="border-b text-xs text-muted-foreground"><tr>{["日付", "商品コード", "内容", "数量", "単位", "単価", "税区分", "税率", "Category", "金額"].map((label) => <th className="p-2 font-medium" key={label}>{label}</th>)}</tr></thead>
              <tbody>{lineItems.map((item, index) => <LineItemRow item={item} key={index} />)}</tbody>
            </ReviewTable>
          )}
        </section>

        <section className="rounded-md border bg-background p-4">
          <h3 className="text-sm font-semibold">税内訳</h3>
          <ArrayFieldMeta field={document.taxBreakdown} />
          {taxBreakdownDisplay === "missing" ? <p className="text-sm text-muted-foreground">税内訳は未取得です。</p> : taxBreakdownDisplay === "empty" ? <p className="text-sm text-muted-foreground">税内訳はありません。</p> : (
            <ReviewTable><thead className="border-b text-xs text-muted-foreground"><tr>{["表記", "税率", "対象額", "税額", "Category"].map((label) => <th className="p-2 font-medium" key={label}>{label}</th>)}</tr></thead><tbody>{taxBreakdown.map((item, index) => <tr className="border-b align-top" key={index}><td className="p-2"><AutoEntryFieldValue field={item.categoryNotation} /></td><td className="p-2"><AutoEntryFieldValue field={item.taxRatePercent} /></td><td className="p-2"><AutoEntryFieldValue field={item.taxableAmount} /></td><td className="p-2"><AutoEntryFieldValue field={item.taxAmount} /></td><td className="p-2"><AutoEntryFieldValue field={item.category} /><ObjectReviewMeta review={item.review} /></td></tr>)}</tbody></ReviewTable>
          )}
        </section>

        <section className="rounded-md border bg-background p-4">
          <h3 className="text-sm font-semibold">調整</h3>
          <ArrayFieldMeta field={document.adjustments} />
          {adjustmentsDisplay === "missing" ? <p className="text-sm text-muted-foreground">調整は未取得です。</p> : adjustmentsDisplay === "empty" ? <p className="text-sm text-muted-foreground">調整はありません。</p> : (
            <ReviewTable><thead className="border-b text-xs text-muted-foreground"><tr>{["種別", "方向", "説明", "金額", "正規化金額"].map((label) => <th className="p-2 font-medium" key={label}>{label}</th>)}</tr></thead><tbody>{adjustments.map((item, index) => <tr className="border-b align-top" key={index}><td className="p-2"><AutoEntryFieldValue field={item.type} /></td><td className="p-2"><AutoEntryFieldValue field={item.direction} /></td><td className="p-2"><AutoEntryFieldValue field={item.description} /></td><td className="p-2"><AutoEntryFieldValue field={item.rawAmount} /></td><td className="p-2"><DerivedFieldValue field={item.normalizedSignedAmount} /><ObjectReviewMeta review={item.review} /></td></tr>)}</tbody></ReviewTable>
          )}
        </section>

        <ReviewSection title="支払・振込">
          <AutoEntryFieldRow field={document.paymentDueDate} label="支払期限" />
          <div className="border-b py-3">
            <p className="text-sm text-muted-foreground">振込先</p>
            <div className="mt-1 flex items-start gap-2">
              <StatusBadge status={document.bankTransferDestination.status} />
              <ReviewMeta
                confidence={document.bankTransferDestination.confidence}
                findings={document.bankTransferDestination.findings}
                sources={document.bankTransferDestination.sources}
              />
            </div>
          </div>
          {bank ? <div className="border-t pt-2"><AutoEntryFieldRow field={bank.bankName} label="銀行名" /><AutoEntryFieldRow field={bank.branchName} label="支店名" /><AutoEntryFieldRow field={bank.accountType} label="口座種別" /><AutoEntryFieldRow field={bank.accountNumber} label="口座番号" /><AutoEntryFieldRow field={bank.accountHolderName} label="口座名義" /></div> : null}
        </ReviewSection>
      </div>
    </div>
  );
}
