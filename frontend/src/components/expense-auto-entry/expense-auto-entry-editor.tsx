"use client";

import { Plus, Trash2, TriangleAlert } from "lucide-react";
import type { ReactNode } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import type {
  AutoEntryAdjustment,
  AutoEntryField,
  AutoEntryFieldStatus,
  AutoEntryFindingCode,
  AutoEntrySourceRef,
} from "@/lib/auto-entry-review";
import {
  formatAutoEntryConfidence,
  formatAutoEntryFinding,
  formatAutoEntrySources,
} from "@/lib/auto-entry-review-display";
import {
  type ExpenseAutoEntryForm,
  type ExpenseAutoEntryItem,
  type ExpenseAutoEntryReviewSource,
  type ResolvedAutoEntryField,
  autoEntryStatusLabel,
  formatExpenseAutoEntryTaxAmount,
  getAutoEntryAttention,
  isSafeExpenseAutoEntryAdjustment,
  reconcileExpenseAutoEntryInvoiceTotal,
  shouldShowAutoEntryField,
} from "@/lib/expense-auto-entry";
import {
  categoryLabels,
  expenseCategories,
  totalExpenseAmount,
  type ExpenseCategory,
  yen,
} from "@/lib/expense-application";
import { cn } from "@/lib/utils";

function statusClass(status: AutoEntryFieldStatus): string {
  if (status === "OK") return "border-emerald-600/30 bg-emerald-600/10 text-emerald-700 dark:text-emerald-400";
  if (status === "REVIEW") return "border-amber-600/30 bg-amber-600/10 text-amber-700 dark:text-amber-400";
  return "border-destructive/30 bg-destructive/10 text-destructive";
}

function ReviewMetadata({ status, confidence, sources, findings, resolution }: {
  status: AutoEntryFieldStatus;
  confidence: number | null;
  sources: readonly AutoEntrySourceRef[];
  findings: readonly AutoEntryFindingCode[];
  resolution?: ResolvedAutoEntryField["resolution"];
}) {
  const details = [...new Set([
    formatAutoEntryConfidence(confidence),
    formatAutoEntrySources(sources),
    ...findings.map(formatAutoEntryFinding),
  ].filter((value): value is string => value !== null))];
  return <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground"><Badge className={cn("border", statusClass(status))} variant="outline">{autoEntryStatusLabel(status)}</Badge>{resolution === "EDITED" ? <span className="font-medium text-foreground">修正済み</span> : null}{resolution === "CONFIRMED" ? <span className="font-medium text-foreground">確認済み</span> : null}{details.map((detail) => <span key={detail}>{detail}</span>)}</div>;
}

function FieldMetadata({ field, resolution }: {
  field: Pick<AutoEntryField<unknown>, "status" | "confidence" | "sources" | "findings">;
  resolution?: ResolvedAutoEntryField["resolution"];
}) {
  return <ReviewMetadata confidence={field.confidence} findings={field.findings} resolution={resolution} sources={field.sources} status={field.status} />;
}

function adjustmentDirectionLabel(direction: string | null): string {
  if (direction === "DEDUCTION") return "減算";
  if (direction === "ADDITION") return "加算";
  return "方向未確定";
}

function AdjustmentMetadata({ adjustment }: { adjustment: AutoEntryAdjustment }) {
  const findings = [
    ...adjustment.review.findings,
    ...adjustment.direction.findings,
    ...adjustment.normalizedSignedAmount.findings,
  ];
  return <ReviewMetadata confidence={adjustment.review.confidence} findings={findings} sources={adjustment.review.sources} status={adjustment.normalizedSignedAmount.status} />;
}

function ExpenseAutoEntryAdjustments({ field }: {
  field: AutoEntryField<AutoEntryAdjustment[]>;
}) {
  if (field.value === null) {
    return <div data-testid="expense-auto-entry-adjustments"><p className="font-medium">調整額（読取値） 未取得</p><div className="flex justify-end"><FieldMetadata field={field} /></div></div>;
  }
  if (field.value.length === 0) {
    return <div data-testid="expense-auto-entry-adjustments"><p className="font-medium">調整額（読取値） なし</p></div>;
  }

  const normalizedValues = field.value.map((adjustment) =>
    isSafeExpenseAutoEntryAdjustment(adjustment)
      ? adjustment.normalizedSignedAmount.value
      : null);
  const total = normalizedValues.every((value): value is number => value !== null)
    ? normalizedValues.reduce((sum, value) => sum + value, 0)
    : null;
  return <div className="space-y-2" data-testid="expense-auto-entry-adjustments"><p className="font-medium">調整額（読取値） 合計 {total === null ? "未確定" : yen(total)}</p><ul className="space-y-2">{field.value.map((adjustment, index) => {
    const itemSafe = isSafeExpenseAutoEntryAdjustment(adjustment);
    const label = adjustment.description.value ?? adjustment.type.value ?? `調整 ${index + 1}`;
    const rawAmount = adjustment.rawAmount.value;
    const normalizedValue = itemSafe ? adjustment.normalizedSignedAmount.value : null;
    const amount = normalizedValue !== null
      ? yen(normalizedValue)
      : rawAmount === null ? "未取得" : `符号未確定（読取額 ${yen(Math.abs(rawAmount))}）`;
    return <li className="rounded-md border bg-background px-3 py-2" key={`${label}-${index}`}><p>{label}（{adjustmentDirectionLabel(adjustment.direction.value)}） {amount}</p><AdjustmentMetadata adjustment={adjustment} /></li>;
  })}</ul></div>;
}

function AttentionReason({ field }: { field: ResolvedAutoEntryField }) {
  const reason = field.field.status === "MISSING" ? "未取得のため、必要に応じて入力してください" : field.field.findings.length > 0 ? field.field.findings.map(formatAutoEntryFinding).join(" · ") : "原本を確認してください";
  const details = [
    formatAutoEntryConfidence(field.field.confidence),
    formatAutoEntrySources(field.field.sources),
  ].filter((value): value is string => value !== null);
  return <li className="rounded-md border bg-background px-3 py-2"><p className="font-medium">{field.label}</p><p className="mt-0.5 text-muted-foreground">{reason}</p>{details.length > 0 ? <p className="mt-0.5 text-muted-foreground">{details.join(" · ")}</p> : null}</li>;
}

export function ExpenseAutoEntryEditor({
  form,
  reviewSource,
  resolvedFields,
  confirmedPaths,
  showAttentionOnly,
  onShowAttentionOnlyChange,
  onDocumentChange,
  onApplicationChange,
  onExpenseDateChange,
  onItemChange,
  onConfirmationChange,
  onAddItem,
  onDeleteItem,
  children,
}: {
  form: ExpenseAutoEntryForm;
  reviewSource: ExpenseAutoEntryReviewSource;
  resolvedFields: readonly ResolvedAutoEntryField[];
  confirmedPaths: ReadonlySet<string>;
  showAttentionOnly: boolean;
  onShowAttentionOnlyChange: (value: boolean) => void;
  onDocumentChange: (values: Partial<ExpenseAutoEntryForm["document"]>, path: string) => void;
  onApplicationChange: (values: Partial<ExpenseAutoEntryForm["application"]>) => void;
  onExpenseDateChange: (value: string) => void;
  onItemChange: (index: number, values: Partial<ExpenseAutoEntryItem>) => void;
  onConfirmationChange: (path: string, checked: boolean) => void;
  onAddItem: () => void;
  onDeleteItem: (index: number) => void;
  children: ReactNode;
}) {
  const attention = getAutoEntryAttention(resolvedFields);
  const resolvedByPath = new Map(resolvedFields.map((field) => [field.path, field]));
  const reconciliation = reconcileExpenseAutoEntryInvoiceTotal(
    form.document.invoiceTotalAmount,
    form.application.items,
    reviewSource.taxAmount,
    reviewSource.adjustments,
    reviewSource.taxMode,
  );
  const documentFields = [
    { label: "請求社 / 発行元", path: "document.issuerName", value: form.document.issuerName, type: "text" as const },
    { label: "インボイス登録番号", path: "document.issuerTaxRegistrationNumber", value: form.document.issuerTaxRegistrationNumber, type: "text" as const },
    { label: "総請求額（円）", path: "document.totalAmount", value: form.document.invoiceTotalAmount ?? "", type: "number" as const },
  ];

  return <div className="space-y-6">
    <section className="rounded-md border bg-amber-50/50 p-4 dark:bg-amber-950/10"><div className="flex flex-wrap items-center justify-between gap-3"><div><h2 className="font-semibold">⚠ 確認が必要な項目 {attention.length}件</h2><p className="mt-1 text-xs text-muted-foreground">AIの確認状況は申請の必須入力とは別です。</p></div><div className="flex gap-1"><Button aria-pressed={showAttentionOnly} onClick={() => onShowAttentionOnlyChange(true)} size="sm" type="button" variant={showAttentionOnly ? "secondary" : "ghost"}>要確認のみ</Button><Button aria-pressed={!showAttentionOnly} onClick={() => onShowAttentionOnlyChange(false)} size="sm" type="button" variant={!showAttentionOnly ? "secondary" : "ghost"}>すべて</Button></div></div>{attention.length > 0 ? <ul className="mt-3 space-y-2 text-xs">{attention.map((field) => <AttentionReason field={field} key={field.path} />)}</ul> : <p className="mt-3 text-sm text-muted-foreground">現在、確認が必要なAI入力値はありません。</p>}</section>
    <section className="space-y-3"><h2 className="font-semibold">請求書・注文書の読み取り値</h2>{documentFields.map((documentField) => {
      const resolved = resolvedByPath.get(documentField.path);
      if (!resolved || !shouldShowAutoEntryField(resolved, showAttentionOnly)) return null;
      const isReviewConfirmation = resolved.field.status === "REVIEW" && resolved.resolution !== "EDITED";
      return <div className="rounded-md border p-3" key={documentField.path}><label className="grid gap-1 text-sm">{documentField.label}<Input maxLength={documentField.path === "document.issuerTaxRegistrationNumber" ? 100 : 500} min={documentField.type === "number" ? 1 : undefined} onChange={(event) => onDocumentChange(documentField.path === "document.issuerName" ? { issuerName: event.target.value } : documentField.path === "document.issuerTaxRegistrationNumber" ? { issuerTaxRegistrationNumber: event.target.value } : { invoiceTotalAmount: event.target.value === "" ? null : Number(event.target.value) }, documentField.path)} step={documentField.type === "number" ? 1 : undefined} type={documentField.type} value={documentField.value} /></label><FieldMetadata field={resolved.field} resolution={resolved.resolution} />{isReviewConfirmation ? <label className="mt-2 flex items-center gap-2 text-sm"><input checked={confirmedPaths.has(documentField.path)} onChange={(event) => onConfirmationChange(documentField.path, event.target.checked)} type="checkbox" />原本を確認しました</label> : null}</div>;
    })}</section>
    <section className="space-y-4"><h2 className="font-semibold">経費申請の入力</h2><div className="grid gap-3 md:grid-cols-2"><label className="grid gap-1 text-sm">経費区分<select className="h-8 rounded-md border bg-background px-2 text-sm" onChange={(event) => onApplicationChange({ category: event.target.value as ExpenseCategory })} value={form.application.category}>{expenseCategories.map((category) => <option key={category} value={category}>{categoryLabels[category]}</option>)}</select></label><label className="grid gap-1 text-sm">利用日<Input onChange={(event) => onExpenseDateChange(event.target.value)} required type="date" value={form.application.expenseDate} /></label><label className="grid gap-1 text-sm md:col-span-2">件名<Input maxLength={200} onChange={(event) => onApplicationChange({ title: event.target.value })} required value={form.application.title} /></label><label className="grid gap-1 text-sm md:col-span-2">利用目的<textarea className="min-h-20 rounded-md border bg-background p-2 text-sm" onChange={(event) => onApplicationChange({ purpose: event.target.value })} required value={form.application.purpose} /></label><label className="grid gap-1 text-sm md:col-span-2">備考<textarea className="min-h-16 rounded-md border bg-background p-2 text-sm" onChange={(event) => onApplicationChange({ remarks: event.target.value })} value={form.application.remarks} /></label></div></section>
    <section className="space-y-3"><div className="flex items-center justify-between gap-3"><h2 className="font-semibold">経費明細</h2><Button onClick={onAddItem} size="sm" type="button" variant="outline"><Plus data-icon="inline-start" />明細追加</Button></div>{form.application.items.map((item, index) => {
      const description = item.sourceLineItemIndex === null ? undefined : resolvedByPath.get(`document.lineItems[${item.sourceLineItemIndex}].itemDescription`);
      const amount = item.sourceLineItemIndex === null ? undefined : resolvedByPath.get(`document.lineItems[${item.sourceLineItemIndex}].lineAmount`);
      return <div className="space-y-3 rounded-md border p-3" key={`${item.sourceLineItemIndex ?? "manual"}-${index}`}><div className="grid gap-3 md:grid-cols-2"><label className="grid gap-1 text-sm">内容<Input maxLength={500} onChange={(event) => onItemChange(index, { description: event.target.value })} required value={item.description} /></label><label className="grid gap-1 text-sm">金額（円）<Input min={1} onChange={(event) => onItemChange(index, { amount: Number(event.target.value) })} required step={1} type="number" value={item.amount || ""} /></label></div>{description ? <><FieldMetadata field={description.field} resolution={description.resolution} />{description.field.status === "REVIEW" && description.resolution !== "EDITED" ? <label className="flex items-center gap-2 text-sm"><input checked={confirmedPaths.has(description.path)} onChange={(event) => onConfirmationChange(description.path, event.target.checked)} type="checkbox" />内容の原本を確認しました</label> : null}</> : null}{amount ? <><FieldMetadata field={amount.field} resolution={amount.resolution} />{amount.field.status === "REVIEW" && amount.resolution !== "EDITED" ? <label className="flex items-center gap-2 text-sm"><input checked={confirmedPaths.has(amount.path)} onChange={(event) => onConfirmationChange(amount.path, event.target.checked)} type="checkbox" />金額の原本を確認しました</label> : null}</> : null}{form.application.category === "MEAL" || form.application.category === "TRAINING" || form.application.category === "CERTIFICATION" ? <label className="grid gap-1 text-sm">{form.application.category === "MEAL" ? "店舗名" : form.application.category === "TRAINING" ? "主催者" : "試験実施団体"}<Input onChange={(event) => onItemChange(index, { merchantName: event.target.value })} required value={item.merchantName} /></label> : null}{form.application.category === "MEAL" ? <label className="grid gap-1 text-sm">参加者<Input onChange={(event) => onItemChange(index, { participants: event.target.value })} required value={item.participants} /></label> : null}{form.application.category === "TRANSPORTATION" ? <div className="grid gap-3 md:grid-cols-3"><label className="grid gap-1 text-sm">交通手段<Input onChange={(event) => onItemChange(index, { transportationType: event.target.value })} required value={item.transportationType} /></label><label className="grid gap-1 text-sm">出発地<Input onChange={(event) => onItemChange(index, { origin: event.target.value })} required value={item.origin} /></label><label className="grid gap-1 text-sm">到着地<Input onChange={(event) => onItemChange(index, { destination: event.target.value })} required value={item.destination} /></label></div> : null}<div className="flex justify-end"><Button aria-label={`明細${index + 1}を削除`} onClick={() => onDeleteItem(index)} size="sm" type="button" variant="ghost"><Trash2 data-icon="inline-start" />削除</Button></div></div>;
    })}<div className="space-y-3 text-right"><p className="text-lg font-semibold">申請明細合計 {yen(totalExpenseAmount(form.application.items))}</p><div data-testid="expense-auto-entry-tax-amount"><p className="font-medium">消費税（読取値） {formatExpenseAutoEntryTaxAmount(reviewSource.taxAmount.value)}</p><div className="flex justify-end"><FieldMetadata field={reviewSource.taxAmount} /></div></div><ExpenseAutoEntryAdjustments field={reviewSource.adjustments} /></div>{reconciliation === "MISMATCH" ? <Alert><TriangleAlert /><AlertTitle>請求書総額と申請金額の照合結果が一致しません</AlertTitle><AlertDescription>申請明細、読み取った消費税・値引き等を考慮して照合しています。値を自動で変更することはありません。</AlertDescription></Alert> : null}{reconciliation === "UNAVAILABLE" ? <Alert><TriangleAlert /><AlertTitle>請求額を照合できません</AlertTitle><AlertDescription>消費税または符号を安全に確定できる調整額を十分に取得できなかったため、請求書総額との整合性を判定できません。原本を確認してください。</AlertDescription></Alert> : null}</section>
    {children}
  </div>;
}
