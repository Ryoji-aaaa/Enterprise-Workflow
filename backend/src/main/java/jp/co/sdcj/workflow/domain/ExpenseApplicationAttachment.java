package jp.co.sdcj.workflow.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "expense_application_attachments",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_expense_application_attachments_storage_object",
                    columnNames = "storage_object_name"),
            @UniqueConstraint(
                    name = "uk_expense_attachment_id_application",
                    columnNames = {"id", "expense_application_id"})
        })
public class ExpenseApplicationAttachment extends AuditedEntity {

    @Column(name = "expense_application_id", nullable = false)
    private UUID expenseApplicationId;
    @Column(name = "original_file_name", nullable = false, length = 255)
    private String originalFileName;
    @Column(name = "uploaded_by_name_snapshot", nullable = false, length = 200)
    private String uploadedByNameSnapshot;
    @Column(name = "storage_object_name", nullable = false, length = 500)
    private String storageObjectName;
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;
    @Column(name = "file_size", nullable = false)
    private long fileSize;
    @Column(nullable = false, length = 64)
    private String sha256;
    @Column(name = "deleted_by")
    private UUID deletedBy;
    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected ExpenseApplicationAttachment() {
    }

    public ExpenseApplicationAttachment(
            UUID id,
            UUID expenseApplicationId,
            String originalFileName,
            String uploadedByNameSnapshot,
            String storageObjectName,
            String contentType,
            long fileSize,
            String sha256,
            UUID uploadedBy) {
        super(id, uploadedBy);
        this.expenseApplicationId = Objects.requireNonNull(
                expenseApplicationId, "expenseApplicationId");
        this.originalFileName = required(originalFileName, "originalFileName");
        this.uploadedByNameSnapshot = required(uploadedByNameSnapshot, "uploadedByNameSnapshot");
        this.storageObjectName = required(storageObjectName, "storageObjectName");
        this.contentType = required(contentType, "contentType");
        if (fileSize <= 0) {
            throw new IllegalArgumentException("fileSize must be positive");
        }
        this.fileSize = fileSize;
        this.sha256 = required(sha256, "sha256");
    }

    public void delete(UUID actorUserId, Instant at) {
        if (deletedAt != null) {
            throw new IllegalStateException("Attachment is already deleted");
        }
        deletedBy = Objects.requireNonNull(actorUserId, "actorUserId");
        deletedAt = Objects.requireNonNull(at, "at");
        markUpdatedBy(actorUserId);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public UUID getExpenseApplicationId() { return expenseApplicationId; }
    public String getOriginalFileName() { return originalFileName; }
    public String getUploadedByNameSnapshot() { return uploadedByNameSnapshot; }
    public String getStorageObjectName() { return storageObjectName; }
    public String getContentType() { return contentType; }
    public long getFileSize() { return fileSize; }
    public String getSha256() { return sha256; }
    public UUID getDeletedBy() { return deletedBy; }
    public Instant getDeletedAt() { return deletedAt; }
}
