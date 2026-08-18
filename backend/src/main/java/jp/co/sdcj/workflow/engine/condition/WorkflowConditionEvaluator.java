package jp.co.sdcj.workflow.engine.condition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class WorkflowConditionEvaluator {
    private final ObjectMapper objectMapper;
    public WorkflowConditionEvaluator(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public boolean evaluate(String json, WorkflowContext context, WorkflowContextSchema schema) {
        if (json == null || json.isBlank()) return true;
        try { return evaluate(objectMapper.readTree(json), context, schema); }
        catch (JacksonException exception) {
            throw new WorkflowDefinitionException("Invalid workflow condition JSON");
        }
    }

    public void validate(String json, WorkflowContextSchema schema) {
        if (json == null || json.isBlank()) return;
        try { validate(objectMapper.readTree(json), schema); }
        catch (JacksonException exception) {
            throw new WorkflowDefinitionException("Invalid workflow condition JSON");
        }
    }

    private boolean evaluate(JsonNode node, WorkflowContext context, WorkflowContextSchema schema) {
        if (node == null || !node.isObject()) invalid("Condition must be an object");
        if (node.has("all")) return children(node.get("all")).stream()
                .allMatch(child -> evaluate(child, context, schema));
        if (node.has("any")) return children(node.get("any")).stream()
                .anyMatch(child -> evaluate(child, context, schema));
        if (node.has("not")) return !evaluate(node.get("not"), context, schema);
        String field = requiredText(node, "field");
        WorkflowFieldType type = schema.require(field);
        String operator = requiredText(node, "operator");
        validateLeaf(node, type, operator);
        Object actual = context.value(field);
        return switch (operator) {
            case "IS_NULL" -> actual == null;
            case "IS_NOT_NULL" -> actual != null;
            case "EQ" -> equal(actual, node.get("value"), type);
            case "NE" -> !equal(actual, node.get("value"), type);
            case "GT" -> compare(actual, node.get("value"), type) > 0;
            case "GTE" -> compare(actual, node.get("value"), type) >= 0;
            case "LT" -> compare(actual, node.get("value"), type) < 0;
            case "LTE" -> compare(actual, node.get("value"), type) <= 0;
            case "IN" -> contains(actual, node.get("value"), type);
            case "NOT_IN" -> !contains(actual, node.get("value"), type);
            default -> throw new WorkflowDefinitionException("Unknown operator: " + operator);
        };
    }

    private void validate(JsonNode node, WorkflowContextSchema schema) {
        if (node == null || !node.isObject()) invalid("Condition must be an object");
        int forms = (node.has("all") ? 1 : 0) + (node.has("any") ? 1 : 0)
                + (node.has("not") ? 1 : 0) + (node.has("field") ? 1 : 0);
        if (forms != 1) invalid("Condition must contain exactly one expression");
        if (node.has("all")) { children(node.get("all")).forEach(child -> validate(child, schema)); return; }
        if (node.has("any")) { children(node.get("any")).forEach(child -> validate(child, schema)); return; }
        if (node.has("not")) { validate(node.get("not"), schema); return; }
        WorkflowFieldType type = schema.require(requiredText(node, "field"));
        validateLeaf(node, type, requiredText(node, "operator"));
    }

    private static void validateLeaf(JsonNode node, WorkflowFieldType type, String operator) {
        List<String> supported = List.of("EQ", "NE", "GT", "GTE", "LT", "LTE", "IN",
                "NOT_IN", "IS_NULL", "IS_NOT_NULL");
        if (!supported.contains(operator)) invalid("Unknown operator: " + operator);
        if ((operator.equals("GT") || operator.equals("GTE") || operator.equals("LT")
                || operator.equals("LTE")) && type != WorkflowFieldType.NUMBER) {
            invalid("Ordering operator requires NUMBER field");
        }
        boolean noValue = operator.equals("IS_NULL") || operator.equals("IS_NOT_NULL");
        if (noValue && node.has("value")) invalid(operator + " must not have value");
        if (!noValue && !node.has("value")) invalid(operator + " requires value");
        if (operator.equals("IN") || operator.equals("NOT_IN")) {
            if (!node.get("value").isArray() || node.get("value").isEmpty()) {
                invalid(operator + " requires a non-empty array");
            }
            node.get("value").forEach(value -> validateValue(value, type));
        } else if (!noValue) validateValue(node.get("value"), type);
    }

    private static void validateValue(JsonNode value, WorkflowFieldType type) {
        if (value == null || value.isNull()) return;
        boolean valid = switch (type) {
            case BOOLEAN -> value.isBoolean();
            case NUMBER -> value.isNumber();
            case STRING, UUID -> value.isString();
        };
        if (!valid) invalid("Condition value does not match field type " + type);
        if (type == WorkflowFieldType.UUID) {
            try { java.util.UUID.fromString(value.asText()); }
            catch (IllegalArgumentException exception) { invalid("Condition value is not UUID"); }
        }
    }

    private static boolean equal(Object actual, JsonNode expected, WorkflowFieldType type) {
        if (actual == null || expected == null || expected.isNull()) return actual == null && expected != null && expected.isNull();
        return switch (type) {
            case NUMBER -> number(actual).compareTo(expected.decimalValue()) == 0;
            case BOOLEAN -> actual.equals(expected.booleanValue());
            case STRING, UUID -> actual.toString().equals(expected.asText());
        };
    }

    private static int compare(Object actual, JsonNode expected, WorkflowFieldType type) {
        if (actual == null) return -1;
        if (type != WorkflowFieldType.NUMBER) invalid("Ordering comparison requires NUMBER");
        return number(actual).compareTo(expected.decimalValue());
    }
    private static boolean contains(Object actual, JsonNode values, WorkflowFieldType type) {
        if (actual == null) return false;
        for (JsonNode value : values) if (equal(actual, value, type)) return true;
        return false;
    }
    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal number) return number;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        throw new WorkflowDefinitionException("Context NUMBER field has invalid value");
    }
    private static String requiredText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isString() || value.asText().isBlank()) invalid(name + " is required");
        return value.asText();
    }
    private static List<JsonNode> children(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) invalid("Boolean composition requires children");
        List<JsonNode> children = new ArrayList<>(); node.forEach(children::add); return children;
    }
    private static void invalid(String message) { throw new WorkflowDefinitionException(message); }
}
