package jp.co.sdcj.workflow.engine.runtime;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowInstanceStepRepository extends JpaRepository<WorkflowInstanceStep, UUID> {
    List<WorkflowInstanceStep> findAllByWorkflowInstanceIdOrderByStepOrder(UUID instanceId);
    Optional<WorkflowInstanceStep> findFirstByWorkflowInstanceIdAndStatusOrderByStepOrder(
            UUID instanceId, WorkflowStepStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select step from WorkflowInstanceStep step where step.id = :id")
    Optional<WorkflowInstanceStep> findByIdForUpdate(@Param("id") UUID id);
}
