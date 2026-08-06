package jp.co.sdcj.workflow.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jp.co.sdcj.workflow.domain.ExpenseApplicationAttachment;

public interface ExpenseApplicationAttachmentRepository
        extends JpaRepository<ExpenseApplicationAttachment, UUID> {

    List<ExpenseApplicationAttachment>
            findAllByExpenseApplicationIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(
                    UUID expenseApplicationId);

    long countByExpenseApplicationIdAndDeletedAtIsNull(UUID expenseApplicationId);

    @Query("""
            select coalesce(sum(attachment.fileSize), 0)
            from ExpenseApplicationAttachment attachment
            where attachment.expenseApplicationId = :applicationId
              and attachment.deletedAt is null
            """)
    long activeFileSize(@Param("applicationId") UUID applicationId);

    Optional<ExpenseApplicationAttachment>
            findByIdAndExpenseApplicationIdAndDeletedAtIsNull(
                    UUID id, UUID expenseApplicationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select attachment from ExpenseApplicationAttachment attachment
            where attachment.id = :id
              and attachment.expenseApplicationId = :applicationId
              and attachment.deletedAt is null
            """)
    Optional<ExpenseApplicationAttachment> findActiveForUpdate(
            @Param("applicationId") UUID applicationId,
            @Param("id") UUID id);
}
