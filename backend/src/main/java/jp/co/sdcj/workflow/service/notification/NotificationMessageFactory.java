package jp.co.sdcj.workflow.service.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Component;

import jp.co.sdcj.workflow.domain.AccessRequest;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.ExpenseApplication;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstance;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceCandidate;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceStep;
import jp.co.sdcj.workflow.domain.NotificationType;

@Component
public class NotificationMessageFactory {
    public List<NotificationRequest> approvalRequests(
            ExpenseApplication application,
            WorkflowInstance instance,
            WorkflowInstanceStep step,
            Collection<WorkflowInstanceCandidate> candidates) {
        return candidates.stream().map(candidate -> new NotificationRequest(
                NotificationType.EXPENSE_APPROVAL_REQUIRED,
                "EXPENSE_APPLICATION",
                application.getId(),
                application.getId(),
                instance.getId(),
                step.getId(),
                candidate.getCandidateUserId(), candidate.getCandidateNameSnapshot(),
                candidate.getCandidateEmailSnapshot(),
                "[Workflow] 経費申請の承認依頼",
                "%s（%s）の承認をお願いします。".formatted(
                        application.getTitle(), application.getApplicationNumber()),
                "EXPENSE_APPROVAL_REQUIRED:%s:%s".formatted(
                        step.getId(), candidate.getCandidateUserId())))
                .toList();
    }

    public NotificationRequest approvedApplicant(
            ExpenseApplication application, WorkflowInstance instance) {
        return applicantRequest(
                NotificationType.EXPENSE_APPROVED,
                application,
                instance,
                "承認が完了しました。",
                "EXPENSE_APPROVED:%s:%s".formatted(
                        instance.getId(), application.getApplicantUserId()));
    }

    public NotificationRequest returnedApplicant(
            ExpenseApplication application, WorkflowInstance instance, String reason) {
        return applicantRequest(
                NotificationType.EXPENSE_RETURNED,
                application,
                instance,
                "差し戻されました: " + reason,
                "EXPENSE_RETURNED:%s:%s".formatted(
                        instance.getId(), application.getApplicantUserId()));
    }

    public List<NotificationRequest> accessRequests(
            AccessRequest request,
            Collection<AppUser> administrators,
            Instant queuedAt,
            Duration cooldown) {
        long windowSeconds = Math.max(1, cooldown.toSeconds());
        long notificationWindow = queuedAt.getEpochSecond() / windowSeconds;
        String body = """
                表示名: %s
                メールアドレス: %s
                external subject: %s
                issuer: %s
                初回アクセス日時: %s
                最終アクセス日時: %s
                アクセス回数: %d
                """.formatted(
                request.getDisplayName(),
                request.getEmail(),
                request.getExternalSubject(),
                request.getIssuer(),
                request.getFirstRequestedAt(),
                request.getLastRequestedAt(),
                request.getRequestCount());
        return administrators.stream().map(administrator -> new NotificationRequest(
                NotificationType.ACCESS_REQUEST,
                "ACCESS_REQUEST",
                request.getId(),
                null,
                null,
                null,
                administrator.getId(),
                administrator.getDisplayName(),
                administrator.getEmail(),
                "[Workflow] 未登録ユーザーからアクセスがありました",
                body,
                "ACCESS_REQUEST:%s:%d:%s".formatted(
                        request.getId(), notificationWindow, administrator.getId())))
                .toList();
    }

    private static NotificationRequest applicantRequest(
            NotificationType type,
            ExpenseApplication application,
            WorkflowInstance instance,
            String message,
            String deduplicationKey) {
        return new NotificationRequest(
                type,
                "EXPENSE_APPLICATION",
                application.getId(),
                application.getId(),
                instance.getId(),
                null,
                application.getApplicantUserId(),
                application.getApplicantNameSnapshot(),
                application.getApplicantEmailSnapshot(),
                "[Workflow] 経費申請の更新",
                "%s（%s）: %s".formatted(
                        application.getTitle(), application.getApplicationNumber(), message),
                deduplicationKey);
    }
}
