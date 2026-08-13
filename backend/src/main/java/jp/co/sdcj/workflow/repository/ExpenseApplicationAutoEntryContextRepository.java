package jp.co.sdcj.workflow.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jp.co.sdcj.workflow.domain.ExpenseApplicationAutoEntryContext;

public interface ExpenseApplicationAutoEntryContextRepository
        extends JpaRepository<ExpenseApplicationAutoEntryContext, UUID> {

    Optional<ExpenseApplicationAutoEntryContext> findByAnalysisId(UUID analysisId);

    Optional<ExpenseApplicationAutoEntryContext> findByExpenseApplicationId(
            UUID expenseApplicationId);

    boolean existsByExpenseApplicationId(UUID expenseApplicationId);

    boolean existsBySourceAttachmentId(UUID sourceAttachmentId);

    @Query("""
            select context.sourceAttachmentId
            from ExpenseApplicationAutoEntryContext context
            where context.expenseApplicationId = :applicationId
            """)
    Optional<UUID> findSourceAttachmentIdByExpenseApplicationId(
            @Param("applicationId") UUID applicationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select context from ExpenseApplicationAutoEntryContext context
            where context.expenseApplicationId = :applicationId
            """)
    Optional<ExpenseApplicationAutoEntryContext> findByExpenseApplicationIdForUpdate(
            @Param("applicationId") UUID applicationId);
}
