package jp.co.sdcj.workflow.service;

import static jp.co.sdcj.workflow.service.ExpenseAutoEntryHumanReviewState.HumanResolution.CONFIRMED;
import static jp.co.sdcj.workflow.service.ExpenseAutoEntryHumanReviewState.HumanResolution.EDITED;
import static jp.co.sdcj.workflow.service.ExpenseAutoEntryHumanReviewState.HumanResolution.NOT_REQUIRED;
import static jp.co.sdcj.workflow.service.ExpenseAutoEntryHumanReviewState.HumanResolution.UNRESOLVED;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFieldStatus.MISSING;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFieldStatus.OK;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.api.ExpenseAutoEntryDraftContentRequest;
import jp.co.sdcj.workflow.service.ExpenseAutoEntryHumanReviewState.FieldState;
import jp.co.sdcj.workflow.service.ExpenseAutoEntryHumanReviewState.HumanResolution;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryField;

@Component
@ConditionalOnProperty(prefix = "workflow.document-analysis", name = "enabled", havingValue = "true")
public class ExpenseAutoEntryHumanReviewResolver {

    public static final String ISSUER_NAME_PATH = "document.issuerName";
    public static final String ISSUER_TAX_REGISTRATION_NUMBER_PATH =
            "document.issuerTaxRegistrationNumber";
    public static final String TOTAL_AMOUNT_PATH = "document.totalAmount";

    public ExpenseAutoEntryHumanReviewState resolve(
            AutoEntryReviewResponse review,
            ExpenseAutoEntryDraftContentRequest application,
            ExpenseAutoEntryDraftContentRequest.Document document,
            List<String> confirmedFieldPaths) {
        Objects.requireNonNull(review, "review");
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(document, "document");
        List<AutoEntryReviewResponse.AutoEntryLineItem> originalItems =
                review.document().lineItems().value() == null
                        ? List.of() : review.document().lineItems().value();
        Set<String> supportedPaths = supportedPaths(originalItems.size());
        Set<String> confirmed = confirmedPaths(confirmedFieldPaths, supportedPaths);
        Map<Integer, ExpenseAutoEntryDraftContentRequest.Item> currentBySource =
                validateAndIndexItems(application.items(), originalItems.size());

        Map<String, FieldState> fields = new LinkedHashMap<>();
        put(fields, ISSUER_NAME_PATH, review.document().issuerName(),
                normalize(document.issuerName()), confirmed);
        put(fields, ISSUER_TAX_REGISTRATION_NUMBER_PATH,
                review.document().issuerTaxRegistrationNumber(),
                normalize(document.issuerTaxRegistrationNumber()), confirmed);
        put(fields, TOTAL_AMOUNT_PATH, review.document().totalAmount(),
                document.invoiceTotalAmount(), confirmed);
        for (int index = 0; index < originalItems.size(); index++) {
            AutoEntryReviewResponse.AutoEntryLineItem original = originalItems.get(index);
            ExpenseAutoEntryDraftContentRequest.Item current = currentBySource.get(index);
            put(fields, descriptionPath(index), original.itemDescription(),
                    current == null ? null : normalize(current.description()), confirmed);
            put(fields, amountPath(index), original.lineAmount(),
                    current == null ? null : current.amount(), confirmed);
        }

        ExpenseAutoEntryHumanReviewState.Document normalizedDocument =
                new ExpenseAutoEntryHumanReviewState.Document(
                        normalize(document.issuerName()),
                        normalize(document.issuerTaxRegistrationNumber()),
                        document.invoiceTotalAmount());
        List<ExpenseAutoEntryHumanReviewState.Item> stateItems = application.items().stream()
                .map(item -> new ExpenseAutoEntryHumanReviewState.Item(
                        item.sourceLineItemIndex(), normalize(item.description()), item.amount()))
                .toList();
        return new ExpenseAutoEntryHumanReviewState(
                ExpenseAutoEntryHumanReviewState.CURRENT_SCHEMA_VERSION,
                normalizedDocument,
                stateItems,
                Collections.unmodifiableMap(fields));
    }

    public void requireSupportedCurrency(AutoEntryReviewResponse review) {
        String currency = normalize(review.document().currencyCode().value());
        if (currency != null && !"JPY".equalsIgnoreCase(currency)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "EXPENSE_AUTO_ENTRY_CURRENCY_UNSUPPORTED",
                    "JPY以外の文書は経費下書きへ変換できません。");
        }
    }

    private static Map<Integer, ExpenseAutoEntryDraftContentRequest.Item> validateAndIndexItems(
            List<ExpenseAutoEntryDraftContentRequest.Item> items,
            int originalItemCount) {
        Map<Integer, ExpenseAutoEntryDraftContentRequest.Item> bySource = new HashMap<>();
        for (ExpenseAutoEntryDraftContentRequest.Item item : items) {
            Integer sourceIndex = item.sourceLineItemIndex();
            if (sourceIndex == null) {
                continue;
            }
            if (sourceIndex < 0 || sourceIndex >= originalItemCount
                    || bySource.putIfAbsent(sourceIndex, item) != null) {
                throw invalidMapping();
            }
        }
        return bySource;
    }

    private static Set<String> confirmedPaths(
            List<String> confirmedFieldPaths,
            Set<String> supportedPaths) {
        if (confirmedFieldPaths == null) {
            throw invalidMapping();
        }
        Set<String> confirmed = new HashSet<>();
        for (String path : confirmedFieldPaths) {
            if (path == null || !supportedPaths.contains(path) || !confirmed.add(path)) {
                throw invalidMapping();
            }
        }
        return confirmed;
    }

    private static Set<String> supportedPaths(int lineItemCount) {
        Set<String> supported = new HashSet<>(Set.of(
                ISSUER_NAME_PATH,
                ISSUER_TAX_REGISTRATION_NUMBER_PATH,
                TOTAL_AMOUNT_PATH));
        for (int index = 0; index < lineItemCount; index++) {
            supported.add(descriptionPath(index));
            supported.add(amountPath(index));
        }
        return supported;
    }

    private static <T> void put(
            Map<String, FieldState> fields,
            String path,
            AutoEntryField<T> original,
            T current,
            Set<String> confirmed) {
        fields.put(path, new FieldState(resolution(
                original, current, confirmed.contains(path))));
    }

    static <T> HumanResolution resolution(
            AutoEntryField<T> original,
            T current,
            boolean confirmed) {
        if (!equalValue(original.value(), current)) {
            return EDITED;
        }
        if (original.status() == MISSING) {
            return UNRESOLVED;
        }
        if (original.status() == OK) {
            return NOT_REQUIRED;
        }
        return confirmed ? CONFIRMED : UNRESOLVED;
    }

    private static boolean equalValue(Object original, Object current) {
        if (original instanceof BigDecimal left && current instanceof BigDecimal right) {
            return left.compareTo(right) == 0;
        }
        if (original instanceof String left && (current == null || current instanceof String)) {
            return Objects.equals(normalize(left), normalize((String) current));
        }
        return Objects.equals(original, current);
    }

    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    public static String descriptionPath(int index) {
        return "document.lineItems[%d].itemDescription".formatted(index);
    }

    public static String amountPath(int index) {
        return "document.lineItems[%d].lineAmount".formatted(index);
    }

    private static ApiException invalidMapping() {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "EXPENSE_AUTO_ENTRY_SOURCE_MAPPING_INVALID",
                "自動入力結果と経費明細の対応が不正です。");
    }
}
