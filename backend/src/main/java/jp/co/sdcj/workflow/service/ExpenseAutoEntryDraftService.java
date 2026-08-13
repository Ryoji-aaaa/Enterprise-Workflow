package jp.co.sdcj.workflow.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.api.ExpenseAutoEntryDraftContentRequest;
import jp.co.sdcj.workflow.api.ExpenseAutoEntryDraftCreateRequest;
import jp.co.sdcj.workflow.api.ExpenseAutoEntryDraftUpdateRequest;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProfile;
import jp.co.sdcj.workflow.domain.ExpenseApplication;
import jp.co.sdcj.workflow.domain.ExpenseApplicationAttachment;
import jp.co.sdcj.workflow.domain.ExpenseApplicationAutoEntryContext;
import jp.co.sdcj.workflow.repository.ExpenseApplicationAttachmentRepository;
import jp.co.sdcj.workflow.repository.ExpenseApplicationAutoEntryContextRepository;
import jp.co.sdcj.workflow.repository.ExpenseApplicationRepository;
import jp.co.sdcj.workflow.service.ExpenseAutoEntryDraftDetails.Warning;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisService;
import jp.co.sdcj.workflow.service.documentanalysis.OpenedDocumentAnalysisContent;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewService;
import jp.co.sdcj.workflow.storage.AttachmentStorage;
import jp.co.sdcj.workflow.storage.AttachmentStorageException;

@Service
@Profile("!manual-seed")
@ConditionalOnProperty(prefix = "workflow.document-analysis", name = "enabled", havingValue = "true")
public class ExpenseAutoEntryDraftService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExpenseAutoEntryDraftService.class);
    private static final String ATTACHMENT_TARGET_TYPE = "EXPENSE_ATTACHMENT";
    private static final String AUTO_ENTRY_TARGET_TYPE = "EXPENSE_AUTO_ENTRY_DRAFT";

    private final AutoEntryReviewService reviewService;
    private final DocumentAnalysisService documentAnalysisService;
    private final ExpenseAutoEntryHumanReviewResolver humanReviewResolver;
    private final ExpenseApplicationService applicationService;
    private final ExpenseApplicationAccessService accessService;
    private final ExpenseApplicationRepository applicationRepository;
    private final ExpenseApplicationAttachmentRepository attachmentRepository;
    private final ExpenseApplicationAutoEntryContextRepository contextRepository;
    private final AttachmentStorage attachmentStorage;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ExpenseAutoEntryDraftService(
            AutoEntryReviewService reviewService,
            DocumentAnalysisService documentAnalysisService,
            ExpenseAutoEntryHumanReviewResolver humanReviewResolver,
            ExpenseApplicationService applicationService,
            ExpenseApplicationAccessService accessService,
            ExpenseApplicationRepository applicationRepository,
            ExpenseApplicationAttachmentRepository attachmentRepository,
            ExpenseApplicationAutoEntryContextRepository contextRepository,
            AttachmentStorage attachmentStorage,
            AuditLogService auditLogService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.reviewService = reviewService;
        this.documentAnalysisService = documentAnalysisService;
        this.humanReviewResolver = humanReviewResolver;
        this.applicationService = applicationService;
        this.accessService = accessService;
        this.applicationRepository = applicationRepository;
        this.attachmentRepository = attachmentRepository;
        this.contextRepository = contextRepository;
        this.attachmentStorage = attachmentStorage;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ExpenseAutoEntryDraftDetails create(
            ExpenseAutoEntryDraftCreateRequest request,
            AppUser user) {
        ExpenseApplicationAutoEntryContext existing = existingOwnedContext(
                request.analysisId(), user);
        if (existing != null) {
            return load(existing, user, false);
        }

        AutoEntryReviewResponse review = reviewService.review(request.analysisId(), user);
        humanReviewResolver.requireSupportedCurrency(review);
        ExpenseAutoEntryHumanReviewState humanState = humanReviewResolver.resolve(
                review, request.application(), request.document(), request.confirmedFieldPaths());
        CopiedSource source = readSource(request.analysisId(), user);
        UUID applicationId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        String objectName = objectName(applicationId, attachmentId);
        storeAttachment(source, objectName, applicationId, attachmentId, user);

        try {
            ExpenseAutoEntryDraftDetails created = transactionTemplate.execute(status -> createInDatabase(
                    request.application(), review, humanState, source, applicationId,
                    attachmentId, objectName, user));
            if (created == null) {
                throw new IllegalStateException("Expense AUTO_ENTRY create transaction returned no result");
            }
            return created;
        } catch (RuntimeException exception) {
            compensateAttachment(objectName, applicationId, attachmentId);
            if (exception instanceof DataIntegrityViolationException) {
                ExpenseApplicationAutoEntryContext winner = existingOwnedContext(
                        request.analysisId(), user);
                if (winner != null) {
                    return load(winner, user, false);
                }
            }
            throw exception;
        }
    }

    public ExpenseAutoEntryDraftDetails get(UUID applicationId, AppUser user) {
        ExpenseAutoEntryDraftDetails details = transactionTemplate.execute(status -> {
            accessService.owned(applicationId, user, "EXPENSE_AUTO_ENTRY_DRAFT_READ_DENIED");
            ExpenseApplicationAutoEntryContext context = contextRepository
                    .findByExpenseApplicationId(applicationId)
                    .orElseThrow(ExpenseAutoEntryDraftService::notFound);
            return loadInTransaction(context, user, false);
        });
        if (details == null) {
            throw new IllegalStateException("Expense AUTO_ENTRY read transaction returned no result");
        }
        return details;
    }

    public ExpenseAutoEntryDraftDetails update(
            UUID applicationId,
            ExpenseAutoEntryDraftUpdateRequest request,
            AppUser user) {
        ExpenseAutoEntryDraftDetails details = transactionTemplate.execute(status -> {
            ExpenseApplicationDetails applicationDetails = applicationService.updateAutoEntryDraft(
                    applicationId, request.application().toInput(),
                    request.applicationVersion(), user);
            ExpenseApplicationAutoEntryContext context = contextRepository
                    .findByExpenseApplicationIdForUpdate(applicationId)
                    .orElseThrow(ExpenseAutoEntryDraftService::notFound);
            if (context.getVersion() != request.contextVersion()) {
                throw conflict("OPTIMISTIC_LOCK_CONFLICT", "他の更新と競合しました。再読み込みしてください。");
            }
            AutoEntryReviewResponse review = deserializeReview(context.getReviewSnapshot());
            humanReviewResolver.requireSupportedCurrency(review);
            ExpenseAutoEntryHumanReviewState humanState = humanReviewResolver.resolve(
                    review, request.application(), request.document(), request.confirmedFieldPaths());
            context.updateHumanReviewState(serialize(humanState), user.getId());
            auditLogService.recordSuccess(
                    AuditActor.user(user),
                    "EXPENSE_AUTO_ENTRY_DRAFT_UPDATED",
                    AUTO_ENTRY_TARGET_TYPE,
                    applicationId.toString(),
                    null,
                    autoEntryAuditData(context, humanState),
                    null);
            return new ExpenseAutoEntryDraftDetails(
                    applicationDetails, context, review, humanState,
                    warnings(humanState, applicationDetails.application().getTotalAmount()), false);
        });
        if (details == null) {
            throw new IllegalStateException("Expense AUTO_ENTRY update transaction returned no result");
        }
        return details;
    }

    private ExpenseAutoEntryDraftDetails createInDatabase(
            ExpenseAutoEntryDraftContentRequest request,
            AutoEntryReviewResponse review,
            ExpenseAutoEntryHumanReviewState humanState,
            CopiedSource source,
            UUID applicationId,
            UUID attachmentId,
            String objectName,
            AppUser user) {
        ExpenseApplicationDetails applicationDetails = applicationService.createDraftWithId(
                applicationId, request.toInput(), user);
        ExpenseApplicationAttachment attachment = attachmentRepository.save(
                new ExpenseApplicationAttachment(
                        attachmentId,
                        applicationId,
                        source.fileName(),
                        user.getDisplayName(),
                        objectName,
                        source.contentType(),
                        source.content().length,
                        source.sha256(),
                        user.getId()));
        ExpenseApplicationAutoEntryContext context = contextRepository.save(
                new ExpenseApplicationAutoEntryContext(
                        applicationId,
                        review.analysisId(),
                        attachmentId,
                        review.schemaVersion(),
                        serialize(review),
                        serialize(humanState),
                        user.getId()));
        auditLogService.recordSuccess(
                AuditActor.user(user),
                "EXPENSE_ATTACHMENT_UPLOADED",
                ATTACHMENT_TARGET_TYPE,
                attachmentId.toString(),
                null,
                attachmentAuditData(attachment),
                null);
        auditLogService.recordSuccess(
                AuditActor.user(user),
                "EXPENSE_AUTO_ENTRY_DRAFT_CREATED",
                AUTO_ENTRY_TARGET_TYPE,
                applicationId.toString(),
                null,
                autoEntryAuditData(context, humanState),
                null);
        return new ExpenseAutoEntryDraftDetails(
                applicationDetails, context, review, humanState,
                warnings(humanState, applicationDetails.application().getTotalAmount()), true);
    }

    private ExpenseAutoEntryDraftDetails load(
            ExpenseApplicationAutoEntryContext context,
            AppUser user,
            boolean created) {
        ExpenseAutoEntryDraftDetails details = transactionTemplate.execute(
                status -> loadInTransaction(context, user, created));
        if (details == null) {
            throw new IllegalStateException("Expense AUTO_ENTRY load transaction returned no result");
        }
        return details;
    }

    private ExpenseAutoEntryDraftDetails loadInTransaction(
            ExpenseApplicationAutoEntryContext context,
            AppUser user,
            boolean created) {
        ExpenseApplicationDetails applicationDetails = applicationService.getAccessible(
                context.getExpenseApplicationId(), user);
        if (!applicationDetails.application().getApplicantUserId().equals(user.getId())) {
            throw notFound();
        }
        AutoEntryReviewResponse review = deserializeReview(context.getReviewSnapshot());
        ExpenseAutoEntryHumanReviewState humanState = deserializeHumanState(
                context.getHumanReviewState());
        validateStoredContext(context, review, humanState);
        return new ExpenseAutoEntryDraftDetails(
                applicationDetails, context, review, humanState,
                warnings(humanState, applicationDetails.application().getTotalAmount()), created);
    }

    private ExpenseApplicationAutoEntryContext existingOwnedContext(
            UUID analysisId,
            AppUser user) {
        return transactionTemplate.execute(status -> contextRepository.findByAnalysisId(analysisId)
                .filter(context -> applicationRepository.findById(context.getExpenseApplicationId())
                        .map(application -> application.getApplicantUserId().equals(user.getId()))
                        .orElse(false))
                .orElse(null));
    }

    private CopiedSource readSource(UUID analysisId, AppUser user) {
        OpenedDocumentAnalysisContent opened = documentAnalysisService.openSource(
                analysisId, DocumentAnalysisProfile.AUTO_ENTRY, user);
        try (var stream = opened.content().stream()) {
            byte[] content = stream.readAllBytes();
            if (content.length != opened.content().length()
                    || !sha256(content).equals(opened.sha256())) {
                throw documentStorageUnavailable();
            }
            return new CopiedSource(
                    opened.fileName(), opened.content().contentType(), opened.sha256(), content);
        } catch (IOException exception) {
            throw documentStorageUnavailable();
        }
    }

    private void storeAttachment(
            CopiedSource source,
            String objectName,
            UUID applicationId,
            UUID attachmentId,
            AppUser user) {
        try {
            attachmentStorage.store(
                    objectName,
                    source.content(),
                    source.contentType(),
                    Map.of(
                            "attachment_id", attachmentId.toString(),
                            "sha256", source.sha256()));
        } catch (AttachmentStorageException exception) {
            logStorageFailure("STORE", applicationId, attachmentId, exception);
            auditLogService.recordFailure(
                    AuditActor.user(user),
                    "EXPENSE_ATTACHMENT_STORAGE_FAILED",
                    ATTACHMENT_TARGET_TYPE,
                    attachmentId.toString(),
                    "%s:STORE_FAILED".formatted(applicationId));
            throw attachmentStorageUnavailable();
        }
    }

    private void compensateAttachment(
            String objectName,
            UUID applicationId,
            UUID attachmentId) {
        try {
            attachmentStorage.delete(objectName);
        } catch (AttachmentStorageException exception) {
            logStorageFailure("DELETE", applicationId, attachmentId, exception);
            LOGGER.error(
                    "Expense AUTO_ENTRY Blob compensation failed applicationId={} attachmentId={} errorType={}",
                    applicationId, attachmentId, exception.getClass().getSimpleName());
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Expense AUTO_ENTRY Blob compensation failed applicationId={} attachmentId={} errorType={}",
                    applicationId, attachmentId, exception.getClass().getSimpleName());
        }
    }

    private void logStorageFailure(
            String operation,
            UUID applicationId,
            UUID attachmentId,
            AttachmentStorageException exception) {
        AttachmentStorageException.Diagnostics diagnostics = exception.diagnostics();
        LOGGER.error(
                "event=expense_attachment_storage_failed operation={} applicationId={} attachmentId={} "
                        + "causeType={} rootCauseType={} httpStatus={} storageErrorCode={} requestId={}",
                operation, applicationId, attachmentId,
                diagnostics.causeType(), diagnostics.rootCauseType(), diagnostics.httpStatus(),
                diagnostics.storageErrorCode(), diagnostics.requestId());
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize Expense AUTO_ENTRY context", exception);
        }
    }

    private AutoEntryReviewResponse deserializeReview(String value) {
        try {
            return objectMapper.readValue(value, AutoEntryReviewResponse.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not read Expense AUTO_ENTRY review snapshot", exception);
        }
    }

    private ExpenseAutoEntryHumanReviewState deserializeHumanState(String value) {
        try {
            return objectMapper.readValue(value, ExpenseAutoEntryHumanReviewState.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not read Expense AUTO_ENTRY human state", exception);
        }
    }

    private static void validateStoredContext(
            ExpenseApplicationAutoEntryContext context,
            AutoEntryReviewResponse review,
            ExpenseAutoEntryHumanReviewState humanState) {
        if (!context.getAnalysisId().equals(review.analysisId())
                || !context.getAutoEntrySchemaVersion().equals(review.schemaVersion())
                || humanState.schemaVersion()
                        != ExpenseAutoEntryHumanReviewState.CURRENT_SCHEMA_VERSION) {
            throw new IllegalStateException("Expense AUTO_ENTRY context is inconsistent");
        }
    }

    static List<Warning> warnings(
            ExpenseAutoEntryHumanReviewState humanState,
            BigDecimal draftTotal) {
        BigDecimal invoiceTotal = humanState.document().invoiceTotalAmount();
        return invoiceTotal != null && invoiceTotal.compareTo(draftTotal) != 0
                ? List.of(Warning.INVOICE_TOTAL_DIFFERS_FROM_DRAFT_TOTAL)
                : List.of();
    }

    private static Map<String, Object> attachmentAuditData(
            ExpenseApplicationAttachment attachment) {
        return Map.of(
                "expenseApplicationId", attachment.getExpenseApplicationId(),
                "attachmentId", attachment.getId(),
                "originalFileName", attachment.getOriginalFileName(),
                "contentType", attachment.getContentType(),
                "fileSize", attachment.getFileSize(),
                "sha256", attachment.getSha256());
    }

    private static Map<String, Object> autoEntryAuditData(
            ExpenseApplicationAutoEntryContext context,
            ExpenseAutoEntryHumanReviewState state) {
        long unresolved = state.fields().values().stream()
                .filter(field -> field.resolution()
                        == ExpenseAutoEntryHumanReviewState.HumanResolution.UNRESOLVED)
                .count();
        return Map.of(
                "applicationId", context.getExpenseApplicationId(),
                "analysisId", context.getAnalysisId(),
                "autoEntrySchemaVersion", context.getAutoEntrySchemaVersion(),
                "sourceAttachmentId", context.getSourceAttachmentId(),
                "unresolvedCount", unresolved);
    }

    private static String objectName(UUID applicationId, UUID attachmentId) {
        return "expense-evidence/%s/%s".formatted(applicationId, attachmentId);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ApiException notFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "EXPENSE_AUTO_ENTRY_DRAFT_NOT_FOUND",
                "自動入力で作成した経費下書きが見つかりません。");
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException documentStorageUnavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "DOCUMENT_ANALYSIS_STORAGE_UNAVAILABLE",
                "分析ファイルストレージへ接続できません。");
    }

    private static ApiException attachmentStorageUnavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "EXPENSE_ATTACHMENT_STORAGE_UNAVAILABLE",
                "添付ファイルのストレージへ接続できません。");
    }

    private record CopiedSource(
            String fileName,
            String contentType,
            String sha256,
            byte[] content) {
    }
}
