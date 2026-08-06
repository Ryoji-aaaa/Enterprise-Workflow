export const notificationStatuses = [
  "SENT",
  "PENDING",
  "PROCESSING",
  "RETRY_WAIT",
  "FAILED",
] as const;

export type NotificationStatus = typeof notificationStatuses[number];

export const notificationTypes = [
  "ACCESS_REQUEST",
  "EXPENSE_APPROVAL_REQUIRED",
  "EXPENSE_APPROVED",
  "EXPENSE_RETURNED",
] as const;

export type NotificationType = typeof notificationTypes[number];

export const notificationStatusLabels: Record<NotificationStatus, string> = {
  SENT: "送付済み",
  PENDING: "送付待ち",
  PROCESSING: "送付処理中",
  RETRY_WAIT: "再試行待ち",
  FAILED: "送付失敗",
};

export const notificationTypeLabels: Record<NotificationType, string> = {
  ACCESS_REQUEST: "利用申請",
  EXPENSE_APPROVAL_REQUIRED: "承認依頼",
  EXPENSE_APPROVED: "承認完了",
  EXPENSE_RETURNED: "差戻し",
};

export type MailNotification = {
  notificationId: string;
  notificationType: NotificationType;
  status: NotificationStatus;
  recipientUserId: string | null;
  recipientName: string | null;
  recipientEmail: string;
  subject: string;
  applicationId: string | null;
  applicationNumber: string | null;
  applicationTitle: string | null;
  attemptCount: number;
  createdAt: string;
  sentAt: string | null;
  nextAttemptAt: string;
};

export type MailNotificationDetail = MailNotification & {
  bodyText: string;
  approvalRunId: string | null;
  approvalStepId: string | null;
  lastErrorCode: string | null;
  lastErrorMessage: string | null;
};

export type MailNotificationPage = {
  content: MailNotification[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export function displayDate(value: string | null): string {
  return value ? new Date(value).toLocaleString("ja-JP") : "—";
}
