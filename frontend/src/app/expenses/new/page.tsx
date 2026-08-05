import Link from "next/link";

import { ExpenseApplicationForm } from "@/components/expense-application-form";
import { Button } from "@/components/ui/button";

export default function NewExpensePage() {
  return <main className="min-h-svh bg-muted/30 p-4 md:p-8"><div className="mx-auto max-w-5xl"><div className="mb-6 flex items-center justify-between"><div><h1 className="text-2xl font-semibold">経費申請を作成</h1><p className="text-sm text-muted-foreground">経費内容と明細を入力してください。</p></div><Button render={<Link href="/expenses" />} variant="outline">一覧へ戻る</Button></div><ExpenseApplicationForm /></div></main>;
}
