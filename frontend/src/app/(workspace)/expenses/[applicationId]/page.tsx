import { ExpenseApplicationDetail } from "@/components/expense-application-detail";

export default async function ExpenseDetailPage({
  params,
}: {
  params: Promise<{ applicationId: string }>;
}) {
  const { applicationId } = await params;
  return (
    <main className="p-4 md:p-8">
      <div className="mx-auto max-w-5xl">
        <h1 className="mb-6 text-2xl font-semibold">経費申請詳細</h1>
        <ExpenseApplicationDetail applicationId={applicationId} />
      </div>
    </main>
  );
}
