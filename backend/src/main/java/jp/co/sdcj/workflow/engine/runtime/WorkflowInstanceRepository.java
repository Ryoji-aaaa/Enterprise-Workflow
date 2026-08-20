package jp.co.sdcj.workflow.engine.runtime;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, UUID> {
    Optional<WorkflowInstance> findFirstBySubjectTypeAndSubjectIdOrderByRunNumberDesc(
            String subjectType, UUID subjectId);
    List<WorkflowInstance> findAllBySubjectTypeAndSubjectIdOrderByRunNumberDesc(
            String subjectType, UUID subjectId);
    @Query("""
            select instance.id from WorkflowInstance instance
            where instance.subjectType = :subjectType
              and instance.subjectId = :subjectId
              and instance.runNumber = (
                  select max(latest.runNumber) from WorkflowInstance latest
                  where latest.subjectType = :subjectType
                    and latest.subjectId = :subjectId
              )
            """)
    Optional<UUID> findLatestIdBySubject(
            @Param("subjectType") String subjectType, @Param("subjectId") UUID subjectId);
    long countBySubjectTypeAndSubjectId(String subjectType, UUID subjectId);
}
