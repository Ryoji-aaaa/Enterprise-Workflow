package jp.co.sdcj.workflow.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import jp.co.sdcj.workflow.domain.ExpenseApprovalStep;
import jp.co.sdcj.workflow.domain.ExpenseApprovalStepStatus;

public interface ExpenseApprovalStepRepository extends JpaRepository<ExpenseApprovalStep, UUID> {
    List<ExpenseApprovalStep> findAllByApprovalRunIdOrderByStepOrder(UUID runId);
    Optional<ExpenseApprovalStep> findFirstByApprovalRunIdAndStatusOrderByStepOrder(
            UUID runId, ExpenseApprovalStepStatus status);
    boolean existsByApprovalRunIdAndStatus(UUID runId, ExpenseApprovalStepStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select step from ExpenseApprovalStep step where step.id = :id")
    Optional<ExpenseApprovalStep> findByIdForUpdate(@Param("id") UUID id);
}
