package jp.co.sdcj.workflow.engine.definition;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowAssigneeRuleRepository extends JpaRepository<WorkflowAssigneeRule, UUID> {
    List<WorkflowAssigneeRule> findAllByWorkflowNodeIdIn(Collection<UUID> nodeIds);
    Optional<WorkflowAssigneeRule> findByWorkflowNodeId(UUID nodeId);
}
