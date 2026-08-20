package jp.co.sdcj.workflow.engine.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WorkflowConditionEvaluatorTest {
    private final WorkflowConditionEvaluator evaluator = new WorkflowConditionEvaluator(new ObjectMapper());
    private final WorkflowContextSchema schema = new WorkflowContextSchema(Map.of(
            "applicant.isManager", WorkflowFieldType.BOOLEAN,
            "applicant.parentOrganizationUnitId", WorkflowFieldType.UUID,
            "application.totalAmount", WorkflowFieldType.NUMBER,
            "application.category", WorkflowFieldType.STRING));

    @Test
    void typedOperatorsAndBooleanCompositionAreEvaluatedWithoutReflection() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("applicant.isManager", true);
        values.put("applicant.parentOrganizationUnitId", null);
        values.put("application.totalAmount", new BigDecimal("100000"));
        values.put("application.category", "TRAINING");
        WorkflowContext context = new WorkflowContext(values);
        assertThat(evaluator.evaluate("""
                {"all":[
                  {"field":"applicant.isManager","operator":"EQ","value":true},
                  {"field":"applicant.parentOrganizationUnitId","operator":"IS_NULL"},
                  {"field":"application.totalAmount","operator":"GTE","value":100000},
                  {"field":"application.category","operator":"IN","value":["TRAINING","OTHER"]}
                ]}
                """, context, schema)).isTrue();
        assertThat(evaluator.evaluate("""
                {"not":{"field":"application.totalAmount","operator":"LT","value":100000}}
                """, context, schema)).isTrue();
    }

    @Test
    void unknownFieldsAndMismatchedTypesAreRejected() {
        assertThatThrownBy(() -> evaluator.validate(
                "{\"field\":\"unknown\",\"operator\":\"EQ\",\"value\":1}", schema))
                .isInstanceOf(WorkflowDefinitionException.class);
        assertThatThrownBy(() -> evaluator.validate(
                "{\"field\":\"applicant.isManager\",\"operator\":\"GT\",\"value\":1}", schema))
                .isInstanceOf(WorkflowDefinitionException.class);
        assertThatThrownBy(() -> evaluator.validate(
                "{\"field\":\"application.totalAmount\",\"operator\":\"EQ\",\"value\":\"100\"}", schema))
                .isInstanceOf(WorkflowDefinitionException.class);
    }

    @Test
    void orderingOperatorsReturnFalseForNullActualValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("application.totalAmount", null);
        WorkflowContext context = new WorkflowContext(values);

        for (String operator : java.util.List.of("GT", "GTE", "LT", "LTE")) {
            assertThat(evaluator.evaluate("""
                    {"field":"application.totalAmount","operator":"%s","value":100000}
                    """.formatted(operator), context, schema)).isFalse();
        }
        assertThat(evaluator.evaluate("""
                {"field":"application.totalAmount","operator":"IS_NULL"}
                """, context, schema)).isTrue();
        assertThat(evaluator.evaluate("""
                {"field":"application.totalAmount","operator":"IS_NOT_NULL"}
                """, context, schema)).isFalse();
    }

    @Test
    void orderingOperatorsRejectNullDefinitionValues() {
        for (String operator : java.util.List.of("GT", "GTE", "LT", "LTE")) {
            assertThatThrownBy(() -> evaluator.validate("""
                    {"field":"application.totalAmount","operator":"%s","value":null}
                    """.formatted(operator), schema))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("non-null");
        }
    }
}
