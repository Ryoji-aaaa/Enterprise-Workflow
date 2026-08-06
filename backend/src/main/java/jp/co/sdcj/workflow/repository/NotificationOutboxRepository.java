package jp.co.sdcj.workflow.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import jp.co.sdcj.workflow.domain.NotificationOutbox;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, UUID> {
    boolean existsByDeduplicationKey(String deduplicationKey);

    @Query(value = """
            select *
            from notification_outbox
            where status in ('PENDING', 'RETRY_WAIT')
              and next_attempt_at <= :now
            order by created_at, id
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<NotificationOutbox> findDispatchableForUpdate(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select notification from NotificationOutbox notification where notification.id = :id")
    Optional<NotificationOutbox> findByIdForUpdate(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update notification_outbox
            set status = 'RETRY_WAIT',
                next_attempt_at = :now,
                processing_started_at = null,
                last_error_code = 'PROCESSING_TIMEOUT',
                last_error_message = 'Dispatcher processing timed out before completion.',
                updated_at = :now
            where status = 'PROCESSING'
              and processing_started_at < :cutoff
            """, nativeQuery = true)
    int recoverStaleProcessing(
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now);
}
