package jp.co.sdcj.workflow.engine.definition;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowDefinitionVersionRepository
        extends JpaRepository<WorkflowDefinitionVersion, UUID> {
    @Query("""
            select version from WorkflowDefinitionVersion version
            where version.workflowDefinitionId = :definitionId
              and version.status = jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionStatus.PUBLISHED
              and (version.effectiveFrom is null or version.effectiveFrom <= :at)
              and (version.effectiveUntil is null or version.effectiveUntil > :at)
            order by version.versionNumber desc
            limit 1
            """)
    Optional<WorkflowDefinitionVersion> findPublishedAt(
            @Param("definitionId") UUID definitionId, @Param("at") Instant at);
}
