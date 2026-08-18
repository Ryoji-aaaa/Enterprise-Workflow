import { WorkflowTaskDetail } from "@/components/workflow-task-detail";

export default async function ApprovalDetailPage({ params }: { params: Promise<{ stepId: string }> }) {
  const { stepId } = await params;
  return <main className="p-4 md:p-8"><div className="mx-auto max-w-5xl">
    <h1 className="mb-6 text-2xl font-semibold">ワークフロータスク詳細</h1>
    <WorkflowTaskDetail stepId={stepId} />
  </div></main>;
}
