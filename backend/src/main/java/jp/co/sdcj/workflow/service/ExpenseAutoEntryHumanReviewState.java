package jp.co.sdcj.workflow.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ExpenseAutoEntryHumanReviewState(
        int schemaVersion,
        Document document,
        List<Item> items,
        Map<String, FieldState> fields) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public record Document(
            String issuerName,
            String issuerTaxRegistrationNumber,
            BigDecimal invoiceTotalAmount) {
    }

    public record Item(
            Integer sourceLineItemIndex,
            String itemDescription,
            BigDecimal lineAmount) {
    }

    public record FieldState(HumanResolution resolution) {
    }

    public enum HumanResolution {
        NOT_REQUIRED,
        UNRESOLVED,
        CONFIRMED,
        EDITED
    }
}
