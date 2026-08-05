package jp.co.sdcj.workflow.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
import jp.co.sdcj.workflow.domain.ExpenseApprovalCandidate;
import jp.co.sdcj.workflow.domain.ExpenseApprovalRun;
import jp.co.sdcj.workflow.domain.ExpenseApprovalStep;
import jp.co.sdcj.workflow.domain.ExpenseApprovalStepStatus;
import jp.co.sdcj.workflow.repository.ExpenseApplicationItemRepository;
import jp.co.sdcj.workflow.repository.ExpenseApplicationRepository;
import jp.co.sdcj.workflow.repository.ExpenseApprovalCandidateRepository;
import jp.co.sdcj.workflow.repository.ExpenseApprovalRunRepository;
import jp.co.sdcj.workflow.repository.ExpenseApprovalStepRepository;
import jp.co.sdcj.workflow.service.ResolvedApprovalRoute.ApplicantOrganizationSnapshot;

@Service
public class ExpenseApplicationService {
    private static final BigDecimal MAX_TOTAL_AMOUNT = new BigDecimal("999999999999");

    private final ExpenseApplicationRepository applicationRepository;
    private final ExpenseApplicationItemRepository itemRepository;
    private final ExpenseApprovalRunRepository runRepository;
    private final ExpenseApprovalStepRepository stepRepository;
    private final ExpenseApprovalCandidateRepository candidateRepository;
    private final ExpenseApprovalRouteResolver routeResolver;
    private final AuditLogService auditLogService;
    private final ExpenseNotificationService notificationService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ExpenseApplicationService(
            ExpenseApplicationRepository applicationRepository,
            ExpenseApplicationItemRepository itemRepository,
            ExpenseApprovalRunRepository runRepository,
            ExpenseApprovalStepRepository stepRepository,
            ExpenseApprovalCandidateRepository candidateRepository,
            ExpenseApprovalRouteResolver routeResolver,
            AuditLogService auditLogService,
            ExpenseNotificationService notificationService,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.applicationRepository = applicationRepository;
        this.itemRepository = itemRepository;
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.candidateRepository = candidateRepository;
        this.routeResolver = routeResolver;
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ExpenseApplicationDetails createDraft(
            ExpenseApplicationInput input, AppUser applicant) {
        Instant now = Instant.now();
        ApplicantOrganizationSnapshot organization = routeResolver.resolveOrganization(applicant, now);
        BigDecimal total = validateAndTotal(input);
        ExpenseApplication application = applicationRepository.save(new ExpenseApplication(
                nextApplicationNumber(), applicant, organization.unit().getOrganizationId(),
                organization.unit(), organization.division(), input.category(), input.title(),
                input.purpose(), input.expenseDate(), total, input.remarks(), applicant.getId()));
        List<ExpenseApplicationItem> items = saveItems(application.getId(), input.items());
        auditLogService.recordSuccess(
                AuditActor.user(applicant), "EXPENSE_APPLICATION_CREATED", "EXPENSE_APPLICATION",
                application.getId().toString(), null,
                Map.of("applicationNumber", application.getApplicationNumber(),
                        "status", application.getStatus().name()), null);
        return new ExpenseApplicationDetails(application, items, null, List.of());
    }

    @Transactional
    public ExpenseApplicationDetails update(
            UUID applicationId, ExpenseApplicationInput input, long version, AppUser applicant) {
        ExpenseApplication application = ownedForUpdate(applicationId, applicant);
        if (application.getStatus() != ExpenseApplicationStatus.DRAFT
                && application.getStatus() != ExpenseApplicationStatus.RETURNED) {
            throw conflict("EXPENSE_APPLICATION_NOT_EDITABLE", "この申請は編集できません。");
        }
        if (application.getVersion() != version) {
            throw conflict("OPTIMISTIC_LOCK_CONFLICT", "他の更新と競合しました。再読み込みしてください。");
        }
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
        ResolvedApprovalRoute route = routeResolver.resolve(applicant, now);
        application.updateOrganizationSnapshot(
                route.organization().unit().getOrganizationId(),
                route.organization().unit(), route.organization().division());
        int runNumber = Math.toIntExact(runRepository.countByExpenseApplicationId(applicationId) + 1);
        ExpenseApprovalRun run = runRepository.save(new ExpenseApprovalRun(
                applicationId, runNumber, organizationSnapshot(route), now, applicant.getId()));
        List<ExpenseApprovalCandidate> firstCandidates = List.of();
        for (int index = 0; index < route.steps().size(); index++) {
            var resolvedStep = route.steps().get(index);
            ExpenseApprovalStep step = stepRepository.save(new ExpenseApprovalStep(
                    run.getId(), index + 1, resolvedStep.type(), resolvedStep.target(),
                    index == 0 ? ExpenseApprovalStepStatus.PENDING
                            : ExpenseApprovalStepStatus.WAITING));
            List<ExpenseApprovalCandidate> candidates = resolvedStep.candidates().stream()
                    .map(candidate -> new ExpenseApprovalCandidate(
                            step.getId(), candidate.user(), candidate.assignment(), candidate.position()))
                    .toList();
            candidates = candidateRepository.saveAll(candidates);
            if (index == 0) firstCandidates = candidates;
        }
        application.submit(now, applicant.getId());
        String action = resubmit
                ? "EXPENSE_APPLICATION_RESUBMITTED" : "EXPENSE_APPLICATION_SUBMITTED";
        auditLogService.recordSuccess(
                AuditActor.user(applicant), action, "EXPENSE_APPLICATION",
                applicationId.toString(), Map.of("status", required.name()),
                Map.of("applicationNumber", application.getApplicationNumber(),
                        "status", application.getStatus().name(), "runNumber", runNumber), null);
        notificationService.notifyCandidates(application, firstCandidates);
        return new ExpenseApplicationDetails(
                application, items, run,
                stepRepository.findAllByApprovalRunIdOrderByStepOrder(run.getId()));
    }

    @Transactional
    public ExpenseApplicationDetails cancel(UUID applicationId, AppUser applicant) {
        ExpenseApplication application = ownedForUpdate(applicationId, applicant);
        if (application.getStatus() != ExpenseApplicationStatus.PENDING_APPROVAL) {
            throw conflict("EXPENSE_APPLICATION_INVALID_STATUS", "現在の状態では取り下げできません。");
        }
        ExpenseApprovalRun run = runRepository
                .findFirstByExpenseApplicationIdOrderByRunNumberDesc(applicationId)
                .orElseThrow(() -> new IllegalStateException("Pending application has no approval run"));
        List<ExpenseApprovalStep> steps = stepRepository.findAllByApprovalRunIdOrderByStepOrder(run.getId());
        if (steps.stream().anyMatch(step -> step.getStatus() == ExpenseApprovalStepStatus.APPROVED)) {
            throw conflict("EXPENSE_APPLICATION_ALREADY_PROCESSED", "承認済みのステップがあるため取り下げできません。");
        }
        steps.stream().filter(step -> step.getStatus() == ExpenseApprovalStepStatus.PENDING
                        || step.getStatus() == ExpenseApprovalStepStatus.WAITING)
                .forEach(ExpenseApprovalStep::cancel);
        Instant now = Instant.now();
        run.cancel(now);
        application.cancel(now, applicant.getId());
        auditLogService.recordSuccess(
                AuditActor.user(applicant), "EXPENSE_APPLICATION_CANCELLED", "EXPENSE_APPLICATION",
                applicationId.toString(), Map.of("status", "PENDING_APPROVAL"),
                Map.of("applicationNumber", application.getApplicationNumber(),
                        "status", "CANCELLED", "runNumber", run.getRunNumber()), null);
        return new ExpenseApplicationDetails(application,
                itemRepository.findAllByExpenseApplicationIdOrderByDisplayOrder(applicationId),
                run, steps);
    }

    @Transactional(readOnly = true)
    public Page<ExpenseApplication> getMine(
            AppUser applicant, ExpenseApplicationStatus status, Pageable pageable) {
        return applicationRepository.findMine(applicant.getId(), status, pageable);
    }

    @Transactional(readOnly = true)
    public ExpenseApplicationDetails getAccessible(UUID id, AppUser user) {
        ExpenseApplication application = applicationRepository.findById(id)
                .orElseThrow(ExpenseApplicationService::notFound);
        if (!application.getApplicantUserId().equals(user.getId())
                && !candidateRepository.existsForApplication(id, user.getId())) {
            auditLogService.recordDenied(
                    AuditActor.user(user), "EXPENSE_APPLICATION_READ_DENIED",
                    "EXPENSE_APPLICATION", id.toString(), "NOT_OWNER_OR_CURRENT_CANDIDATE");
            throw notFound();
        }
        return details(application, null);
    }

    private ExpenseApplicationDetails details(
            ExpenseApplication application, List<ExpenseApplicationItem> knownItems) {
        List<ExpenseApplicationItem> items = knownItems == null
                ? itemRepository.findAllByExpenseApplicationIdOrderByDisplayOrder(application.getId())
                : knownItems;
        ExpenseApprovalRun run = runRepository
                .findFirstByExpenseApplicationIdOrderByRunNumberDesc(application.getId()).orElse(null);
        List<ExpenseApprovalStep> steps = run == null ? List.of()
                : stepRepository.findAllByApprovalRunIdOrderByStepOrder(run.getId());
        return new ExpenseApplicationDetails(application, items, run, steps);
    }

    private ExpenseApplication ownedForUpdate(UUID id, AppUser user) {
        ExpenseApplication application = applicationRepository.findByIdForUpdate(id)
                .orElseThrow(ExpenseApplicationService::notFound);
        if (!application.getApplicantUserId().equals(user.getId())) {
            auditLogService.recordDenied(
                    AuditActor.user(user), "EXPENSE_APPLICATION_UPDATE_DENIED",
                    "EXPENSE_APPLICATION", id.toString(), "NOT_OWNER");
            throw notFound();
        }
        return application;
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

    private String organizationSnapshot(ResolvedApprovalRoute route) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("applicantAssignmentId", route.organization().assignment().getId());
        snapshot.put("applicantUnitId", route.organization().unit().getId());
        snapshot.put("applicantUnitName", route.organization().unit().getUnitName());
        snapshot.put("applicantPositionId", route.organization().position() == null
                ? null : route.organization().position().getId());
        snapshot.put("applicantPositionName", route.organization().position() == null
                ? null : route.organization().position().getPositionName());
        snapshot.put("divisionUnitId", route.organization().division().getId());
        snapshot.put("divisionUnitName", route.organization().division().getUnitName());
        snapshot.put("resolvedAt", route.resolvedAt());
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize expense organization snapshot", exception);
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "EXPENSE_APPLICATION_NOT_FOUND", "経費申請が見つかりません。");
    }
    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
    private static ApiException businessError(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
