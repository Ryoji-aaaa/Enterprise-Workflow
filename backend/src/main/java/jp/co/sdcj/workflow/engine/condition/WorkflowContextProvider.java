package jp.co.sdcj.workflow.engine.condition;

import java.time.Instant;
import java.util.UUID;
import jp.co.sdcj.workflow.domain.AppUser;

public interface WorkflowContextProvider {
    String subjectType();
    WorkflowContextSchema schema();
    WorkflowContext provide(UUID subjectId, AppUser requester, Instant at);
}
