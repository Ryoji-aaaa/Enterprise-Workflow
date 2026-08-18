package jp.co.sdcj.workflow.engine.runtime;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowInstanceActionRepository extends JpaRepository<WorkflowInstanceAction, UUID> {
    List<WorkflowInstanceAction> findAllByWorkflowInstanceIdOrderByCreatedAt(UUID instanceId);
}
