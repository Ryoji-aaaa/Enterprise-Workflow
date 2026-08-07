import { ExpenseApplicationDetail } from "@/components/expense-application-detail";

export default async function ApprovalDetailPage({ params }: { params: Promise<{ applicationId: string }> }) {
  const { applicationId } = await params;
  return <main className="p-4 md:p-8"><div className="mx-auto max-w-5xl"><h1 className="mb-6 text-2xl font-semibold">経費申請の承認</h1><ExpenseApplicationDetail approvalView applicationId={applicationId} /></div></main>;
}
