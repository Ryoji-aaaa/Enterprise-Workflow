export type WorkflowStepStatus = "WAITING" | "PENDING" | "APPROVED" | "RETURNED" | "CANCELLED";

export type WorkflowTimelineStep = {
  stepId: string;
  stepOrder: number;
  nodeKey: string;
  stepName: string;
  status: WorkflowStepStatus;
  processedBy: string | null;
  processedAt: string | null;
  comment: string | null;
};

export type WorkflowTask = {
  stepId: string;
  instanceId: string;
  runNumber: number;
  workflowCode: string;
  workflowName: string;
  subjectType: string;
  subjectId: string;
  subjectReference: string;
  subjectTitle: string;
  requesterName: string;
  stepName: string;
  submittedAt: string;
};

export type WorkflowTaskPage = {
  content: WorkflowTask[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type WorkflowTaskDetail = { task: WorkflowTask; timeline: WorkflowTimelineStep[] };

export type WorkflowInstance = {
  instanceId: string;
  definitionVersionId: string;
  subjectType: string;
  subjectId: string;
  runNumber: number;
  status: string;
  startedAt: string;
  completedAt: string | null;
  steps: WorkflowTimelineStep[];
};

export const workflowStepStatusLabels: Record<WorkflowStepStatus, string> = {
  WAITING: "待機中", PENDING: "承認待ち", APPROVED: "承認済み",
  RETURNED: "差戻し", CANCELLED: "取消",
};
