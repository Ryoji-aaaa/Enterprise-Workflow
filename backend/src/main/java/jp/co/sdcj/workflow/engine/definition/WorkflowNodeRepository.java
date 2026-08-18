package jp.co.sdcj.workflow.engine.definition;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowNodeRepository extends JpaRepository<WorkflowNode, UUID> {
    List<WorkflowNode> findAllByWorkflowDefinitionVersionId(UUID versionId);
}
