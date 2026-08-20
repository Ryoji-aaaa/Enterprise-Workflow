package jp.co.sdcj.workflow.engine.subject;

import java.util.UUID;
import jp.co.sdcj.workflow.domain.AppUser;

public interface WorkflowSubjectAccessHandler {
    String subjectType();
    void requireAccess(UUID subjectId, AppUser user);
}
