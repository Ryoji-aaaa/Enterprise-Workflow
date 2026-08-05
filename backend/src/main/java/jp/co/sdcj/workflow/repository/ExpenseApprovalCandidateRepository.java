package jp.co.sdcj.workflow.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jp.co.sdcj.workflow.domain.ExpenseApprovalCandidate;

public interface ExpenseApprovalCandidateRepository extends JpaRepository<ExpenseApprovalCandidate, UUID> {
    List<ExpenseApprovalCandidate> findAllByApprovalStepId(UUID stepId);
    boolean existsByApprovalStepIdAndCandidateUserId(UUID stepId, UUID userId);

    @Query("""
            select case when count(candidate) > 0 then true else false end
            from ExpenseApprovalCandidate candidate, ExpenseApprovalStep step,
                 ExpenseApprovalRun run
            where candidate.candidateUserId = :userId
              and candidate.approvalStepId = step.id
              and step.approvalRunId = run.id
              and run.expenseApplicationId = :applicationId
              and run.runNumber = (
                  select max(currentRun.runNumber)
                  from ExpenseApprovalRun currentRun
                  where currentRun.expenseApplicationId = :applicationId
              )
            """)
    boolean existsForApplication(
            @Param("applicationId") UUID applicationId,
            @Param("userId") UUID userId);
}
