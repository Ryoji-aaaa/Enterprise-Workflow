package jp.co.sdcj.workflow.engine.assignee;

import java.util.Map;
import jp.co.sdcj.workflow.domain.AppUser;

public record ResolvedWorkflowCandidate(AppUser user, Map<String, Object> sourceSnapshot) {}
