package jp.co.sdcj.workflow.engine.subject;

import java.util.UUID;

public interface WorkflowSubjectSummaryProvider {
    String subjectType();
    WorkflowSubjectSummary summary(UUID subjectId);
}
