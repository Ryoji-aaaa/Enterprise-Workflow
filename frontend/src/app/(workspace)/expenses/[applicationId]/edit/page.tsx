import { ExpenseApplicationEdit } from "@/components/expense-application-edit";
import { LinkButton } from "@/components/ui/button";

export default async function EditExpensePage({
  params,
}: {
  params: Promise<{ applicationId: string }>;
}) {
  const { applicationId } = await params;
  return (
    <main className="p-4 md:p-8">
      <div className="mx-auto max-w-5xl">
        <div className="mb-6 flex items-center justify-between">
          <h1 className="text-2xl font-semibold">経費申請を編集</h1>
          <LinkButton href={`/expenses/${applicationId}`} variant="outline">
            詳細へ戻る
          </LinkButton>
        </div>
        <ExpenseApplicationEdit applicationId={applicationId} />
      </div>
    </main>
  );
}
