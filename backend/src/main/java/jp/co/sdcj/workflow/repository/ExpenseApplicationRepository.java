package jp.co.sdcj.workflow.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import jp.co.sdcj.workflow.domain.ExpenseApplication;
import jp.co.sdcj.workflow.domain.ExpenseApplicationStatus;

public interface ExpenseApplicationRepository extends JpaRepository<ExpenseApplication, UUID> {
    @Query("""
            select application from ExpenseApplication application
            where application.applicantUserId = :userId
              and (:status is null or application.status = :status)
            """)
    Page<ExpenseApplication> findMine(
            @Param("userId") UUID userId,
            @Param("status") ExpenseApplicationStatus status,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select application from ExpenseApplication application where application.id = :id")
    Optional<ExpenseApplication> findByIdForUpdate(@Param("id") UUID id);

}
