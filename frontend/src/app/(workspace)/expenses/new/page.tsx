import { ExpenseApplicationForm } from "@/components/expense-application-form";
import { LinkButton } from "@/components/ui/button";

export default function NewExpensePage() {
  return (
    <main className="p-4 md:p-8">
      <div className="mx-auto max-w-5xl">
        <div className="mb-6 flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-semibold">経費申請を作成</h1>
            <p className="text-sm text-muted-foreground">
              経費内容と明細を入力してください。
            </p>
          </div>
          <LinkButton href="/expenses" variant="outline">
            一覧へ戻る
          </LinkButton>
        </div>
        <ExpenseApplicationForm />
      </div>
    </main>
  );
}
