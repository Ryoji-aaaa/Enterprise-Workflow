package jp.co.sdcj.workflow.service.documentanalysis;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.config.DocumentAnalysisProperties;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.DocumentAnalysisJob;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.domain.DocumentAnalysisStatus;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.DocumentAnalysisJobRepository;
import jp.co.sdcj.workflow.service.AuditActor;
import jp.co.sdcj.workflow.service.AuditLogService;
import jp.co.sdcj.workflow.service.PermissionCodes;
import jp.co.sdcj.workflow.service.PermissionService;
import jp.co.sdcj.workflow.storage.DocumentAnalysisObjectNames;
import jp.co.sdcj.workflow.storage.DocumentAnalysisStorage;
import jp.co.sdcj.workflow.storage.DocumentAnalysisStorageException;
import jp.co.sdcj.workflow.storage.StoredDocumentAnalysisContent;

@Service
@ConditionalOnProperty(prefix = "workflow.document-analysis", name = "enabled", havingValue = "true")
public class DocumentAnalysisService {

    private static final int NORMALIZED_SCHEMA_VERSION = 1;
    private static final String TARGET_TYPE = "DOCUMENT_ANALYSIS";
    private static final List<DocumentAnalysisStatus> ACTIVE_STATUSES = List.of(
            DocumentAnalysisStatus.QUEUED,
            DocumentAnalysisStatus.RUNNING);
    private static final String JSON_CONTENT_TYPE = "application/json";

    private final DocumentAnalysisFileInspector fileInspector;
    private final DocumentAnalysisJobRepository jobRepository;
    private final AppUserRepository appUserRepository;
    private final DocumentAnalysisStorage storage;
    private final DocumentAnalysisProperties properties;
    private final PermissionService permissionService;
    private final AuditLogService auditLogService;
    private final TransactionTemplate transactionTemplate;

    public DocumentAnalysisService(
            DocumentAnalysisFileInspector fileInspector,
            DocumentAnalysisJobRepository jobRepository,
            AppUserRepository appUserRepository,
            DocumentAnalysisStorage storage,
            DocumentAnalysisProperties properties,
            PermissionService permissionService,
            AuditLogService auditLogService,
            PlatformTransactionManager transactionManager) {
        this.fileInspector = fileInspector;
        this.jobRepository = jobRepository;
        this.appUserRepository = appUserRepository;
        this.storage = storage;
        this.properties = properties;
        this.permissionService = permissionService;
        this.auditLogService = auditLogService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public DocumentAnalysisJob create(
            DocumentAnalysisProviderType provider,
            MultipartFile file,
            AppUser user) {
        requireProvider(provider, user);
        ValidatedDocumentAnalysisFile validated = fileInspector.inspect(file);
        UUID analysisId = UUID.randomUUID();
        String inputObjectName = DocumentAnalysisObjectNames.input(analysisId);
        try {
            storage.storeInput(inputObjectName, validated.content(), validated.contentType());
        } catch (DocumentAnalysisStorageException exception) {
            throw storageUnavailable();
        }

        try {
            DocumentAnalysisJob saved = transactionTemplate.execute(status -> {
                appUserRepository.findByIdForUpdate(user.getId())
                        .orElseThrow(() -> notFound());
                enforceAbuseLimits(user);
                DocumentAnalysisProperties.Provider providerConfig = providerConfig(provider);
                DocumentAnalysisJob job = new DocumentAnalysisJob(
                        analysisId,
                        provider,
                        user.getId(),
                        validated.originalFileName(),
                        validated.contentType(),
                        validated.fileSize(),
                        validated.sha256(),
                        inputObjectName,
                        providerConfig.modelId(),
                        providerConfig.apiVersion(),
                        NORMALIZED_SCHEMA_VERSION,
                        Instant.now().plus(properties.retention()),
                        user.getId());
                DocumentAnalysisJob persisted = jobRepository.save(job);
                auditLogService.recordSuccess(
                        AuditActor.user(user),
                        "DOCUMENT_ANALYSIS_REQUESTED",
                        TARGET_TYPE,
                        analysisId.toString(),
                        null,
                        auditData(persisted),
                        null);
                return persisted;
            });
            if (saved == null) {
                throw new IllegalStateException("Document analysis transaction returned no result");
            }
            return saved;
        } catch (RuntimeException exception) {
            compensateInput(inputObjectName);
            throw exception;
        }
    }

    public Page<DocumentAnalysisJob> listMine(
            DocumentAnalysisProviderType provider,
            AppUser user,
            Pageable pageable) {
        return transactionTemplate.execute(status -> provider == null
                ? jobRepository.findAllByRequestedByUserIdOrderByCreatedAtDescIdDesc(
                        user.getId(), pageable)
                : jobRepository.findAllByRequestedByUserIdAndProviderOrderByCreatedAtDescIdDesc(
                        user.getId(), provider, pageable));
    }

    public DocumentAnalysisJob getMine(UUID analysisId, AppUser user) {
        return transactionTemplate.execute(status -> jobRepository
                .findByIdAndRequestedByUserId(analysisId, user.getId())
                .orElseThrow(() -> notFound()));
    }

    public OpenedDocumentAnalysisContent openSource(UUID analysisId, AppUser user) {
        try {
            OpenedDocumentAnalysisContent opened = transactionTemplate.execute(status -> {
                DocumentAnalysisJob job = ownUnexpired(analysisId, user);
                StoredDocumentAnalysisContent content = storage.loadInput(job.getInputObjectName());
                if (content.length() != job.getFileSize()) {
                    closeQuietly(content);
                    throw new DocumentAnalysisStorageException(
                            new IllegalStateException("Stored source length mismatch"));
                }
                auditLogService.recordSuccess(
                        AuditActor.user(user),
                        "DOCUMENT_ANALYSIS_SOURCE_ACCESSED",
                        TARGET_TYPE,
                        analysisId.toString(),
                        null,
                        auditData(job),
                        null);
                return new OpenedDocumentAnalysisContent(job.getOriginalFileName(), content);
            });
            if (opened == null) {
                throw new IllegalStateException("Document analysis source transaction returned no result");
            }
            return opened;
        } catch (DocumentAnalysisStorageException exception) {
            throw storageUnavailable();
        }
    }

    public OpenedDocumentAnalysisContent openView(UUID analysisId, AppUser user) {
        return openResult(analysisId, user, "view", DocumentAnalysisJob::getNormalizedResultObjectName);
    }

    public OpenedDocumentAnalysisContent openRawResult(UUID analysisId, AppUser user) {
        return openResult(analysisId, user, "raw", DocumentAnalysisJob::getRawResultObjectName);
    }

    private OpenedDocumentAnalysisContent openResult(
            UUID analysisId,
            AppUser user,
            String resultKind,
            java.util.function.Function<DocumentAnalysisJob, String> objectName) {
        try {
            OpenedDocumentAnalysisContent opened = transactionTemplate.execute(status -> {
                DocumentAnalysisJob job = ownUnexpired(analysisId, user);
                if (job.getStatus() != DocumentAnalysisStatus.SUCCEEDED) {
                    throw new ApiException(
                            HttpStatus.CONFLICT,
                            "DOCUMENT_ANALYSIS_RESULT_NOT_READY",
                            "分析結果はまだ利用できません。");
                }
                StoredDocumentAnalysisContent content = storage.loadResult(objectName.apply(job));
                if (!JSON_CONTENT_TYPE.equalsIgnoreCase(content.contentType())) {
                    closeQuietly(content);
                    throw new DocumentAnalysisStorageException(
                            new IllegalStateException("Stored result content type mismatch"));
                }
                Map<String, Object> afterData = auditData(job);
                afterData.put("resultKind", resultKind);
                auditLogService.recordSuccess(
                        AuditActor.user(user),
                        "DOCUMENT_ANALYSIS_RESULT_ACCESSED",
                        TARGET_TYPE,
                        analysisId.toString(),
                        null,
                        afterData,
                        null);
                return new OpenedDocumentAnalysisContent(null, content);
            });
            if (opened == null) {
                throw new IllegalStateException("Document analysis result transaction returned no result");
            }
            return opened;
        } catch (DocumentAnalysisStorageException exception) {
            throw storageUnavailable();
        }
    }

    private DocumentAnalysisJob ownUnexpired(UUID analysisId, AppUser user) {
        DocumentAnalysisJob job = jobRepository
                .findByIdAndRequestedByUserId(analysisId, user.getId())
                .orElseThrow(() -> notFound());
        if (!job.getExpiresAt().isAfter(Instant.now())) {
            throw new ApiException(
                    HttpStatus.GONE,
                    "DOCUMENT_ANALYSIS_EXPIRED",
                    "分析ファイルと結果の保持期限が切れています。");
        }
        return job;
    }

    private void requireProvider(DocumentAnalysisProviderType provider, AppUser user) {
        if (provider == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "DOCUMENT_ANALYSIS_PROVIDER_REQUIRED",
                    "分析Providerを指定してください。");
        }
        if (!providerConfig(provider).enabled()) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "DOCUMENT_ANALYSIS_PROVIDER_DISABLED",
                    "指定された分析Providerは利用できません。");
        }
        String permissionCode = switch (provider) {
            case DOCUMENT_INTELLIGENCE -> PermissionCodes.DOCUMENT_INTELLIGENCE_ANALYZE;
            case CONTENT_UNDERSTANDING -> PermissionCodes.CONTENT_UNDERSTANDING_ANALYZE;
        };
        if (!permissionService.hasPermission(user.getId(), permissionCode)) {
            auditLogService.recordDenied(
                    AuditActor.user(user),
                    "DOCUMENT_ANALYSIS_REQUEST_DENIED",
                    TARGET_TYPE,
                    "PROVIDER",
                    "DOCUMENT_ANALYSIS_PROVIDER_FORBIDDEN");
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "DOCUMENT_ANALYSIS_PROVIDER_FORBIDDEN",
                    "指定された分析Providerを利用する権限がありません。");
        }
    }

    private DocumentAnalysisProperties.Provider providerConfig(
            DocumentAnalysisProviderType provider) {
        return switch (provider) {
            case DOCUMENT_INTELLIGENCE -> properties.documentIntelligence();
            case CONTENT_UNDERSTANDING -> properties.contentUnderstanding();
        };
    }

    private void enforceAbuseLimits(AppUser user) {
        long active = jobRepository.countByRequestedByUserIdAndStatusIn(
                user.getId(), ACTIVE_STATUSES);
        if (active >= properties.maxActiveJobsPerUser()) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "DOCUMENT_ANALYSIS_CONCURRENCY_LIMIT",
                    "同時に実行できる分析要求数の上限に達しています。");
        }
        long recent = jobRepository.countByRequestedByUserIdAndCreatedAtGreaterThanEqual(
                user.getId(), Instant.now().minus(1, ChronoUnit.HOURS));
        if (recent >= properties.maxRequestsPerUserPerHour()) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "DOCUMENT_ANALYSIS_RATE_LIMIT",
                    "分析要求の回数上限に達しています。時間をおいて再試行してください。");
        }
    }

    private void compensateInput(String inputObjectName) {
        try {
            storage.deleteInputIfExists(inputObjectName);
        } catch (RuntimeException ignored) {
            // The original transaction failure is returned to the caller.
        }
    }

    private static Map<String, Object> auditData(DocumentAnalysisJob job) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("analysisId", job.getId());
        data.put("provider", job.getProvider());
        data.put("contentType", job.getContentType());
        data.put("fileSize", job.getFileSize());
        data.put("sha256", job.getSha256());
        return data;
    }

    private static ApiException notFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "DOCUMENT_ANALYSIS_NOT_FOUND",
                "分析Jobが見つかりません。");
    }

    private static ApiException storageUnavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "DOCUMENT_ANALYSIS_STORAGE_UNAVAILABLE",
                "分析ファイルストレージへ接続できません。");
    }

    private static void closeQuietly(StoredDocumentAnalysisContent content) {
        try {
            content.stream().close();
        } catch (IOException ignored) {
            // The storage validation failure is the primary failure.
        }
    }
}
