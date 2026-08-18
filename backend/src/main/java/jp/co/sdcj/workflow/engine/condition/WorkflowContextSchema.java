package jp.co.sdcj.workflow.engine.condition;

import java.util.Map;

public record WorkflowContextSchema(Map<String, WorkflowFieldType> fields) {
    public WorkflowContextSchema { fields = Map.copyOf(fields); }
    public WorkflowFieldType require(String field) {
        WorkflowFieldType type = fields.get(field);
        if (type == null) throw new WorkflowDefinitionException("Unknown workflow field: " + field);
        return type;
    }
}
