package jp.co.sdcj.workflow.engine.definition;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {
    Optional<WorkflowDefinition> findByWorkflowCodeAndEnabledTrue(String workflowCode);
}
