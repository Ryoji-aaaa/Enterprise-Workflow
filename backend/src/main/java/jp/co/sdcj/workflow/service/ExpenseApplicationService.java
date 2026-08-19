package jp.co.sdcj.workflow.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.ExpenseApplication;
import jp.co.sdcj.workflow.domain.ExpenseApplicationItem;
import jp.co.sdcj.workflow.domain.ExpenseApplicationStatus;
import jp.co.sdcj.workflow.engine.WorkflowEngine;
import jp.co.sdcj.workflow.engine.runtime.WorkflowRuntimeService;
import jp.co.sdcj.workflow.engine.subject.ApplicantOrganizationResolver;
import jp.co.sdcj.workflow.engine.subject.ExpenseWorkflowContextProvider;
import jp.co.sdcj.workflow.repository.ExpenseApplicationItemRepository;
import jp.co.sdcj.workflow.repository.ExpenseApplicationAutoEntryContextRepository;
import jp.co.sdcj.workflow.repository.ExpenseApplicationRepository;

@Service
public class ExpenseApplicationService {
    private static final BigDecimal MAX_TOTAL_AMOUNT = new BigDecimal("999999999999");

    private final ExpenseApplicationRepository applicationRepository;
    private final ExpenseApplicationAutoEntryContextRepository autoEntryContextRepository;
    private final ExpenseApplicationAccessService accessService;
    private final ExpenseApplicationItemRepository itemRepository;
    private final ApplicantOrganizationResolver organizationResolver;
    private final WorkflowEngine workflowEngine;
    private final WorkflowRuntimeService workflowRuntime;
    private final AuditLogService auditLogService;
    private final JdbcTemplate jdbcTemplate;

    public ExpenseApplicationService(
            ExpenseApplicationRepository applicationRepository,
            ExpenseApplicationAutoEntryContextRepository autoEntryContextRepository,
            ExpenseApplicationAccessService accessService,
            ExpenseApplicationItemRepository itemRepository,
            ApplicantOrganizationResolver organizationResolver,
            WorkflowEngine workflowEngine,
            WorkflowRuntimeService workflowRuntime,
            AuditLogService auditLogService,
            JdbcTemplate jdbcTemplate) {
        this.applicationRepository = applicationRepository;
        this.autoEntryContextRepository = autoEntryContextRepository;
        this.accessService = accessService;
        this.itemRepository = itemRepository;
        this.organizationResolver = organizationResolver;
        this.workflowEngine = workflowEngine;
        this.workflowRuntime = workflowRuntime;
        this.auditLogService = auditLogService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public ExpenseApplicationDetails createDraft(
            ExpenseApplicationInput input, AppUser applicant) {
        return createDraftWithId(UUID.randomUUID(), input, applicant);
    }

    @Transactional
    public ExpenseApplicationDetails createDraftWithId(
            UUID applicationId, ExpenseApplicationInput input, AppUser applicant) {
        Instant now = Instant.now();
        var organization = organizationResolver.resolve(applicant, now);
        BigDecimal total = validateAndTotal(input);
        ExpenseApplication application = applicationRepository.save(new ExpenseApplication(
                applicationId, nextApplicationNumber(), applicant,
                organization.unit().getOrganizationId(),
                organization.unit(), organization.division(), input.category(), input.title(),
                input.purpose(), input.expenseDate(), total, input.remarks(), applicant.getId()));
        List<ExpenseApplicationItem> items = saveItems(application.getId(), input.items());
        auditLogService.recordSuccess(
                AuditActor.user(applicant), "EXPENSE_APPLICATION_CREATED", "EXPENSE_APPLICATION",
                application.getId().toString(), null,
                Map.of("applicationNumber", application.getApplicationNumber(),
                        "status", application.getStatus().name()), null);
        return new ExpenseApplicationDetails(application, items);
    }

    @Transactional
    public ExpenseApplicationDetails update(
            UUID applicationId, ExpenseApplicationInput input, long version, AppUser applicant) {
        ExpenseApplication application = ownedForUpdate(applicationId, applicant);
        if (autoEntryContextRepository.existsByExpenseApplicationId(applicationId)) {
            throw conflict(
                    "EXPENSE_AUTO_ENTRY_DRAFT_REQUIRES_CONTEXT_UPDATE",
                    "自動入力で作成した下書きは専用APIから更新してください。");
        }
        return updateOwned(application, input, version, applicant, false);
    }

    @Transactional
    public ExpenseApplicationDetails updateAutoEntryDraft(
            UUID applicationId, ExpenseApplicationInput input, long version, AppUser applicant) {
        return updateOwned(
                ownedForUpdate(applicationId, applicant), input, version, applicant, true);
    }

    private ExpenseApplicationDetails updateOwned(
            ExpenseApplication application,
            ExpenseApplicationInput input,
            long version,
            AppUser applicant,
            boolean autoEntry) {
        if (application.getStatus() != ExpenseApplicationStatus.DRAFT
                && application.getStatus() != ExpenseApplicationStatus.RETURNED) {
            throw conflict(
                    autoEntry
                            ? "EXPENSE_AUTO_ENTRY_DRAFT_NOT_EDITABLE"
                            : "EXPENSE_APPLICATION_NOT_EDITABLE",
                    "この申請は編集できません。");
        }
        if (application.getVersion() != version) {
            throw conflict("OPTIMISTIC_LOCK_CONFLICT", "他の更新と競合しました。再読み込みしてください。");
        }
        UUID applicationId = application.getId();
        BigDecimal total = validateAndTotal(input);
        Map<String, Object> before = Map.of(
                "status", application.getStatus().name(), "totalAmount", application.getTotalAmount());
        application.updateContent(input.category(), input.title(), input.purpose(),
                input.expenseDate(), total, input.remarks(), applicant.getId());
        itemRepository.deleteAllByExpenseApplicationId(applicationId);
        itemRepository.flush();
        List<ExpenseApplicationItem> items = saveItems(applicationId, input.items());
        auditLogService.recordSuccess(
                AuditActor.user(applicant), "EXPENSE_APPLICATION_UPDATED", "EXPENSE_APPLICATION",
                applicationId.toString(), before,
                Map.of("applicationNumber", application.getApplicationNumber(),
                        "status", application.getStatus().name(), "totalAmount", total), null);
        return details(application, items);
    }

    @Transactional
    public ExpenseApplicationDetails submit(UUID applicationId, AppUser applicant, boolean resubmit) {
        ExpenseApplication application = ownedForUpdate(applicationId, applicant);
        ExpenseApplicationStatus required = resubmit
                ? ExpenseApplicationStatus.RETURNED : ExpenseApplicationStatus.DRAFT;
        if (application.getStatus() != required) {
            throw conflict("EXPENSE_APPLICATION_INVALID_STATUS", "現在の状態では申請できません。");
        }
        List<ExpenseApplicationItem> items = itemRepository
                .findAllByExpenseApplicationIdOrderByDisplayOrder(applicationId);
        if (items.isEmpty()) {
            throw businessError("EXPENSE_APPLICATION_ITEMS_REQUIRED", "明細を1件以上入力してください。");
        }
        BigDecimal itemTotal = items.stream().map(ExpenseApplicationItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (itemTotal.compareTo(application.getTotalAmount()) != 0) {
            throw businessError("EXPENSE_APPLICATION_AMOUNT_MISMATCH", "明細合計が申請金額と一致しません。");
        }

        Instant now = Instant.now();
        var organization = organizationResolver.resolve(applicant, now);
        application.updateOrganizationSnapshot(
                organization.unit().getOrganizationId(), organization.unit(), organization.division());
        workflowEngine.start("EXPENSE_APPROVAL", ExpenseWorkflowContextProvider.SUBJECT_TYPE,
                applicationId, applicant, now);
        return new ExpenseApplicationDetails(application, items);
    }

    @Transactional
    public ExpenseApplicationDetails cancel(UUID applicationId, AppUser applicant) {
        ExpenseApplication application = ownedForUpdate(applicationId, applicant);
        if (application.getStatus() != ExpenseApplicationStatus.PENDING_APPROVAL) {
            throw conflict("EXPENSE_APPLICATION_INVALID_STATUS", "現在の状態では取り下げできません。");
        }
        workflowRuntime.cancelLatest(ExpenseWorkflowContextProvider.SUBJECT_TYPE, applicationId, applicant);
        return new ExpenseApplicationDetails(application,
                itemRepository.findAllByExpenseApplicationIdOrderByDisplayOrder(applicationId));
    }

    @Transactional(readOnly = true)
    public Page<ExpenseApplication> getMine(
            AppUser applicant, ExpenseApplicationStatus status, Pageable pageable) {
        return applicationRepository.findMine(applicant.getId(), status, pageable);
    }

    @Transactional(readOnly = true)
    public ExpenseApplicationDetails getAccessible(UUID id, AppUser user) {
        ExpenseApplication application = accessService.accessible(
                id, user, "EXPENSE_APPLICATION_READ_DENIED");
        return details(application, null);
    }

    private ExpenseApplicationDetails details(
            ExpenseApplication application, List<ExpenseApplicationItem> knownItems) {
        List<ExpenseApplicationItem> items = knownItems == null
                ? itemRepository.findAllByExpenseApplicationIdOrderByDisplayOrder(application.getId())
                : knownItems;
        return new ExpenseApplicationDetails(application, items);
    }

    private ExpenseApplication ownedForUpdate(UUID id, AppUser user) {
        return accessService.ownedForUpdate(id, user, "EXPENSE_APPLICATION_UPDATE_DENIED");
    }

    private List<ExpenseApplicationItem> saveItems(
            UUID applicationId, List<ExpenseApplicationInput.Item> items) {
        return itemRepository.saveAll(java.util.stream.IntStream.range(0, items.size())
                .mapToObj(index -> {
                    ExpenseApplicationInput.Item item = items.get(index);
                    return new ExpenseApplicationItem(applicationId, index, item.expenseDate(),
                            item.description(), item.amount(), item.merchantName(), item.origin(),
                            item.destination(), item.transportationType(), item.participants());
                }).toList());
    }

    private static BigDecimal validateAndTotal(ExpenseApplicationInput input) {
        if (input.items() == null || input.items().isEmpty()) {
            throw businessError("EXPENSE_APPLICATION_ITEMS_REQUIRED", "明細を1件以上入力してください。");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (ExpenseApplicationInput.Item item : input.items()) {
            if (item.amount() == null || item.amount().signum() <= 0) {
                throw businessError("EXPENSE_APPLICATION_AMOUNT_MISMATCH", "明細金額は1円以上で入力してください。");
            }
            switch (input.category()) {
                case MEAL -> requireFields(
                        item.merchantName(), "店舗名", item.participants(), "参加者");
                case TRANSPORTATION -> requireFields(
                        item.transportationType(), "交通手段", item.origin(), "出発地",
                        item.destination(), "到着地");
                case TRAINING -> requireFields(item.merchantName(), "主催者");
                case CERTIFICATION -> requireFields(item.merchantName(), "試験実施団体");
                case OTHER -> {
                    // The common description field is validated by the request DTO.
                }
            }
            total = total.add(item.amount());
            if (total.compareTo(MAX_TOTAL_AMOUNT) > 0) {
                throw businessError(
                        "EXPENSE_APPLICATION_TOTAL_AMOUNT_EXCEEDED",
                        "明細合計は999,999,999,999円以下で入力してください。");
            }
        }
        return total;
    }

    private static void requireFields(String... valuesAndLabels) {
        for (int index = 0; index < valuesAndLabels.length; index += 2) {
            if (valuesAndLabels[index] == null || valuesAndLabels[index].isBlank()) {
                throw businessError("EXPENSE_APPLICATION_CATEGORY_FIELD_REQUIRED",
                        valuesAndLabels[index + 1] + "を入力してください。");
            }
        }
    }

    private String nextApplicationNumber() {
        Long sequence = jdbcTemplate.queryForObject(
                "select nextval('expense_application_number_seq')", Long.class);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return "EXP-%s-%06d".formatted(today.format(DateTimeFormatter.BASIC_ISO_DATE), sequence);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
    private static ApiException businessError(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
