package jp.co.sdcj.workflow.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "notification_outbox", uniqueConstraints = @UniqueConstraint(
        name = "uq_notification_outbox_deduplication", columnNames = "deduplication_key"))
public class NotificationOutbox {
    @Id private UUID id;
    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 100)
    private NotificationType notificationType;
    @Column(name = "source_type", nullable = false, length = 100) private String sourceType;
    @Column(name = "source_id", nullable = false) private UUID sourceId;
    @Column(name = "expense_application_id") private UUID expenseApplicationId;
    @Column(name = "workflow_instance_id") private UUID workflowInstanceId;
    @Column(name = "workflow_step_id") private UUID workflowStepId;
    @Column(name = "recipient_user_id") private UUID recipientUserId;
    @Column(name = "recipient_name_snapshot", length = 255) private String recipientNameSnapshot;
    @Column(name = "recipient_email_snapshot", nullable = false, length = 320)
    private String recipientEmailSnapshot;
    @Column(nullable = false, length = 255) private String subject;
    @Column(name = "body_text", nullable = false, columnDefinition = "text") private String bodyText;
    @Column(name = "deduplication_key", nullable = false, length = 500) private String deduplicationKey;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30) private NotificationStatus status;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;
    @Column(name = "processing_started_at") private Instant processingStartedAt;
    @Column(name = "sent_at") private Instant sentAt;
    @Column(name = "last_error_code", length = 100) private String lastErrorCode;
    @Column(name = "last_error_message", length = 1000) private String lastErrorMessage;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected NotificationOutbox() {
    }

    public NotificationOutbox(
            NotificationType notificationType,
            String sourceType,
            UUID sourceId,
            UUID expenseApplicationId,
            UUID workflowInstanceId,
            UUID workflowStepId,
            UUID recipientUserId,
            String recipientNameSnapshot,
            String recipientEmailSnapshot,
            String subject,
            String bodyText,
            String deduplicationKey,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.notificationType = Objects.requireNonNull(notificationType);
        this.sourceType = required(sourceType, "sourceType");
        this.sourceId = Objects.requireNonNull(sourceId);
        this.expenseApplicationId = expenseApplicationId;
        this.workflowInstanceId = workflowInstanceId;
        this.workflowStepId = workflowStepId;
        this.recipientUserId = recipientUserId;
        this.recipientNameSnapshot = recipientNameSnapshot;
        this.recipientEmailSnapshot = required(recipientEmailSnapshot, "recipientEmailSnapshot");
        this.subject = required(subject, "subject");
        this.bodyText = required(bodyText, "bodyText");
        this.deduplicationKey = required(deduplicationKey, "deduplicationKey");
        this.status = NotificationStatus.PENDING;
        this.nextAttemptAt = Objects.requireNonNull(createdAt);
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    @PrePersist
    void insert() {
        Instant now = Instant.now();
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = createdAt;
        if (nextAttemptAt == null) nextAttemptAt = createdAt;
    }

    public void claim(Instant at) {
        if (status != NotificationStatus.PENDING && status != NotificationStatus.RETRY_WAIT) {
            throw new IllegalStateException("Only dispatchable notifications can be claimed");
        }
        status = NotificationStatus.PROCESSING;
        attemptCount++;
        processingStartedAt = at;
        updatedAt = at;
    }

    public void markSent(Instant at) {
        requireProcessing();
        status = NotificationStatus.SENT;
        sentAt = at;
        processingStartedAt = null;
        lastErrorCode = null;
        lastErrorMessage = null;
        updatedAt = at;
    }

    public void markDeliveryFailure(
            Instant at, Instant retryAt, boolean exhausted, String errorCode, String errorMessage) {
        requireProcessing();
        status = exhausted ? NotificationStatus.FAILED : NotificationStatus.RETRY_WAIT;
        nextAttemptAt = exhausted ? at : Objects.requireNonNull(retryAt);
        processingStartedAt = null;
        lastErrorCode = limited(errorCode, 100);
        lastErrorMessage = limited(errorMessage, 1000);
        updatedAt = at;
    }

    public void recover(Instant at) {
        if (status != NotificationStatus.PROCESSING) return;
        status = NotificationStatus.RETRY_WAIT;
        nextAttemptAt = at;
        processingStartedAt = null;
        lastErrorCode = "PROCESSING_TIMEOUT";
        lastErrorMessage = "Dispatcher processing timed out before completion.";
        updatedAt = at;
    }

    private void requireProcessing() {
        if (status != NotificationStatus.PROCESSING) {
            throw new IllegalStateException("Notification is not being processed");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String limited(String value, int maximumLength) {
        if (value == null) return null;
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    public UUID getId() { return id; }
    public NotificationType getNotificationType() { return notificationType; }
    public String getSourceType() { return sourceType; }
    public UUID getSourceId() { return sourceId; }
    public UUID getExpenseApplicationId() { return expenseApplicationId; }
    public UUID getWorkflowInstanceId() { return workflowInstanceId; }
    public UUID getWorkflowStepId() { return workflowStepId; }
    public UUID getRecipientUserId() { return recipientUserId; }
    public String getRecipientNameSnapshot() { return recipientNameSnapshot; }
    public String getRecipientEmailSnapshot() { return recipientEmailSnapshot; }
    public String getSubject() { return subject; }
    public String getBodyText() { return bodyText; }
    public String getDeduplicationKey() { return deduplicationKey; }
    public NotificationStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getProcessingStartedAt() { return processingStartedAt; }
    public Instant getSentAt() { return sentAt; }
    public String getLastErrorCode() { return lastErrorCode; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
