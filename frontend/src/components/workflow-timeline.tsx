import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { workflowStepStatusLabels, type WorkflowTimelineStep } from "@/lib/workflow";

export function WorkflowTimeline({ runNumber, steps }: { runNumber: number; steps: WorkflowTimelineStep[] }) {
  return <Card><CardHeader><CardTitle>承認経路（実行 {runNumber}）</CardTitle></CardHeader>
    <CardContent><ol className="space-y-3">{steps.map((step) => <li className="rounded-lg border p-3" key={step.stepId}>
      <div className="flex items-center justify-between gap-3"><p className="font-medium">{step.stepName}</p>
        <Badge variant="secondary">{workflowStepStatusLabels[step.status] ?? step.status}</Badge></div>
      {step.processedBy && <p className="mt-1 text-sm text-muted-foreground">処理者: {step.processedBy}{step.processedAt ? ` / ${new Date(step.processedAt).toLocaleString("ja-JP")}` : ""}</p>}
      {step.comment && <p className="mt-1 text-sm">コメント: {step.comment}</p>}
    </li>)}</ol></CardContent></Card>;
}
