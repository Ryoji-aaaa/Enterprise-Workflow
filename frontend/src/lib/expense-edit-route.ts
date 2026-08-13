import {
  ExpenseAutoEntryApiError,
  getExpenseAutoEntryDraft,
  type ExpenseAutoEntryDraftResponse,
} from "./expense-auto-entry-api.ts";

type DraftLoader = (
  applicationId: string,
  signal?: AbortSignal,
) => Promise<ExpenseAutoEntryDraftResponse>;

export async function probeExpenseEditRoute(
  applicationId: string,
  signal?: AbortSignal,
  loadDraft: DraftLoader = getExpenseAutoEntryDraft,
): Promise<"AUTO_ENTRY" | "GENERIC"> {
  try {
    await loadDraft(applicationId, signal);
    return "AUTO_ENTRY";
  } catch (cause) {
    if (cause instanceof ExpenseAutoEntryApiError
        && cause.status === 404
        && cause.code === "EXPENSE_AUTO_ENTRY_DRAFT_NOT_FOUND") {
      return "GENERIC";
    }
    throw cause;
  }
}
