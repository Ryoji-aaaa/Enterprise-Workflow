package jp.co.sdcj.workflow.engine.runtime;

import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class WorkflowAccessService {
    private final WorkflowInstanceCandidateRepository candidates;
    public WorkflowAccessService(WorkflowInstanceCandidateRepository candidates) { this.candidates = candidates; }
    public boolean isCurrentCandidate(String subjectType, UUID subjectId, UUID userId) {
        return candidates.existsForCurrentSubject(subjectType, subjectId, userId);
    }
}
