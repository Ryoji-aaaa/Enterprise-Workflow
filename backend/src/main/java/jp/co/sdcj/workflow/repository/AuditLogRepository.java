package jp.co.sdcj.workflow.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import jp.co.sdcj.workflow.domain.AuditLog;
import jp.co.sdcj.workflow.domain.AuditResult;

/** Append-only persistence and paged lookup interface for audit records. */
public interface AuditLogRepository extends Repository<AuditLog, UUID> {

    AuditLog save(AuditLog auditLog);

    Optional<AuditLog> findById(UUID id);

    Page<AuditLog> findAll(Pageable pageable);

    default Page<AuditLog> search(
            UUID actorUserId,
            String actionType,
            String targetType,
            String targetId,
            Instant fromTime,
            Instant toTime,
            AuditResult result,
            Pageable pageable) {
        if (fromTime == null && toTime == null) {
            return searchWithoutPeriod(actorUserId, actionType, targetType, targetId,
                    result, pageable);
        }
        if (fromTime == null) {
            return searchUntil(actorUserId, actionType, targetType, targetId,
                    toTime, result, pageable);
        }
        if (toTime == null) {
            return searchFrom(actorUserId, actionType, targetType, targetId,
                    fromTime, result, pageable);
        }
        return searchBetween(actorUserId, actionType, targetType, targetId,
                fromTime, toTime, result, pageable);
    }

    @Query("""
            select audit from AuditLog audit
            where (:actorUserId is null or audit.actorUserId = :actorUserId)
              and (:actionType is null or audit.actionType = :actionType)
              and (:targetType is null or audit.targetType = :targetType)
              and (:targetId is null or audit.targetId = :targetId)
              and (:result is null or audit.result = :result)
            """)
    Page<AuditLog> searchWithoutPeriod(
            @Param("actorUserId") UUID actorUserId,
            @Param("actionType") String actionType,
            @Param("targetType") String targetType,
            @Param("targetId") String targetId,
            @Param("result") AuditResult result,
            Pageable pageable);

    @Query("""
            select audit from AuditLog audit
            where (:actorUserId is null or audit.actorUserId = :actorUserId)
              and (:actionType is null or audit.actionType = :actionType)
              and (:targetType is null or audit.targetType = :targetType)
              and (:targetId is null or audit.targetId = :targetId)
              and audit.occurredAt >= :fromTime
              and (:result is null or audit.result = :result)
            """)
    Page<AuditLog> searchFrom(
            @Param("actorUserId") UUID actorUserId,
            @Param("actionType") String actionType,
            @Param("targetType") String targetType,
            @Param("targetId") String targetId,
            @Param("fromTime") Instant fromTime,
            @Param("result") AuditResult result,
            Pageable pageable);

    @Query("""
            select audit from AuditLog audit
            where (:actorUserId is null or audit.actorUserId = :actorUserId)
              and (:actionType is null or audit.actionType = :actionType)
              and (:targetType is null or audit.targetType = :targetType)
              and (:targetId is null or audit.targetId = :targetId)
              and audit.occurredAt < :toTime
              and (:result is null or audit.result = :result)
            """)
    Page<AuditLog> searchUntil(
            @Param("actorUserId") UUID actorUserId,
            @Param("actionType") String actionType,
            @Param("targetType") String targetType,
            @Param("targetId") String targetId,
            @Param("toTime") Instant toTime,
            @Param("result") AuditResult result,
            Pageable pageable);

    @Query("""
            select audit from AuditLog audit
            where (:actorUserId is null or audit.actorUserId = :actorUserId)
              and (:actionType is null or audit.actionType = :actionType)
              and (:targetType is null or audit.targetType = :targetType)
              and (:targetId is null or audit.targetId = :targetId)
              and audit.occurredAt >= :fromTime
              and audit.occurredAt < :toTime
              and (:result is null or audit.result = :result)
            """)
    Page<AuditLog> searchBetween(
            @Param("actorUserId") UUID actorUserId,
            @Param("actionType") String actionType,
            @Param("targetType") String targetType,
            @Param("targetId") String targetId,
            @Param("fromTime") Instant fromTime,
            @Param("toTime") Instant toTime,
            @Param("result") AuditResult result,
            Pageable pageable);
}
