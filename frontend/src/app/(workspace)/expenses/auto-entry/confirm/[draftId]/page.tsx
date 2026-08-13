import { ExpenseAutoEntryConfirmation } from "@/components/expense-auto-entry/expense-auto-entry-confirmation";

export default async function ExpenseAutoEntryConfirmationPage({ params }: { params: Promise<{ draftId: string }> }) {
  const { draftId } = await params;
  return <ExpenseAutoEntryConfirmation draftId={draftId} />;
}
