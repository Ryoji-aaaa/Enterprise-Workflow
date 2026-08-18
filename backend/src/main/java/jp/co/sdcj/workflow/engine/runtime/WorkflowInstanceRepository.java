package jp.co.sdcj.workflow.engine.runtime;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, UUID> {
    Optional<WorkflowInstance> findFirstBySubjectTypeAndSubjectIdOrderByRunNumberDesc(
            String subjectType, UUID subjectId);
    List<WorkflowInstance> findAllBySubjectTypeAndSubjectIdOrderByRunNumberDesc(
            String subjectType, UUID subjectId);
    long countBySubjectTypeAndSubjectId(String subjectType, UUID subjectId);
}
