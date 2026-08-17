"use client";

import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from "react";
import { FileUp, TriangleAlert } from "lucide-react";
import dynamic from "next/dynamic";
import { useRouter } from "next/navigation";

import { AnalysisStatus } from "@/components/document-analysis/analysis-status";
import { ExpenseAutoEntryEditor } from "@/components/expense-auto-entry/expense-auto-entry-editor";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useCurrentUser } from "@/components/workspace/current-user-context";
import {
  type AutoEntryReviewResponse,
} from "@/lib/auto-entry-review";
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
import {
  ExpenseAutoEntryApiError,
  createExpenseAutoEntryDraft,
} from "@/lib/expense-auto-entry-api";
import {
  type ExpenseAutoEntryForm,
  type ExpenseAutoEntryItem,
  createExpenseAutoEntryDraftRequest,
  createManualExpenseAutoEntryItem,
  getAutoEntryAttention,
  getResolvedAutoEntryFields,
  initializeExpenseAutoEntryForm,
  liveAutoEntryReviewToSource,
} from "@/lib/expense-auto-entry";
import {
  isExpenseInputValid,
} from "@/lib/expense-application";
import { createSynchronousMutationGuard } from "@/lib/synchronous-mutation-guard";

const today = new Date().toISOString().slice(0, 10);

const ExpenseAutoEntryDocumentPreview = dynamic(
  () => import("@/components/expense-auto-entry/expense-auto-entry-document-preview")
    .then((module) => module.ExpenseAutoEntryDocumentPreview),
  {
    ssr: false,
    loading: () => <p className="p-4 text-sm text-muted-foreground">プレビューを準備しています…</p>,
  },
);

function fileMetadata(file: File): AnalyzableFile {
  return { name: file.name, size: file.size, type: file.type };
}

function analysisErrorMessage(cause: unknown, fallback: string): string {
  return cause instanceof DocumentAnalysisApiError ? cause.message : fallback;
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
  const mutationGuardRef = useRef(createSynchronousMutationGuard());

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

  const reviewSource = useMemo(() => review ? liveAutoEntryReviewToSource(review) : null, [review]);
  const resolvedFields = useMemo(
    () => reviewSource && form ? getResolvedAutoEntryFields(reviewSource, form, confirmedPaths) : [],
    [confirmedPaths, form, reviewSource],
  );
  const attention = useMemo(() => getAutoEntryAttention(resolvedFields), [resolvedFields]);
  const valid = form !== null && isExpenseInputValid(
    form.application.category,
    form.application.title,
    form.application.purpose,
    form.application.expenseDate,
    form.application.items,
  );
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
    if (!mutationGuardRef.current.tryStart()) return;
    if (attention.length > 0 && !window.confirm(
      `未確認の項目が${attention.length}件あります。確認画面でも引き続き修正できます。\nこのまま確認画面へ進みますか？`,
    )) {
      mutationGuardRef.current.finish();
      return;
    }

    setSubmitting(true);
    setSubmitError(null);
    try {
      const response = await createExpenseAutoEntryDraft(
        createExpenseAutoEntryDraftRequest(state.job.id, form, resolvedFields),
      );
      router.push(`/expenses/auto-entry/confirm/${encodeURIComponent(response.application.id)}`);
    } catch (cause) {
      if (!(cause instanceof AuthenticationRequiredError)) {
        setSubmitError(cause instanceof ExpenseAutoEntryApiError
          && (cause.status === 503 || cause.code === "BACKEND_UNAVAILABLE")
          ? "作成結果を確認できませんでした。入力内容は保持されています。同じ分析の「決定」は安全に再実行できます。"
          : cause instanceof Error ? cause.message : "自動入力の経費下書きを作成できませんでした。");
      }
    } finally {
      setSubmitting(false);
      mutationGuardRef.current.finish();
    }
  }

  if (!available) {
    return <main className="p-4 md:p-8"><div className="mx-auto max-w-3xl rounded-md border bg-card p-6 text-card-foreground"><h1 className="text-lg font-semibold">請求/注文書申請（自動入力）</h1><p className="mt-2 text-sm text-muted-foreground">この機能は現在利用できません。</p></div></main>;
  }

  const previewFile = state.selectedFile ?? (browserFile ? fileMetadata(browserFile) : null);
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
          <div className="min-h-[36rem] overflow-hidden rounded-md border bg-card text-card-foreground"><ExpenseAutoEntryDocumentPreview file={previewFile} objectUrl={objectUrl} pages={review?.pages ?? []} resolvedFields={resolvedFields} serverUrl={null} /></div>
          <section className="min-w-0 rounded-md border bg-card text-card-foreground">
            <div className="border-b p-4"><AnalysisStatus state={state} viewLoading={reviewLoading} /></div>
            <div className="space-y-6 p-4">
              {submitError ? <Alert variant="destructive"><TriangleAlert /><AlertTitle>作成できませんでした</AlertTitle><AlertDescription>{submitError}</AlertDescription></Alert> : null}
              {form && reviewSource ? <ExpenseAutoEntryEditor confirmedPaths={confirmedPaths} form={form} onAddItem={() => updateApplication({ items: [...form.application.items, createManualExpenseAutoEntryItem(form.application.expenseDate)] })} onApplicationChange={updateApplication} onConfirmationChange={setConfirmed} onDeleteItem={(index) => updateApplication({ items: form.application.items.filter((_, itemIndex) => itemIndex !== index) })} onDocumentChange={updateDocument} onExpenseDateChange={changeExpenseDate} onItemChange={updateItem} onShowAttentionOnlyChange={setShowAttentionOnly} resolvedFields={resolvedFields} reviewSource={reviewSource} showAttentionOnly={showAttentionOnly}><div className="flex justify-end"><Button disabled={!canDecide} onClick={() => void decide()} type="button">{submitting ? "作成中…" : "決定"}</Button></div></ExpenseAutoEntryEditor> : <p className="text-sm text-muted-foreground">文書を選択すると、分析完了後に入力フォームを表示します。</p>}
            </div>
          </section>
        </div>
      </div>
    </main>
  );
}
