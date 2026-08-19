package jp.co.sdcj.workflow.engine.runtime;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowInstanceCandidateRepository
        extends JpaRepository<WorkflowInstanceCandidate, UUID> {
    List<WorkflowInstanceCandidate> findAllByWorkflowInstanceStepId(UUID stepId);
    boolean existsByWorkflowInstanceStepIdAndCandidateUserId(UUID stepId, UUID userId);
    Optional<WorkflowInstanceCandidate> findByWorkflowInstanceStepIdAndCandidateUserId(
            UUID stepId, UUID userId);
    @Query("""
            select case when count(candidate) > 0 then true else false end
            from WorkflowInstanceCandidate candidate, WorkflowInstanceStep step, WorkflowInstance instance
            where candidate.candidateUserId = :userId
              and candidate.workflowInstanceStepId = step.id
              and step.workflowInstanceId = instance.id
              and instance.subjectType = :subjectType
              and instance.subjectId = :subjectId
              and instance.status = jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceStatus.PENDING
              and step.status = jp.co.sdcj.workflow.engine.runtime.WorkflowStepStatus.PENDING
            """)
    boolean existsForCurrentSubject(@Param("subjectType") String subjectType,
            @Param("subjectId") UUID subjectId, @Param("userId") UUID userId);
    @Query("""
            select step from WorkflowInstanceStep step, WorkflowInstanceCandidate candidate
            where candidate.candidateUserId = :userId
              and candidate.workflowInstanceStepId = step.id
              and step.status = jp.co.sdcj.workflow.engine.runtime.WorkflowStepStatus.PENDING
            """)
    Page<WorkflowInstanceStep> findPendingStepsForCandidate(
            @Param("userId") UUID userId, Pageable pageable);
}
