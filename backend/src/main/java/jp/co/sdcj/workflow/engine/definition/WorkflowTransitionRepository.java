package jp.co.sdcj.workflow.engine.definition;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowTransitionRepository extends JpaRepository<WorkflowTransition, UUID> {
    List<WorkflowTransition> findAllByWorkflowDefinitionVersionId(UUID versionId);
}
