package jp.co.sdcj.workflow.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import jp.co.sdcj.workflow.domain.ExpenseApprovalRun;

public interface ExpenseApprovalRunRepository extends JpaRepository<ExpenseApprovalRun, UUID> {
    Optional<ExpenseApprovalRun> findFirstByExpenseApplicationIdOrderByRunNumberDesc(UUID applicationId);
    List<ExpenseApprovalRun> findAllByExpenseApplicationIdOrderByRunNumberDesc(UUID applicationId);
    long countByExpenseApplicationId(UUID applicationId);
}
