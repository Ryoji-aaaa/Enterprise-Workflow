package jp.co.sdcj.workflow.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.ExpenseApplication;
import jp.co.sdcj.workflow.repository.ExpenseApplicationRepository;
import jp.co.sdcj.workflow.repository.ExpenseApprovalCandidateRepository;

@Service
public class ExpenseApplicationAccessService {

    private final ExpenseApplicationRepository applicationRepository;
    private final ExpenseApprovalCandidateRepository candidateRepository;
    private final AuditLogService auditLogService;

    public ExpenseApplicationAccessService(
            ExpenseApplicationRepository applicationRepository,
            ExpenseApprovalCandidateRepository candidateRepository,
            AuditLogService auditLogService) {
        this.applicationRepository = applicationRepository;
        this.candidateRepository = candidateRepository;
        this.auditLogService = auditLogService;
    }

    public ExpenseApplication accessible(UUID id, AppUser user, String deniedAction) {
        ExpenseApplication application = applicationRepository.findById(id)
                .orElseThrow(ExpenseApplicationAccessService::notFound);
        if (!application.getApplicantUserId().equals(user.getId())
                && !candidateRepository.existsForApplication(id, user.getId())) {
            deny(user, deniedAction, id, "NOT_OWNER_OR_CURRENT_CANDIDATE");
        }
        return application;
    }

    public ExpenseApplication ownedForUpdate(UUID id, AppUser user, String deniedAction) {
        ExpenseApplication application = applicationRepository.findByIdForUpdate(id)
                .orElseThrow(ExpenseApplicationAccessService::notFound);
        if (!application.getApplicantUserId().equals(user.getId())) {
            deny(user, deniedAction, id, "NOT_OWNER");
        }
        return application;
    }

    private void deny(AppUser user, String action, UUID id, String reason) {
        auditLogService.recordDenied(
                AuditActor.user(user), action, "EXPENSE_APPLICATION", id.toString(), reason);
        throw notFound();
    }

    private static ApiException notFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "EXPENSE_APPLICATION_NOT_FOUND",
                "経費申請が見つかりません。");
    }
}
