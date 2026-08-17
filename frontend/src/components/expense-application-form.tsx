"use client";

import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { Plus, Trash2 } from "lucide-react";

import { Button, LinkButton } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { AuthenticationRequiredError, fetchBackend } from "@/lib/backend-browser-client";
import {
  categoryLabels,
  expenseCategories,
  expenseErrorMessage,
  isExpenseInputValid,
  totalExpenseAmount,
  type ExpenseApplication,
  type ExpenseCategory,
  type ExpenseItem,
  yen,
} from "@/lib/expense-application";
import {
  ExpenseSubmitResultError,
  submitExpenseApplicationWithReconciliation,
} from "@/lib/expense-submit";
import { createSynchronousMutationGuard } from "@/lib/synchronous-mutation-guard";

const today = new Date().toISOString().slice(0, 10);
const emptyItem = (): ExpenseItem => ({
  expenseDate: today,
  description: "",
  amount: 0,
  merchantName: "",
  origin: "",
  destination: "",
  transportationType: "",
  participants: "",
});

type ErrorBody = { code?: string; message?: string };

export function ExpenseApplicationForm({ applicationId }: { applicationId?: string }) {
  const router = useRouter();
  const [category, setCategory] = useState<ExpenseCategory>("TRANSPORTATION");
  const [title, setTitle] = useState("");
  const [purpose, setPurpose] = useState("");
  const [expenseDate, setExpenseDate] = useState(today);
  const [remarks, setRemarks] = useState("");
  const [items, setItems] = useState<ExpenseItem[]>([emptyItem()]);
  const [persistedApplicationId, setPersistedApplicationId] = useState(applicationId);
  const [version, setVersion] = useState<number | undefined>();
  const [originalStatus, setOriginalStatus] = useState<ExpenseApplication["status"]>("DRAFT");
  const [loading, setLoading] = useState(Boolean(applicationId));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitResultUnknownApplicationId, setSubmitResultUnknownApplicationId] = useState<string | null>(null);
  const mutationGuardRef = useRef(createSynchronousMutationGuard());

  useEffect(() => {
    if (!applicationId) return;
    const controller = new AbortController();
    fetchBackend(`/api/backend/expense-applications/${applicationId}`, {
      cache: "no-store", signal: controller.signal,
    }).then(async (response) => {
      if (!response.ok) throw new Error("load");
      const application = (await response.json()) as ExpenseApplication;
      if (!application.editable) throw new Error("not-editable");
      setCategory(application.category);
      setTitle(application.title);
      setPurpose(application.purpose);
      setExpenseDate(application.expenseDate);
      setRemarks(application.remarks ?? "");
      setItems(application.items);
      setPersistedApplicationId(application.id);
      setVersion(application.version);
      setOriginalStatus(application.status);
      setLoading(false);
    }).catch((cause) => {
      if (!controller.signal.aborted && !(cause instanceof AuthenticationRequiredError)) {
        setError("申請内容を読み込めませんでした。");
        setLoading(false);
      }
    });
    return () => controller.abort();
  }, [applicationId]);

  const total = useMemo(() => totalExpenseAmount(items), [items]);
  const valid = isExpenseInputValid(category, title, purpose, expenseDate, items);

  function updateItem(index: number, values: Partial<ExpenseItem>) {
    setItems((current) => current.map((item, itemIndex) =>
      itemIndex === index ? { ...item, ...values } : item));
  }

  async function persist(submit: boolean) {
    if (submitResultUnknownApplicationId) return;
    if (!valid) {
      setError("共通項目、カテゴリ別項目、1円以上の明細金額を入力してください。");
      return;
    }
    if (!mutationGuardRef.current.tryStart()) return;
    if (submit && !window.confirm("申請後は承認待ちになります。申請しますか？")) {
      mutationGuardRef.current.finish();
      return;
    }
    setSaving(true);
    setError(null);
    const payload = { category, title, purpose, expenseDate, remarks, items, version };
    const saveTargetApplicationId = persistedApplicationId;
    let applicationIdForResult = persistedApplicationId;
    try {
      const saveResponse = await fetchBackend(
        saveTargetApplicationId
          ? `/api/backend/expense-applications/${saveTargetApplicationId}`
          : "/api/backend/expense-applications",
        {
          method: saveTargetApplicationId ? "PUT" : "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload),
        },
      );
      const saved = (await saveResponse.json()) as ExpenseApplication & ErrorBody;
      if (!saveResponse.ok) {
        throw new Error(expenseErrorMessage(saved.code, saved.message ?? "下書きを保存できませんでした。"));
      }
      applicationIdForResult = saved.id;
      setPersistedApplicationId(saved.id);
      setVersion(saved.version);
      setOriginalStatus(saved.status);
      if (!submit) {
        router.push(`/expenses/${saved.id}`);
        return;
      }
      const action = originalStatus === "RETURNED" ? "resubmit" : "submit";
      const submitted = await submitExpenseApplicationWithReconciliation(saved.id, action);
      router.push(`/expenses/${submitted.id}`);
    } catch (cause) {
      if (!(cause instanceof AuthenticationRequiredError)) {
        if (cause instanceof ExpenseSubmitResultError && cause.resultUnknown) {
          setError(cause.message);
          setSubmitResultUnknownApplicationId(applicationIdForResult ?? null);
        } else {
          setError(cause instanceof Error ? cause.message : "申請を保存できませんでした。");
        }
      }
    } finally {
      setSaving(false);
      mutationGuardRef.current.finish();
    }
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    void persist(true);
  }

  if (loading) return <Card><CardContent>申請内容を読み込んでいます…</CardContent></Card>;

  const itemDateLabel = category === "TRAINING" ? "開催日"
    : category === "CERTIFICATION" ? "受験予定日または受験日" : "利用日";
  const itemDescriptionLabel = category === "TRAINING" ? "研修名"
    : category === "CERTIFICATION" ? "資格名"
      : category === "TRANSPORTATION" ? "内容（片道／往復を含む）" : "内容";
  const merchantNameRequired = category === "MEAL" || category === "TRAINING" || category === "CERTIFICATION";

  return (
    <form className="space-y-6" onSubmit={onSubmit}>
      {error && <Card><CardContent className="text-destructive">{error}</CardContent></Card>}
      {submitResultUnknownApplicationId ? <Card><CardContent className="flex flex-wrap items-center justify-between gap-3"><p className="text-sm">申請・再申請を再実行せず、現在の状態と承認履歴を確認してください。</p><LinkButton href={`/expenses/${submitResultUnknownApplicationId}`}>申請詳細を確認</LinkButton></CardContent></Card> : null}
      <Card>
        <CardHeader><CardTitle>申請内容</CardTitle></CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-2">
          <label className="grid gap-1 text-sm">経費区分
            <select className="h-10 rounded-md border bg-background px-3" onChange={(e) => setCategory(e.target.value as ExpenseCategory)} value={category}>
              {expenseCategories.map((value) => <option key={value} value={value}>{categoryLabels[value]}</option>)}
            </select>
          </label>
          <label className="grid gap-1 text-sm"><span>利用日 <span className="text-destructive">*</span></span><Input onChange={(e) => setExpenseDate(e.target.value)} required type="date" value={expenseDate} /></label>
          <label className="grid gap-1 text-sm md:col-span-2"><span>件名 <span className="text-destructive">*</span></span><Input maxLength={200} onChange={(e) => setTitle(e.target.value)} required value={title} /></label>
          <label className="grid gap-1 text-sm md:col-span-2"><span>利用目的 <span className="text-destructive">*</span></span><textarea className="min-h-24 rounded-md border bg-background p-3" onChange={(e) => setPurpose(e.target.value)} required value={purpose} /></label>
          <label className="grid gap-1 text-sm md:col-span-2">備考<textarea className="min-h-20 rounded-md border bg-background p-3" onChange={(e) => setRemarks(e.target.value)} value={remarks} /></label>
          <p className="text-sm text-muted-foreground md:col-span-2">
            領収書・証憑を添付する場合は、先に下書きを保存してください。
            下書き保存後の詳細画面から添付できます。
          </p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex-row items-center justify-between"><CardTitle>明細</CardTitle><Button onClick={() => setItems((current) => [...current, emptyItem()])} type="button" variant="outline"><Plus />明細追加</Button></CardHeader>
        <CardContent className="space-y-4">
          {items.map((item, index) => (
            <div className="grid gap-3 rounded-lg border p-4 md:grid-cols-3" key={item.id ?? index}>
              <label className="grid gap-1 text-sm"><span>{itemDateLabel} <span className="text-destructive">*</span></span><Input onChange={(e) => updateItem(index, { expenseDate: e.target.value })} required type="date" value={item.expenseDate} /></label>
              <label className="grid gap-1 text-sm md:col-span-2"><span>{itemDescriptionLabel} <span className="text-destructive">*</span></span><Input maxLength={500} onChange={(e) => updateItem(index, { description: e.target.value })} required value={item.description} /></label>
              <label className="grid gap-1 text-sm"><span>金額（円） <span className="text-destructive">*</span></span><Input min={1} onChange={(e) => updateItem(index, { amount: Number(e.target.value) })} required step={1} type="number" value={item.amount || ""} /></label>
              <label className="grid gap-1 text-sm md:col-span-2"><span>{category === "MEAL" ? "店舗名" : category === "TRAINING" ? "主催者" : category === "CERTIFICATION" ? "試験実施団体" : "支払先"}{merchantNameRequired ? <> <span className="text-destructive">*</span></> : null}</span><Input onChange={(e) => updateItem(index, { merchantName: e.target.value })} required={merchantNameRequired} value={item.merchantName ?? ""} /></label>
              {category === "MEAL" && <label className="grid gap-1 text-sm md:col-span-3"><span>参加者（社内／社外区分・人数を含む） <span className="text-destructive">*</span></span><Input onChange={(e) => updateItem(index, { participants: e.target.value })} required value={item.participants ?? ""} /></label>}
              {category === "TRANSPORTATION" && <>
                <label className="grid gap-1 text-sm"><span>交通手段 <span className="text-destructive">*</span></span><Input onChange={(e) => updateItem(index, { transportationType: e.target.value })} required value={item.transportationType ?? ""} /></label>
                <label className="grid gap-1 text-sm"><span>出発地 <span className="text-destructive">*</span></span><Input onChange={(e) => updateItem(index, { origin: e.target.value })} required value={item.origin ?? ""} /></label>
                <label className="grid gap-1 text-sm"><span>到着地 <span className="text-destructive">*</span></span><Input onChange={(e) => updateItem(index, { destination: e.target.value })} required value={item.destination ?? ""} /></label>
              </>}
              <div className="flex justify-end md:col-span-3"><Button aria-label={`明細${index + 1}を削除`} disabled={items.length === 1} onClick={() => setItems((current) => current.filter((_, itemIndex) => itemIndex !== index))} type="button" variant="ghost"><Trash2 />削除</Button></div>
            </div>
          ))}
          <p className="text-right text-xl font-semibold">合計 {yen(total)}</p>
        </CardContent>
      </Card>
      <div className="flex flex-wrap justify-end gap-3">
        <Button disabled={saving || !valid || submitResultUnknownApplicationId !== null} onClick={() => void persist(false)} type="button" variant="outline">下書き保存</Button>
        <Button disabled={saving || !valid || submitResultUnknownApplicationId !== null} type="submit">{originalStatus === "RETURNED" ? "再申請" : "申請"}</Button>
      </div>
    </form>
  );
}
