"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";

import { DocumentPreview } from "@/components/document-analysis/document-preview";
import { ExpenseAutoEntryEditor } from "@/components/expense-auto-entry/expense-auto-entry-editor";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button, LinkButton } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { useCurrentUser } from "@/components/workspace/current-user-context";
import { canEditExpenseAutoEntryDraft } from "@/lib/backend-api";
import { AuthenticationRequiredError, fetchBackend } from "@/lib/backend-browser-client";
import type { AnalyzableFile } from "@/lib/document-analysis";
import {
  ExpenseAutoEntryApiError,
  getExpenseAutoEntryDraft,
  updateExpenseAutoEntryDraft,
  type ExpenseAutoEntryDraftResponse,
} from "@/lib/expense-auto-entry-api";
import {
  type ExpenseAutoEntryForm,
  type ExpenseAutoEntryItem,
  confirmedAutoEntryFieldPaths,
  createExpenseAutoEntryDraftUpdateRequest,
  createManualExpenseAutoEntryItem,
  getAutoEntryAttention,
  getResolvedAutoEntryFields,
  persistedAutoEntryOriginalToSource,
  persistedExpenseAutoEntryDraftToForm,
} from "@/lib/expense-auto-entry";
import { isExpenseInputValid } from "@/lib/expense-application";
import type { ExpenseAttachment } from "@/lib/expense-attachment";
import {
  ExpenseSubmitResultError,
  submitExpenseApplicationWithReconciliation,
} from "@/lib/expense-submit";
import { createSynchronousMutationGuard } from "@/lib/synchronous-mutation-guard";

type ErrorBody = { code?: string; message?: string };

export function ExpenseAutoEntryConfirmation({ draftId }: { draftId: string }) {
  const currentUser = useCurrentUser();
  const router = useRouter();
  const available = canEditExpenseAutoEntryDraft(currentUser);
  const [draft, setDraft] = useState<ExpenseAutoEntryDraftResponse | null>(null);
  const [form, setForm] = useState<ExpenseAutoEntryForm | null>(null);
  const [confirmedPaths, setConfirmedPaths] = useState<Set<string>>(new Set());
  const [showAttentionOnly, setShowAttentionOnly] = useState(true);
  const [sourceFile, setSourceFile] = useState<AnalyzableFile | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [processing, setProcessing] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [reloadNotice, setReloadNotice] = useState<string | null>(null);
  const [submitResultUnknown, setSubmitResultUnknown] = useState(false);
  const mutationGuardRef = useRef(createSynchronousMutationGuard());

  const applyDraft = useCallback((nextDraft: ExpenseAutoEntryDraftResponse) => {
    setDraft(nextDraft);
    setForm(persistedExpenseAutoEntryDraftToForm(nextDraft));
    setConfirmedPaths(confirmedAutoEntryFieldPaths(nextDraft.autoEntry.fields));
    setShowAttentionOnly(true);
    setDirty(false);
    setSaved(false);
    setReloadNotice(null);
  }, []);

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true);
    setError(null);
    setPreviewError(null);
    try {
      const nextDraft = await getExpenseAutoEntryDraft(draftId, signal);
      if (signal?.aborted) return;
      applyDraft(nextDraft);
      try {
        const response = await fetchBackend(`/api/backend/expense-applications/${encodeURIComponent(draftId)}/attachments`, {
          cache: "no-store",
          signal,
        });
        const attachments = (await response.json().catch(() => [])) as ExpenseAttachment[] & ErrorBody;
        if (!response.ok) throw new Error("証憑を取得できませんでした。");
        const source = attachments.find((attachment) => attachment.id === nextDraft.autoEntry.sourceAttachmentId);
        if (!source) throw new Error("元の証憑を取得できませんでした。経費申請詳細で添付状況を確認してください。");
        setSourceFile({ name: source.originalFileName, size: source.fileSize, type: source.contentType });
      } catch (cause) {
        if (!signal?.aborted && !(cause instanceof AuthenticationRequiredError)) {
          setSourceFile(null);
          setPreviewError(cause instanceof Error ? cause.message : "元の証憑を取得できませんでした。");
        }
      }
    } catch (cause) {
      if (!signal?.aborted && !(cause instanceof AuthenticationRequiredError)) {
        setError(cause instanceof Error ? cause.message : "申請内容を読み込めませんでした。");
      }
    } finally {
      if (!signal?.aborted) setLoading(false);
    }
  }, [applyDraft, draftId]);

  useEffect(() => {
    if (!available) return;
    const controller = new AbortController();
    const timeout = window.setTimeout(() => void load(controller.signal), 0);
    return () => { controller.abort(); window.clearTimeout(timeout); };
  }, [available, load]);

  const resolvedFields = useMemo(
    () => draft && form ? getResolvedAutoEntryFields(persistedAutoEntryOriginalToSource(draft.autoEntry.original), form, confirmedPaths) : [],
    [confirmedPaths, draft, form],
  );
  const attention = useMemo(() => getAutoEntryAttention(resolvedFields), [resolvedFields]);
  const valid = form !== null && isExpenseInputValid(form.application.category, form.application.title, form.application.purpose, form.application.expenseDate, form.application.items);
  const editable = draft?.application.status === "DRAFT" || draft?.application.status === "RETURNED";

  function markDirty() {
    setDirty(true);
    setSaved(false);
    setError(null);
  }

  function updateDocument(values: Partial<ExpenseAutoEntryForm["document"]>, path: string) {
    setForm((current) => current ? { ...current, document: { ...current.document, ...values } } : current);
    setConfirmedPaths((current) => { const next = new Set(current); next.delete(path); return next; });
    markDirty();
  }

  function updateApplication(values: Partial<ExpenseAutoEntryForm["application"]>) {
    setForm((current) => current ? { ...current, application: { ...current.application, ...values } } : current);
    markDirty();
  }

  function changeExpenseDate(expenseDate: string) {
    setForm((current) => current ? { ...current, application: { ...current.application, expenseDate, items: current.application.items.map((item) => ({ ...item, expenseDate })) } } : current);
    markDirty();
  }

  function updateItem(index: number, values: Partial<ExpenseAutoEntryItem>) {
    const item = form?.application.items[index];
    setForm((current) => current ? { ...current, application: { ...current.application, items: current.application.items.map((value, itemIndex) => itemIndex === index ? { ...value, ...values } : value) } } : current);
    if (item?.sourceLineItemIndex !== null && item?.sourceLineItemIndex !== undefined) {
      setConfirmedPaths((current) => {
        const next = new Set(current);
        if (Object.hasOwn(values, "description")) next.delete(`document.lineItems[${item.sourceLineItemIndex}].itemDescription`);
        if (Object.hasOwn(values, "amount")) next.delete(`document.lineItems[${item.sourceLineItemIndex}].lineAmount`);
        return next;
      });
    }
    markDirty();
  }

  function setConfirmed(path: string, checked: boolean) {
    setConfirmedPaths((current) => { const next = new Set(current); if (checked) next.add(path); else next.delete(path); return next; });
    markDirty();
  }

  async function persistDraft(): Promise<ExpenseAutoEntryDraftResponse | null> {
    if (!draft || !form || !valid) {
      if (!valid) setError("共通項目、カテゴリ別項目、1円以上の明細金額を入力してください。");
      return null;
    }
    setError(null);
    setReloadNotice(null);
    try {
      const response = await updateExpenseAutoEntryDraft(draft.application.id, createExpenseAutoEntryDraftUpdateRequest(draft, form, resolvedFields));
      applyDraft(response);
      setSaved(true);
      return response;
    } catch (cause) {
      if (!(cause instanceof AuthenticationRequiredError)) {
        const conflict = cause instanceof ExpenseAutoEntryApiError
          && cause.code === "OPTIMISTIC_LOCK_CONFLICT";
        const ambiguous = cause instanceof ExpenseAutoEntryApiError
          && (cause.status === 503 || cause.code === "BACKEND_UNAVAILABLE");
        const message = ambiguous
          ? "保存結果を確認できませんでした。最新内容を再読み込みしてください。"
          : cause instanceof Error ? cause.message : "自動入力の経費下書きを保存できませんでした。";
        setReloadNotice(conflict || ambiguous ? message : null);
        setError(message);
      }
      return null;
    }
  }

  async function save(): Promise<ExpenseAutoEntryDraftResponse | null> {
    if (!draft || !form || !valid || processing) {
      if (!valid) setError("共通項目、カテゴリ別項目、1円以上の明細金額を入力してください。");
      return null;
    }
    if (!mutationGuardRef.current.tryStart()) return null;
    setProcessing(true);
    try {
      return await persistDraft();
    } finally {
      setProcessing(false);
      mutationGuardRef.current.finish();
    }
  }

  async function submit() {
    if (!draft || !form || !valid || processing || !editable || submitResultUnknown) {
      if (!valid) setError("共通項目、カテゴリ別項目、1円以上の明細金額を入力してください。");
      return;
    }
    if (!mutationGuardRef.current.tryStart()) return;
    if (attention.length > 0 && !window.confirm(`確認が完了していない項目が${attention.length}件あります。\n\n${attention.map((field) => `・${field.label}`).join("\n")}\n\nこのまま申請しますか？`)) {
      mutationGuardRef.current.finish();
      return;
    }
    setProcessing(true);
    setError(null);
    try {
      let latestDraft = draft;
      if (dirty) {
        const savedDraft = await persistDraft();
        if (!savedDraft) return;
        latestDraft = savedDraft;
      }
      const action = latestDraft.application.status === "RETURNED" ? "resubmit" : "submit";
      await submitExpenseApplicationWithReconciliation(
        latestDraft.application.id,
        action,
      );
      router.push(`/expenses/${latestDraft.application.id}`);
    } catch (cause) {
      if (!(cause instanceof AuthenticationRequiredError)) {
        if (cause instanceof ExpenseSubmitResultError && cause.resultUnknown) {
          setSubmitResultUnknown(true);
          setError(cause.message);
        } else {
          setError(`下書きは保存されていますが、申請できませんでした。${cause instanceof Error ? ` ${cause.message}` : ""}`);
        }
      }
    } finally {
      setProcessing(false);
      mutationGuardRef.current.finish();
    }
  }

  if (!available) return <main className="p-4 md:p-8"><div className="mx-auto max-w-3xl rounded-md border bg-card p-6 text-card-foreground"><h1 className="text-lg font-semibold">自動入力の確認</h1><p className="mt-2 text-sm text-muted-foreground">この機能は現在利用できません。</p></div></main>;
  if (loading) return <main className="p-4 md:p-8"><Card><CardContent>申請内容を読み込んでいます…</CardContent></Card></main>;
  if (!draft || !form) return <main className="p-4 md:p-8"><Card><CardContent className="text-destructive">{error ?? "申請内容を読み込めませんでした。"}</CardContent></Card></main>;
  if (!editable) return <main className="p-4 md:p-8"><div className="mx-auto max-w-3xl space-y-4"><Card><CardContent>この経費申請は現在編集できません。</CardContent></Card><LinkButton href={`/expenses/${draft.application.id}`}>経費申請詳細へ</LinkButton></div></main>;

  const sourceUrl = sourceFile ? `/api/backend/expense-applications/${encodeURIComponent(draft.application.id)}/attachments/${encodeURIComponent(draft.autoEntry.sourceAttachmentId)}/content` : null;
  return <main className="p-4 md:p-8"><div className="mx-auto max-w-[96rem] space-y-4"><div><h1 className="text-xl font-semibold">自動入力の確認</h1><p className="mt-1 text-sm text-muted-foreground">保存済みの下書きと証憑を確認して、最終編集・申請を行います。</p></div>{error ? <Alert variant="destructive"><AlertTitle>{submitResultUnknown ? "申請結果の確認が必要です" : "処理できませんでした"}</AlertTitle><AlertDescription>{error}</AlertDescription></Alert> : null}{submitResultUnknown ? <div className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-destructive/40 p-3"><p className="text-sm">再申請を再実行せず、現在の状態と承認履歴を確認してください。</p><LinkButton href={`/expenses/${draft.application.id}`}>申請詳細を確認</LinkButton></div> : null}{reloadNotice ? <div className="flex items-center gap-3 rounded-md border border-destructive/40 p-3"><p className="text-sm">{reloadNotice}</p><Button onClick={() => void load()} size="sm" type="button" variant="outline">再読み込み</Button></div> : null}<div className="grid min-w-0 gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(28rem,1fr)]"><div className="min-h-[36rem] overflow-hidden rounded-md border bg-card text-card-foreground"><DocumentPreview file={sourceFile} objectUrl={null} serverUrl={sourceUrl} />{previewError ? <p className="border-t p-3 text-sm text-destructive">{previewError}</p> : null}</div><section className="min-w-0 rounded-md border bg-card p-4 text-card-foreground"><ExpenseAutoEntryEditor confirmedPaths={confirmedPaths} form={form} onAddItem={() => updateApplication({ items: [...form.application.items, createManualExpenseAutoEntryItem(form.application.expenseDate)] })} onApplicationChange={updateApplication} onConfirmationChange={setConfirmed} onDeleteItem={(index) => updateApplication({ items: form.application.items.filter((_, itemIndex) => itemIndex !== index) })} onDocumentChange={updateDocument} onExpenseDateChange={changeExpenseDate} onItemChange={updateItem} onShowAttentionOnlyChange={setShowAttentionOnly} resolvedFields={resolvedFields} showAttentionOnly={showAttentionOnly}><div className="flex flex-wrap items-center justify-end gap-3"><span className="text-sm text-emerald-700">{saved ? "保存しました" : ""}</span><Button disabled={processing || !dirty || !valid} onClick={() => void save()} type="button" variant="outline">{processing ? "保存中…" : "下書き保存"}</Button><Button disabled={processing || !valid || submitResultUnknown} onClick={() => void submit()} type="button">{processing ? "処理中…" : draft.application.status === "RETURNED" ? "再申請" : "申請"}</Button></div></ExpenseAutoEntryEditor></section></div></div></main>;
}
