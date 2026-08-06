package jp.co.sdcj.workflow.service;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.config.AttachmentProperties;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.ExpenseApplication;
import jp.co.sdcj.workflow.domain.ExpenseApplicationAttachment;
import jp.co.sdcj.workflow.domain.ExpenseApplicationStatus;
import jp.co.sdcj.workflow.repository.ExpenseApplicationAttachmentRepository;
import jp.co.sdcj.workflow.storage.AttachmentStorage;
import jp.co.sdcj.workflow.storage.AttachmentStorageException;
import jp.co.sdcj.workflow.storage.StoredAttachmentContent;

@Service
@Profile("!manual-seed")
public class ExpenseApplicationAttachmentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ExpenseApplicationAttachmentService.class);
    private static final String TARGET_TYPE = "EXPENSE_ATTACHMENT";

    private final ExpenseAttachmentSecurityInspector securityInspector;
    private final ExpenseApplicationAccessService accessService;
    private final ExpenseApplicationAttachmentRepository attachmentRepository;
    private final AttachmentStorage storage;
    private final AuditLogService auditLogService;
    private final AttachmentProperties properties;
    private final TransactionTemplate transactionTemplate;

    public ExpenseApplicationAttachmentService(
            ExpenseAttachmentSecurityInspector securityInspector,
            ExpenseApplicationAccessService accessService,
            ExpenseApplicationAttachmentRepository attachmentRepository,
            AttachmentStorage storage,
            AuditLogService auditLogService,
            AttachmentProperties properties,
            PlatformTransactionManager transactionManager) {
        this.securityInspector = securityInspector;
        this.accessService = accessService;
        this.attachmentRepository = attachmentRepository;
        this.storage = storage;
        this.auditLogService = auditLogService;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ExpenseApplicationAttachment upload(
            UUID applicationId, MultipartFile file, AppUser user) {
        transactionTemplate.executeWithoutResult(status -> {
            ExpenseApplication application = accessService.ownedForUpdate(
                    applicationId, user, "EXPENSE_ATTACHMENT_UPLOAD_DENIED");
            requireEditable(application, user, "EXPENSE_ATTACHMENT_UPLOAD_DENIED");
        });

        ValidatedExpenseAttachment validated;
        try {
            validated = securityInspector.inspect(file);
        } catch (ApiException exception) {
            auditLogService.recordDenied(
                    AuditActor.user(user), "EXPENSE_ATTACHMENT_UPLOAD_DENIED",
                    TARGET_TYPE, applicationId.toString(), exception.getCode());
            throw exception;
        }

        UUID attachmentId = UUID.randomUUID();
        String objectName = "expense-evidence/%s/%s".formatted(applicationId, attachmentId);
        try {
            storage.store(objectName, validated.content(), validated.contentType(), Map.of(
                    "attachment_id", attachmentId.toString(),
                    "sha256", validated.sha256()));
        } catch (AttachmentStorageException exception) {
            recordStorageFailure(user, applicationId, attachmentId, "STORE_FAILED");
            throw storageUnavailable();
        }

        try {
            ExpenseApplicationAttachment attachment = transactionTemplate.execute(status -> {
                ExpenseApplication application = accessService.ownedForUpdate(
                        applicationId, user, "EXPENSE_ATTACHMENT_UPLOAD_DENIED");
                requireEditable(application, user, "EXPENSE_ATTACHMENT_UPLOAD_DENIED");
                requireCapacity(applicationId, validated.fileSize(), user);
                ExpenseApplicationAttachment saved = attachmentRepository.save(
                        new ExpenseApplicationAttachment(
                                attachmentId,
                                applicationId,
                                validated.originalFileName(),
                                user.getDisplayName(),
                                objectName,
                                validated.contentType(),
                                validated.fileSize(),
                                validated.sha256(),
                                user.getId()));
                auditLogService.recordSuccess(
                        AuditActor.user(user), "EXPENSE_ATTACHMENT_UPLOADED", TARGET_TYPE,
                        attachmentId.toString(), null, auditData(saved), null);
                return saved;
            });
            if (attachment == null) {
                throw new IllegalStateException("Attachment transaction returned no result");
            }
            return attachment;
        } catch (RuntimeException exception) {
            compensateUpload(objectName, applicationId, attachmentId);
            throw exception;
        }
    }

    public ExpenseAttachmentList list(UUID applicationId, AppUser user) {
        return transactionTemplate.execute(status -> {
            ExpenseApplication application = accessService.accessible(
                    applicationId, user, "EXPENSE_ATTACHMENT_READ_DENIED");
            boolean deletable = application.getApplicantUserId().equals(user.getId())
                    && (application.getStatus() == ExpenseApplicationStatus.DRAFT
                            || application.getStatus() == ExpenseApplicationStatus.RETURNED);
            return new ExpenseAttachmentList(attachmentRepository
                    .findAllByExpenseApplicationIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(
                            applicationId), deletable);
        });
    }

    public OpenedExpenseAttachment open(UUID applicationId, UUID attachmentId, AppUser user) {
        try {
            OpenedExpenseAttachment opened = transactionTemplate.execute(status -> {
                accessService.accessible(applicationId, user, "EXPENSE_ATTACHMENT_READ_DENIED");
                ExpenseApplicationAttachment attachment = attachmentRepository
                        .findByIdAndExpenseApplicationIdAndDeletedAtIsNull(
                                attachmentId, applicationId)
                        .orElseThrow(() -> attachmentNotFound(
                                user, attachmentId, "EXPENSE_ATTACHMENT_READ_DENIED"));
                StoredAttachmentContent content = storage.load(attachment.getStorageObjectName());
                if (content.length() != attachment.getFileSize()) {
                    closeQuietly(content);
                    throw new AttachmentStorageException(
                            new IllegalStateException("Stored attachment length mismatch"));
                }
                auditLogService.recordSuccess(
                        AuditActor.user(user), "EXPENSE_ATTACHMENT_CONTENT_ACCESSED", TARGET_TYPE,
                        attachmentId.toString(), null, auditData(attachment), null);
                return new OpenedExpenseAttachment(attachment, content);
            });
            if (opened == null) {
                throw new IllegalStateException("Attachment content transaction returned no result");
            }
            return opened;
        } catch (AttachmentStorageException exception) {
            recordStorageFailure(user, applicationId, attachmentId, "LOAD_FAILED");
            throw storageUnavailable();
        }
    }

    public void delete(UUID applicationId, UUID attachmentId, AppUser user) {
        DeletedAttachment deleted = transactionTemplate.execute(status -> {
            ExpenseApplication application = accessService.ownedForUpdate(
                    applicationId, user, "EXPENSE_ATTACHMENT_DELETE_DENIED");
            requireEditable(application, user, "EXPENSE_ATTACHMENT_DELETE_DENIED");
            ExpenseApplicationAttachment attachment = attachmentRepository
                    .findActiveForUpdate(applicationId, attachmentId)
                    .orElseThrow(() -> attachmentNotFound(
                            user, attachmentId, "EXPENSE_ATTACHMENT_DELETE_DENIED"));
            attachment.delete(user.getId(), Instant.now());
            auditLogService.recordSuccess(
                    AuditActor.user(user), "EXPENSE_ATTACHMENT_DELETED", TARGET_TYPE,
                    attachmentId.toString(), auditData(attachment),
                    Map.of("expenseApplicationId", applicationId, "deleted", true), null);
            attachmentRepository.flush();
            return new DeletedAttachment(attachment.getStorageObjectName());
        });
        if (deleted == null) {
            throw new IllegalStateException("Attachment deletion transaction returned no result");
        }

        try {
            storage.delete(deleted.storageObjectName());
        } catch (AttachmentStorageException exception) {
            LOGGER.error(
                    "Attachment Blob deletion failed after metadata commit "
                            + "applicationId={} attachmentId={} storageObjectName={} "
                            + "errorType={} retryRequired=true",
                    applicationId, attachmentId, deleted.storageObjectName(),
                    exception.getClass().getSimpleName());
            recordDeleteStorageFailure(user, applicationId, attachmentId);
        }
    }

    private void requireCapacity(UUID applicationId, long newSize, AppUser user) {
        long count = attachmentRepository.countByExpenseApplicationIdAndDeletedAtIsNull(applicationId);
        if (count >= properties.maxFilesPerApplication()) {
            deny(user, applicationId, "EXPENSE_ATTACHMENT_COUNT_EXCEEDED", "添付できる件数の上限を超えています。");
        }
        long totalSize = attachmentRepository.activeFileSize(applicationId);
        if (totalSize + newSize > properties.maxTotalSizePerApplication().toBytes()) {
            deny(user, applicationId, "EXPENSE_ATTACHMENT_TOTAL_SIZE_EXCEEDED", "添付ファイルの合計サイズが上限を超えています。");
        }
    }

    private void requireEditable(ExpenseApplication application, AppUser user, String deniedAction) {
        if (application.getStatus() != ExpenseApplicationStatus.DRAFT
                && application.getStatus() != ExpenseApplicationStatus.RETURNED) {
            auditLogService.recordDenied(
                    AuditActor.user(user), deniedAction, TARGET_TYPE,
                    application.getId().toString(), "EXPENSE_ATTACHMENT_NOT_EDITABLE");
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "EXPENSE_ATTACHMENT_NOT_EDITABLE",
                    "現在の申請状態では添付ファイルを変更できません。");
        }
    }

    private void deny(AppUser user, UUID applicationId, String code, String message) {
        auditLogService.recordDenied(
                AuditActor.user(user), "EXPENSE_ATTACHMENT_UPLOAD_DENIED", TARGET_TYPE,
                applicationId.toString(), code);
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    private ApiException attachmentNotFound(
            AppUser user, UUID attachmentId, String deniedAction) {
        auditLogService.recordDenied(
                AuditActor.user(user), deniedAction, TARGET_TYPE,
                attachmentId.toString(), "EXPENSE_ATTACHMENT_NOT_FOUND");
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "EXPENSE_ATTACHMENT_NOT_FOUND",
                "添付ファイルが見つかりません。");
    }

    private void compensateUpload(String objectName, UUID applicationId, UUID attachmentId) {
        try {
            storage.delete(objectName);
        } catch (RuntimeException compensationFailure) {
            LOGGER.error(
                    "Attachment upload compensation failed applicationId={} attachmentId={} errorType={}",
                    applicationId, attachmentId, compensationFailure.getClass().getSimpleName());
        }
    }

    private void recordStorageFailure(
            AppUser user, UUID applicationId, UUID attachmentId, String reason) {
        auditLogService.recordFailure(
                AuditActor.user(user), "EXPENSE_ATTACHMENT_STORAGE_FAILED", TARGET_TYPE,
                attachmentId.toString(), "%s:%s".formatted(applicationId, reason));
    }

    private void recordDeleteStorageFailure(
            AppUser user, UUID applicationId, UUID attachmentId) {
        try {
            recordStorageFailure(user, applicationId, attachmentId, "DELETE_FAILED_RETRY_REQUIRED");
        } catch (RuntimeException auditFailure) {
            LOGGER.error(
                    "Attachment Blob deletion failure audit could not be recorded "
                            + "applicationId={} attachmentId={} errorType={}",
                    applicationId, attachmentId, auditFailure.getClass().getSimpleName());
        }
    }

    private static Map<String, Object> auditData(ExpenseApplicationAttachment attachment) {
        return Map.of(
                "expenseApplicationId", attachment.getExpenseApplicationId(),
                "attachmentId", attachment.getId(),
                "originalFileName", attachment.getOriginalFileName(),
                "contentType", attachment.getContentType(),
                "fileSize", attachment.getFileSize(),
                "sha256", attachment.getSha256());
    }

    private static void closeQuietly(StoredAttachmentContent content) {
        try {
            content.stream().close();
        } catch (IOException ignored) {
            // The length mismatch is the primary storage failure.
        }
    }

    private static ApiException storageUnavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "EXPENSE_ATTACHMENT_STORAGE_UNAVAILABLE",
                "添付ファイルのストレージへ接続できません。");
    }

    private record DeletedAttachment(String storageObjectName) {
    }
}
