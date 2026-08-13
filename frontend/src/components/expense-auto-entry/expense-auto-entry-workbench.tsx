"use client";

import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from "react";
import { FileUp, Plus, Trash2, TriangleAlert } from "lucide-react";
import { useRouter } from "next/navigation";

import { AnalysisStatus } from "@/components/document-analysis/analysis-status";
import { DocumentPreview } from "@/components/document-analysis/document-preview";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useCurrentUser } from "@/components/workspace/current-user-context";
import {
  type AutoEntryField,
  type AutoEntryReviewResponse,
} from "@/lib/auto-entry-review";
import {
  formatAutoEntryConfidence,
  formatAutoEntryFinding,
  formatAutoEntrySources,
} from "@/lib/auto-entry-review-display";
import { canUseExpenseAutoEntry } from "@/lib/backend-api";
import { AuthenticationRequiredError } from "@/lib/backend-browser-client";
import {
  type DocumentAnalysisJob,
  DocumentAnalysisApiError,
  createDocumentAnalysis,
  getAutoEntryReview,
  getDocumentAnalysis,
} from "@/lib/document-analysis-api";
import {
  type AnalyzableFile,
  DOCUMENT_ANALYSIS_ACCEPT,
  documentAnalysisReducer,
  initialDocumentAnalysisState,
  isDocumentAnalysisProcessing,
  validateSingleDocumentSelection,
} from "@/lib/document-analysis";
import { createExpenseAutoEntryDraft } from "@/lib/expense-auto-entry-api";
import {
  type ExpenseAutoEntryForm,
  type ExpenseAutoEntryItem,
  type ResolvedAutoEntryField,
  autoEntryStatusLabel,
  createExpenseAutoEntryDraftRequest,
  createManualExpenseAutoEntryItem,
  getAutoEntryAttention,
  getResolvedAutoEntryFields,
  hasInvoiceTotalMismatch,
  initializeExpenseAutoEntryForm,
  shouldShowAutoEntryField,
} from "@/lib/expense-auto-entry";
import {
  categoryLabels,
  expenseCategories,
  isExpenseInputValid,
  totalExpenseAmount,
  type ExpenseCategory,
  yen,
} from "@/lib/expense-application";
import { cn } from "@/lib/utils";

const today = new Date().toISOString().slice(0, 10);

function fileMetadata(file: File): AnalyzableFile {
  return { name: file.name, size: file.size, type: file.type };
}

function analysisErrorMessage(cause: unknown, fallback: string): string {
  return cause instanceof DocumentAnalysisApiError ? cause.message : fallback;
}

function statusClass(status: AutoEntryField<unknown>["status"]): string {
  if (status === "OK") return "border-emerald-600/30 bg-emerald-600/10 text-emerald-700 dark:text-emerald-400";
  if (status === "REVIEW") return "border-amber-600/30 bg-amber-600/10 text-amber-700 dark:text-amber-400";
  return "border-destructive/30 bg-destructive/10 text-destructive";
}

function FieldMetadata({ field, resolution }: {
  field: AutoEntryField<string | number>;
  resolution: ResolvedAutoEntryField["resolution"];
}) {
  const details = [
    formatAutoEntryConfidence(field.confidence),
    formatAutoEntrySources(field.sources),
    ...field.findings.map(formatAutoEntryFinding),
  ].filter((value): value is string => value !== null);

  return (
    <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
      <Badge className={cn("border", statusClass(field.status))} variant="outline">
        {autoEntryStatusLabel(field.status)}
      </Badge>
      {resolution === "EDITED" ? <span className="font-medium text-foreground">修正済み</span> : null}
      {resolution === "CONFIRMED" ? <span className="font-medium text-foreground">確認済み</span> : null}
      {details.map((detail) => <span key={detail}>{detail}</span>)}
    </div>
  );
}

function AttentionReason({ field }: { field: ResolvedAutoEntryField }) {
  const reason = field.field.status === "MISSING"
    ? "未取得のため、必要に応じて入力してください"
    : field.field.findings.length > 0
      ? field.field.findings.map(formatAutoEntryFinding).join(" · ")
      : "原本を確認してください";
  const details = [
    formatAutoEntryConfidence(field.field.confidence),
    formatAutoEntrySources(field.field.sources),
  ].filter((value): value is string => value !== null);
  return <li className="rounded-md border bg-background px-3 py-2"><p className="font-medium">{field.label}</p><p className="mt-0.5 text-muted-foreground">{reason}</p>{details.length > 0 ? <p className="mt-0.5 text-muted-foreground">{details.join(" · ")}</p> : null}</li>;
}

export function ExpenseAutoEntryWorkbench() {
  const currentUser = useCurrentUser();
  const router = useRouter();
  const available = canUseExpenseAutoEntry(currentUser);
  const [state, dispatch] = useReducer(documentAnalysisReducer, initialDocumentAnalysisState);
  const [browserFile, setBrowserFile] = useState<File | null>(null);
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [review, setReview] = useState<AutoEntryReviewResponse | null>(null);
  const [reviewLoading, setReviewLoading] = useState(false);
  const [form, setForm] = useState<ExpenseAutoEntryForm | null>(null);
  const [confirmedPaths, setConfirmedPaths] = useState<Set<string>>(new Set());
  const [showAttentionOnly, setShowAttentionOnly] = useState(true);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const objectUrlRef = useRef<string | null>(null);

  const replaceBrowserFile = useCallback((file: File | null) => {
    if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
    objectUrlRef.current = file ? URL.createObjectURL(file) : null;
    setObjectUrl(objectUrlRef.current);
  }, []);

  useEffect(() => () => {
    if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
  }, []);

  const loadSucceededReview = useCallback(async (job: DocumentAnalysisJob, signal?: AbortSignal) => {
    setReviewLoading(true);
    try {
      const nextReview = await getAutoEntryReview(job.id, signal);
      if (signal?.aborted) return;
      setReview(nextReview);
      setForm(initializeExpenseAutoEntryForm(nextReview, today));
      setConfirmedPaths(new Set());
      setShowAttentionOnly(true);
      setSubmitError(null);
      dispatch({
        type: "view",
        job,
        result: {
          analysisId: job.id,
          provider: job.provider,
          modelId: job.modelId,
          providerApiVersion: job.providerApiVersion,
          markdown: "",
          paragraphs: [],
          tables: [],
        },
      });
    } catch (cause) {
      if (!signal?.aborted) {
        dispatch({ type: "fail", job, message: analysisErrorMessage(cause, "自動入力結果を取得できませんでした。") });
      }
    } finally {
      if (!signal?.aborted) setReviewLoading(false);
    }
  }, []);

  useEffect(() => {
    if (state.status !== "queued" && state.status !== "running") return;
    const controller = new AbortController();
    const timeout = window.setTimeout(async () => {
      try {
        const job = await getDocumentAnalysis(state.job.id, controller.signal, "AUTO_ENTRY");
        if (controller.signal.aborted) return;
        if (job.status === "SUCCEEDED") {
          void loadSucceededReview(job, controller.signal);
        } else {
          dispatch({ type: "job", job });
        }
      } catch (cause) {
        if (!controller.signal.aborted) {
          dispatch({ type: "fail", job: state.job, message: analysisErrorMessage(cause, "分析状態を取得できませんでした。") });
        }
      }
    }, 1_000);
    return () => { controller.abort(); window.clearTimeout(timeout); };
  }, [loadSucceededReview, state]);

  function selectFiles(files: FileList) {
    const validation = validateSingleDocumentSelection(files);
    if (!validation.valid) {
      setBrowserFile(null);
      replaceBrowserFile(null);
      setReview(null);
      setForm(null);
      setSubmitError(null);
      dispatch({ type: "reject", message: validation.message });
      return;
    }

    const file = files[0];
    setBrowserFile(file);
    replaceBrowserFile(file);
    setReview(null);
    setForm(null);
    setSubmitError(null);
    setReviewLoading(false);
    dispatch({ type: "select", file: fileMetadata(file), validation });
    dispatch({ type: "upload" });
    void (async () => {
      try {
        const job = await createDocumentAnalysis("CONTENT_UNDERSTANDING", file, undefined, "AUTO_ENTRY");
        dispatch({ type: "job", job });
      } catch (cause) {
        dispatch({ type: "fail", message: analysisErrorMessage(cause, "分析要求を開始できませんでした。") });
      }
    })();
  }

  const resolvedFields = useMemo(
    () => review && form ? getResolvedAutoEntryFields(review, form, confirmedPaths) : [],
    [confirmedPaths, form, review],
  );
  const attention = useMemo(() => getAutoEntryAttention(resolvedFields), [resolvedFields]);
  const resolvedByPath = useMemo(
    () => new Map(resolvedFields.map((field) => [field.path, field])),
    [resolvedFields],
  );
  const valid = form !== null && isExpenseInputValid(
    form.application.category,
    form.application.title,
    form.application.purpose,
    form.application.expenseDate,
    form.application.items,
  );
  const total = form ? totalExpenseAmount(form.application.items) : 0;
  const totalMismatch = form
    ? hasInvoiceTotalMismatch(form.document.invoiceTotalAmount, form.application.items)
    : false;
  const analysisProcessing = isDocumentAnalysisProcessing(state.status) || reviewLoading;
  const canDecide = state.status === "succeeded" && review !== null && form !== null && valid && !submitting;

  function updateDocument(values: Partial<ExpenseAutoEntryForm["document"]>, path: string) {
    setForm((current) => current ? { ...current, document: { ...current.document, ...values } } : current);
    setConfirmedPaths((current) => {
      const next = new Set(current);
      next.delete(path);
      return next;
    });
  }

  function updateApplication(values: Partial<ExpenseAutoEntryForm["application"]>) {
    setForm((current) => current ? { ...current, application: { ...current.application, ...values } } : current);
  }

  function changeExpenseDate(expenseDate: string) {
    setForm((current) => current ? {
      ...current,
      application: {
        ...current.application,
        expenseDate,
        items: current.application.items.map((item) => ({ ...item, expenseDate })),
      },
    } : current);
  }

  function updateItem(index: number, values: Partial<ExpenseAutoEntryItem>) {
    const item = form?.application.items[index];
    setForm((current) => current ? {
      ...current,
      application: {
        ...current.application,
        items: current.application.items.map((value, itemIndex) => itemIndex === index ? { ...value, ...values } : value),
      },
    } : current);
    if (item?.sourceLineItemIndex !== null && item?.sourceLineItemIndex !== undefined) {
      if (Object.hasOwn(values, "description")) {
        setConfirmedPaths((current) => {
          const next = new Set(current);
          next.delete(`document.lineItems[${item.sourceLineItemIndex}].itemDescription`);
          return next;
        });
      }
      if (Object.hasOwn(values, "amount")) {
        setConfirmedPaths((current) => {
          const next = new Set(current);
          next.delete(`document.lineItems[${item.sourceLineItemIndex}].lineAmount`);
          return next;
        });
      }
    }
  }

  function setConfirmed(path: string, checked: boolean) {
    setConfirmedPaths((current) => {
      const next = new Set(current);
      if (checked) next.add(path);
      else next.delete(path);
      return next;
    });
  }

  async function decide() {
    if (!form || !review || !state.job || !valid || submitting) return;
    if (attention.length > 0 && !window.confirm(
      `未確認の項目が${attention.length}件あります。確認画面でも引き続き修正できます。\nこのまま確認画面へ進みますか？`,
    )) return;

    setSubmitting(true);
    setSubmitError(null);
    try {
      const response = await createExpenseAutoEntryDraft(
        createExpenseAutoEntryDraftRequest(state.job.id, form, resolvedFields),
      );
      router.push(`/expenses/auto-entry/confirm/${encodeURIComponent(response.application.id)}`);
    } catch (cause) {
      if (!(cause instanceof AuthenticationRequiredError)) {
        setSubmitError(cause instanceof Error ? cause.message : "自動入力の経費下書きを作成できませんでした。");
      }
    } finally {
      setSubmitting(false);
    }
  }

  if (!available) {
    return <main className="p-4 md:p-8"><div className="mx-auto max-w-3xl rounded-md border bg-card p-6 text-card-foreground"><h1 className="text-lg font-semibold">請求/注文書申請（自動入力）</h1><p className="mt-2 text-sm text-muted-foreground">この機能は現在利用できません。</p></div></main>;
  }

  const previewFile = state.selectedFile ?? (browserFile ? fileMetadata(browserFile) : null);
  const documentFields = [
    { label: "請求社 / 発行元", path: "document.issuerName", value: form?.document.issuerName ?? "", type: "text" as const },
    { label: "インボイス登録番号", path: "document.issuerTaxRegistrationNumber", value: form?.document.issuerTaxRegistrationNumber ?? "", type: "text" as const },
    { label: "総請求額（円）", path: "document.totalAmount", value: form?.document.invoiceTotalAmount ?? "", type: "number" as const },
  ];

  return (
    <main className="p-4 md:p-8">
      <div className="mx-auto max-w-[96rem] space-y-4">
        <div className="rounded-md border bg-card p-4 text-card-foreground">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div><h1 className="text-xl font-semibold">請求/注文書申請（自動入力）</h1><p className="mt-1 text-sm text-muted-foreground">文書を読み込むと分析を開始し、確認が必要な値を中心に入力できます。</p></div>
            <input accept={DOCUMENT_ANALYSIS_ACCEPT} className="sr-only" disabled={analysisProcessing} id="expense-auto-entry-file" onChange={(event) => { if (event.target.files) selectFiles(event.target.files); event.target.value = ""; }} ref={inputRef} type="file" />
            <Button disabled={analysisProcessing} onClick={() => inputRef.current?.click()} type="button"><FileUp data-icon="inline-start" />文書を読み込む</Button>
          </div>
          <p className="mt-3 text-sm text-muted-foreground">{previewFile?.name ?? "PDF、JPEG、PNGを1件選択できます。"}</p>
        </div>

        <div className="grid min-w-0 gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(28rem,1fr)]">
          <div className="min-h-[36rem] overflow-hidden rounded-md border bg-card text-card-foreground"><DocumentPreview file={previewFile} objectUrl={objectUrl} serverUrl={null} /></div>
          <section className="min-w-0 rounded-md border bg-card text-card-foreground">
            <div className="border-b p-4"><AnalysisStatus state={state} viewLoading={reviewLoading} /></div>
            <div className="space-y-6 p-4">
              {submitError ? <Alert variant="destructive"><TriangleAlert /><AlertTitle>作成できませんでした</AlertTitle><AlertDescription>{submitError}</AlertDescription></Alert> : null}
              {form && review ? <>
                <section className="rounded-md border bg-amber-50/50 p-4 dark:bg-amber-950/10">
                  <div className="flex flex-wrap items-center justify-between gap-3"><div><h2 className="font-semibold">⚠ 確認が必要な項目 {attention.length}件</h2><p className="mt-1 text-xs text-muted-foreground">AIの確認状況は申請の必須入力とは別です。</p></div><div className="flex gap-1"><Button aria-pressed={showAttentionOnly} onClick={() => setShowAttentionOnly(true)} size="sm" type="button" variant={showAttentionOnly ? "secondary" : "ghost"}>要確認のみ</Button><Button aria-pressed={!showAttentionOnly} onClick={() => setShowAttentionOnly(false)} size="sm" type="button" variant={!showAttentionOnly ? "secondary" : "ghost"}>すべて</Button></div></div>
                  {attention.length > 0 ? <ul className="mt-3 space-y-2 text-xs"><>{attention.map((field) => <AttentionReason field={field} key={field.path} />)}</></ul> : <p className="mt-3 text-sm text-muted-foreground">現在、確認が必要なAI入力値はありません。</p>}
                </section>

                <section className="space-y-3"><h2 className="font-semibold">請求書・注文書の読み取り値</h2>{documentFields.map((documentField) => {
                  const resolved = resolvedByPath.get(documentField.path);
                  if (!resolved || !shouldShowAutoEntryField(resolved, showAttentionOnly)) return null;
                  const reviewValue = resolved.field;
                  const isReviewConfirmation = reviewValue.status === "REVIEW" && resolved.resolution !== "EDITED";
                  return <div className="rounded-md border p-3" key={documentField.path}><label className="grid gap-1 text-sm">{documentField.label}<Input maxLength={documentField.path === "document.issuerTaxRegistrationNumber" ? 100 : 500} min={documentField.type === "number" ? 1 : undefined} onChange={(event) => updateDocument(documentField.path === "document.issuerName" ? { issuerName: event.target.value } : documentField.path === "document.issuerTaxRegistrationNumber" ? { issuerTaxRegistrationNumber: event.target.value } : { invoiceTotalAmount: event.target.value === "" ? null : Number(event.target.value) }, documentField.path)} step={documentField.type === "number" ? 1 : undefined} type={documentField.type} value={documentField.value} /></label><FieldMetadata field={reviewValue} resolution={resolved.resolution} />{isReviewConfirmation ? <label className="mt-2 flex items-center gap-2 text-sm"><input checked={confirmedPaths.has(documentField.path)} onChange={(event) => setConfirmed(documentField.path, event.target.checked)} type="checkbox" />原本を確認しました</label> : null}</div>;
                })}</section>

                <section className="space-y-4"><h2 className="font-semibold">経費申請の入力</h2><div className="grid gap-3 md:grid-cols-2"><label className="grid gap-1 text-sm">経費区分<select className="h-8 rounded-md border bg-background px-2 text-sm" onChange={(event) => updateApplication({ category: event.target.value as ExpenseCategory })} value={form.application.category}>{expenseCategories.map((category) => <option key={category} value={category}>{categoryLabels[category]}</option>)}</select></label><label className="grid gap-1 text-sm">利用日<Input onChange={(event) => changeExpenseDate(event.target.value)} required type="date" value={form.application.expenseDate} /></label><label className="grid gap-1 text-sm md:col-span-2">件名<Input maxLength={200} onChange={(event) => updateApplication({ title: event.target.value })} required value={form.application.title} /></label><label className="grid gap-1 text-sm md:col-span-2">利用目的<textarea className="min-h-20 rounded-md border bg-background p-2 text-sm" onChange={(event) => updateApplication({ purpose: event.target.value })} required value={form.application.purpose} /></label><label className="grid gap-1 text-sm md:col-span-2">備考<textarea className="min-h-16 rounded-md border bg-background p-2 text-sm" onChange={(event) => updateApplication({ remarks: event.target.value })} value={form.application.remarks} /></label></div></section>

                <section className="space-y-3"><div className="flex items-center justify-between gap-3"><h2 className="font-semibold">経費明細</h2><Button onClick={() => updateApplication({ items: [...form.application.items, createManualExpenseAutoEntryItem(form.application.expenseDate)] })} size="sm" type="button" variant="outline"><Plus data-icon="inline-start" />明細追加</Button></div>{form.application.items.map((item, index) => {
                  const description = item.sourceLineItemIndex === null ? undefined : resolvedByPath.get(`document.lineItems[${item.sourceLineItemIndex}].itemDescription`);
                  const amount = item.sourceLineItemIndex === null ? undefined : resolvedByPath.get(`document.lineItems[${item.sourceLineItemIndex}].lineAmount`);
                  const isDescriptionReviewConfirmation = description?.field.status === "REVIEW" && description.resolution !== "EDITED";
                  const isAmountReviewConfirmation = amount?.field.status === "REVIEW" && amount.resolution !== "EDITED";
                  return <div className="space-y-3 rounded-md border p-3" key={`${item.sourceLineItemIndex ?? "manual"}-${index}`}><div className="grid gap-3 md:grid-cols-2"><label className="grid gap-1 text-sm">内容<Input maxLength={500} onChange={(event) => updateItem(index, { description: event.target.value })} required value={item.description} /></label><label className="grid gap-1 text-sm">金額（円）<Input min={1} onChange={(event) => updateItem(index, { amount: Number(event.target.value) })} required step={1} type="number" value={item.amount || ""} /></label></div>{description ? <><FieldMetadata field={description.field} resolution={description.resolution} />{isDescriptionReviewConfirmation ? <label className="flex items-center gap-2 text-sm"><input checked={confirmedPaths.has(description.path)} onChange={(event) => setConfirmed(description.path, event.target.checked)} type="checkbox" />内容の原本を確認しました</label> : null}</> : null}{amount ? <><FieldMetadata field={amount.field} resolution={amount.resolution} />{isAmountReviewConfirmation ? <label className="flex items-center gap-2 text-sm"><input checked={confirmedPaths.has(amount.path)} onChange={(event) => setConfirmed(amount.path, event.target.checked)} type="checkbox" />金額の原本を確認しました</label> : null}</> : null}
                    {form.application.category === "MEAL" || form.application.category === "TRAINING" || form.application.category === "CERTIFICATION" ? <label className="grid gap-1 text-sm">{form.application.category === "MEAL" ? "店舗名" : form.application.category === "TRAINING" ? "主催者" : "試験実施団体"}<Input onChange={(event) => updateItem(index, { merchantName: event.target.value })} required value={item.merchantName} /></label> : null}{form.application.category === "MEAL" ? <label className="grid gap-1 text-sm">参加者<Input onChange={(event) => updateItem(index, { participants: event.target.value })} required value={item.participants} /></label> : null}{form.application.category === "TRANSPORTATION" ? <div className="grid gap-3 md:grid-cols-3"><label className="grid gap-1 text-sm">交通手段<Input onChange={(event) => updateItem(index, { transportationType: event.target.value })} required value={item.transportationType} /></label><label className="grid gap-1 text-sm">出発地<Input onChange={(event) => updateItem(index, { origin: event.target.value })} required value={item.origin} /></label><label className="grid gap-1 text-sm">到着地<Input onChange={(event) => updateItem(index, { destination: event.target.value })} required value={item.destination} /></label></div> : null}<div className="flex justify-end"><Button aria-label={`明細${index + 1}を削除`} onClick={() => updateApplication({ items: form.application.items.filter((_, itemIndex) => itemIndex !== index) })} size="sm" type="button" variant="ghost"><Trash2 data-icon="inline-start" />削除</Button></div></div>;
                })}<p className="text-right text-lg font-semibold">申請明細合計 {yen(total)}</p>{totalMismatch ? <Alert><TriangleAlert /><AlertTitle>請求書総額と申請明細合計が一致しません</AlertTitle><AlertDescription>明細または請求書総額を自動で変更することはありません。</AlertDescription></Alert> : null}</section>
                <div className="flex justify-end"><Button disabled={!canDecide} onClick={() => void decide()} type="button">{submitting ? "作成中…" : "決定"}</Button></div>
              </> : <p className="text-sm text-muted-foreground">文書を選択すると、分析完了後に入力フォームを表示します。</p>}
            </div>
          </section>
        </div>
      </div>
    </main>
  );
}
