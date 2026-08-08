package jp.co.sdcj.workflow.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "document_analysis_jobs",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_document_analysis_jobs_input_object",
                    columnNames = "input_object_name"),
            @UniqueConstraint(
                    name = "uk_document_analysis_jobs_raw_result_object",
                    columnNames = "raw_result_object_name"),
            @UniqueConstraint(
                    name = "uk_document_analysis_jobs_normalized_result_object",
                    columnNames = "normalized_result_object_name")
        })
public class DocumentAnalysisJob extends AuditedEntity {

    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final int ERROR_CODE_MAX_LENGTH = 100;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private DocumentAnalysisProviderType provider;

    @Column(name = "model_id", nullable = false, length = 200)
    private String modelId;

    @Column(name = "provider_api_version", nullable = false, length = 50)
    private String providerApiVersion;

    @Column(name = "normalized_schema_version", nullable = false)
    private int normalizedSchemaVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private DocumentAnalysisStatus status;

    @Column(name = "requested_by_user_id", nullable = false)
    private UUID requestedByUserId;

    @Column(name = "original_file_name", nullable = false, length = 255)
    private String originalFileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "input_object_name", nullable = false, length = 500)
    private String inputObjectName;

    @Column(name = "raw_result_object_name", length = 500)
    private String rawResultObjectName;

    @Column(name = "normalized_result_object_name", length = 500)
    private String normalizedResultObjectName;

    @Column(name = "provider_operation_id", length = 500)
    private String providerOperationId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected DocumentAnalysisJob() {
    }

    public DocumentAnalysisJob(
            UUID id,
            DocumentAnalysisProviderType provider,
            UUID requestedByUserId,
            String originalFileName,
            String contentType,
            long fileSize,
            String sha256,
            String inputObjectName,
            String modelId,
            String providerApiVersion,
            int normalizedSchemaVersion,
            Instant expiresAt,
            UUID auditUserId) {
        super(id, auditUserId);
        this.provider = Objects.requireNonNull(provider, "provider");
        this.requestedByUserId = Objects.requireNonNull(requestedByUserId, "requestedByUserId");
        this.originalFileName = required(originalFileName, "originalFileName");
        this.contentType = required(contentType, "contentType");
        if (fileSize <= 0) {
            throw new IllegalArgumentException("fileSize must be positive");
        }
        this.fileSize = fileSize;
        this.sha256 = sha256(sha256);
        this.inputObjectName = required(inputObjectName, "inputObjectName");
        this.modelId = required(modelId, "modelId");
        this.providerApiVersion = required(providerApiVersion, "providerApiVersion");
        if (normalizedSchemaVersion < 1) {
            throw new IllegalArgumentException("normalizedSchemaVersion must be at least 1");
        }
        this.normalizedSchemaVersion = normalizedSchemaVersion;
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.status = DocumentAnalysisStatus.QUEUED;
        this.attemptCount = 0;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String sha256(String value) {
        String requiredValue = required(value, "sha256");
        if (!SHA256_PATTERN.matcher(requiredValue).matches()) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hexadecimal characters");
        }
        return requiredValue;
    }

    public void claim(Instant now, java.time.Duration processingTimeout) {
        requireStatus(DocumentAnalysisStatus.QUEUED, "Only queued jobs can be claimed");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(processingTimeout, "processingTimeout");
        if (processingTimeout.isZero() || processingTimeout.isNegative()) {
            throw new IllegalArgumentException("processingTimeout must be positive");
        }
        status = DocumentAnalysisStatus.RUNNING;
        attemptCount++;
        if (startedAt == null) {
            startedAt = now;
        }
        leaseExpiresAt = now.plus(processingTimeout);
        errorCode = null;
        errorMessage = null;
        markUpdatedBy(SystemUser.ID);
    }

    public void succeed(
            String rawResultObjectName,
            String normalizedResultObjectName,
            String providerOperationId,
            Instant completedAt) {
        requireStatus(DocumentAnalysisStatus.RUNNING, "Only running jobs can succeed");
        this.rawResultObjectName = required(rawResultObjectName, "rawResultObjectName");
        this.normalizedResultObjectName = required(
                normalizedResultObjectName, "normalizedResultObjectName");
        this.providerOperationId = limited(providerOperationId, 500, "providerOperationId");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
        this.leaseExpiresAt = null;
        this.errorCode = null;
        this.errorMessage = null;
        this.status = DocumentAnalysisStatus.SUCCEEDED;
        markUpdatedBy(SystemUser.ID);
    }

    public void fail(String safeErrorCode, String safeErrorMessage, Instant completedAt) {
        requireStatus(DocumentAnalysisStatus.RUNNING, "Only running jobs can fail");
        completeAsFailed(
                DocumentAnalysisStatus.FAILED,
                safeErrorCode,
                safeErrorMessage,
                null,
                completedAt);
    }

    public void recoveryRequired(
            String safeErrorCode,
            String safeErrorMessage,
            String providerOperationId,
            Instant completedAt) {
        requireStatus(DocumentAnalysisStatus.RUNNING, "Only running jobs can require recovery");
        completeAsFailed(
                DocumentAnalysisStatus.FAILED_RECOVERY_REQUIRED,
                safeErrorCode,
                safeErrorMessage,
                providerOperationId,
                completedAt);
    }

    public void expire(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status != DocumentAnalysisStatus.QUEUED
                && status != DocumentAnalysisStatus.SUCCEEDED
                && status != DocumentAnalysisStatus.FAILED
                && status != DocumentAnalysisStatus.FAILED_RECOVERY_REQUIRED) {
            throw new IllegalStateException("Only retention-eligible jobs can expire");
        }
        status = DocumentAnalysisStatus.EXPIRED;
        leaseExpiresAt = null;
        markUpdatedBy(SystemUser.ID);
    }

    private void completeAsFailed(
            DocumentAnalysisStatus terminalStatus,
            String safeErrorCode,
            String safeErrorMessage,
            String providerOperationId,
            Instant completedAt) {
        this.status = terminalStatus;
        this.errorCode = limited(required(safeErrorCode, "safeErrorCode"),
                ERROR_CODE_MAX_LENGTH, "safeErrorCode");
        this.errorMessage = limited(required(safeErrorMessage, "safeErrorMessage"),
                ERROR_MESSAGE_MAX_LENGTH, "safeErrorMessage");
        this.providerOperationId = limited(providerOperationId, 500, "providerOperationId");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
        this.leaseExpiresAt = null;
        markUpdatedBy(SystemUser.ID);
    }

    private void requireStatus(DocumentAnalysisStatus expected, String message) {
        if (status != expected) {
            throw new IllegalStateException(message);
        }
    }

    private static String limited(String value, int maxLength, String name) {
        if (value == null) {
            return null;
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return value;
    }

    public DocumentAnalysisProviderType getProvider() { return provider; }
    public String getModelId() { return modelId; }
    public String getProviderApiVersion() { return providerApiVersion; }
    public int getNormalizedSchemaVersion() { return normalizedSchemaVersion; }
    public DocumentAnalysisStatus getStatus() { return status; }
    public UUID getRequestedByUserId() { return requestedByUserId; }
    public String getOriginalFileName() { return originalFileName; }
    public String getContentType() { return contentType; }
    public long getFileSize() { return fileSize; }
    public String getSha256() { return sha256; }
    public String getInputObjectName() { return inputObjectName; }
    public String getRawResultObjectName() { return rawResultObjectName; }
    public String getNormalizedResultObjectName() { return normalizedResultObjectName; }
    public String getProviderOperationId() { return providerOperationId; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
