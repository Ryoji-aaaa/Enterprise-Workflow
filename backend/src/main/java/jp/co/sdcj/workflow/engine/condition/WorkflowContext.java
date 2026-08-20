package jp.co.sdcj.workflow.engine.condition;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record WorkflowContext(Map<String, Object> values) {
    public WorkflowContext { values = Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
    public Object value(String field) { return values.get(field); }
}
