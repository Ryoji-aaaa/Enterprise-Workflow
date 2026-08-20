import { fetchBackend } from "./backend-browser-client.ts";
import {
  expenseErrorMessage,
  type ExpenseApplication,
} from "./expense-application.ts";
import type { WorkflowInstance } from "./workflow.ts";

type BackendFetch = (
  input: string | URL | Request,
  init?: RequestInit,
) => Promise<Response>;

type ErrorBody = {
  code?: string;
  message?: string;
};

export async function loadLatestExpenseWorkflow(
  applicationId: string,
  fetchImplementation: BackendFetch = fetchBackend,
  signal?: AbortSignal,
): Promise<WorkflowInstance | null> {
  const response = await fetchImplementation(
    `/api/backend/workflow/subjects/EXPENSE_APPLICATION/${encodeURIComponent(applicationId)}/latest`,
    { cache: "no-store", signal },
  );
  if (response.status === 404) return null;
  if (!response.ok) throw new Error("承認経路を取得できませんでした。");
  return await response.json() as WorkflowInstance;
}

export async function performExpenseDetailAction({
  actionPath,
  applicationId,
  showWorkflow,
  body,
  onApplication,
  onWorkflow,
  fetchImplementation = fetchBackend,
}: {
  actionPath: string;
  applicationId: string;
  showWorkflow: boolean;
  body?: object;
  onApplication: (application: ExpenseApplication) => void;
  onWorkflow: (workflow: WorkflowInstance | null) => void;
  fetchImplementation?: BackendFetch;
}): Promise<void> {
  const response = await fetchImplementation(actionPath, {
    method: "POST",
    headers: body ? { "Content-Type": "application/json" } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const result = (await response.json()) as ExpenseApplication & ErrorBody;
  if (!response.ok) {
    throw new Error(expenseErrorMessage(
      result.code,
      result.message ?? "処理できませんでした。",
    ));
  }
  onApplication(result);
  if (showWorkflow) {
    onWorkflow(await loadLatestExpenseWorkflow(applicationId, fetchImplementation));
  }
}
