import { ClipboardCheck } from "lucide-react";
import { WorkflowTaskList } from "@/components/workflow-task-list";
import { LinkButton } from "@/components/ui/button";

export default function ApprovalsPage() {
  return <main className="p-4 md:p-8"><div className="mx-auto max-w-7xl">
    <div className="mb-6 flex items-center justify-between gap-3"><div>
      <h1 className="flex items-center gap-2 text-2xl font-semibold"><ClipboardCheck className="text-primary" />ワークフロータスク</h1>
      <p className="text-sm text-muted-foreground">現在、自分が候補者となっている未処理タスクを表示します。</p>
    </div><LinkButton href="/top" variant="outline">トップへ</LinkButton></div>
    <WorkflowTaskList />
  </div></main>;
}
