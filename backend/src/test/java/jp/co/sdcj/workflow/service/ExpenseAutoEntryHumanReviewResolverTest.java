package jp.co.sdcj.workflow.service;

import static jp.co.sdcj.workflow.service.ExpenseAutoEntryHumanReviewState.HumanResolution.CONFIRMED;
import static jp.co.sdcj.workflow.service.ExpenseAutoEntryHumanReviewState.HumanResolution.EDITED;
import static jp.co.sdcj.workflow.service.ExpenseAutoEntryHumanReviewState.HumanResolution.NOT_REQUIRED;
import static jp.co.sdcj.workflow.service.ExpenseAutoEntryHumanReviewState.HumanResolution.UNRESOLVED;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFieldStatus.MISSING;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFieldStatus.OK;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFieldStatus.REVIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.api.ExpenseAutoEntryDraftContentRequest;
import jp.co.sdcj.workflow.domain.ExpenseCategory;
import jp.co.sdcj.workflow.service.ExpenseAutoEntryDraftDetails.Warning;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryDerivedField;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryField;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryTaxMode;

class ExpenseAutoEntryHumanReviewResolverTest {

    private final ExpenseAutoEntryHumanReviewResolver resolver =
            new ExpenseAutoEntryHumanReviewResolver();

    @Test
    void reviewFieldResolutionIsDerivedByBackend() {
        assertThat(resolve("issuer", REVIEW, "issuer", false)).isEqualTo(UNRESOLVED);
        assertThat(resolve("issuer", REVIEW, "issuer", true)).isEqualTo(CONFIRMED);
        assertThat(resolve("issuer", REVIEW, "edited", false)).isEqualTo(EDITED);
        assertThat(resolve(null, MISSING, null, false)).isEqualTo(UNRESOLVED);
        assertThat(resolve(null, MISSING, "entered", false)).isEqualTo(EDITED);
        assertThat(resolve("issuer", OK, "issuer", true)).isEqualTo(NOT_REQUIRED);
        assertThat(resolve("issuer", OK, "edited", false)).isEqualTo(EDITED);
    }

    @Test
    void numericEqualityIgnoresScaleAndStringsUseTrimmedBlankToNullPolicy() {
        assertThat(ExpenseAutoEntryHumanReviewResolver.resolution(
                field(new BigDecimal("100.00"), REVIEW),
                new BigDecimal("100"),
                false)).isEqualTo(UNRESOLVED);
        assertThat(resolve("  株式会社ABC  ", REVIEW, "株式会社ABC", true))
                .isEqualTo(CONFIRMED);
        assertThat(ExpenseAutoEntryHumanReviewResolver.normalize("   ")).isNull();
    }

    @Test
    void unsupportedConfirmedPathIsRejected() {
        assertThatThrownBy(() -> resolver.resolve(
                review(List.of()),
                application(List.of(manualItem())),
                document(),
                List.of("document.providerConfidence")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus().value()).isEqualTo(400);
                    assertThat(exception.getCode())
                            .isEqualTo("EXPENSE_AUTO_ENTRY_SOURCE_MAPPING_INVALID");
                });
    }

    @Test
    void sourceIndexMustExistAndCannotBeDuplicated() {
        AutoEntryReviewResponse review = review(List.of(line("AI item", "100")));
        assertInvalidMapping(List.of(item(-1, "bad", "100")), review);
        assertInvalidMapping(List.of(item(1, "bad", "100")), review);
        assertInvalidMapping(List.of(
                item(0, "first", "100"), item(0, "duplicate", "100")), review);
    }

    @Test
    void manualAndRemovedItemsPreserveOriginalProvenance() {
        AutoEntryReviewResponse review = review(List.of(line("AI item", "100")));
        ExpenseAutoEntryHumanReviewState state = resolver.resolve(
                review,
                application(List.of(manualItem())),
                document(),
                List.of());

        assertThat(state.items()).singleElement()
                .satisfies(item -> assertThat(item.sourceLineItemIndex()).isNull());
        assertThat(state.fields().get(
                ExpenseAutoEntryHumanReviewResolver.descriptionPath(0)).resolution())
                .isEqualTo(EDITED);
        assertThat(state.fields().get(
                ExpenseAutoEntryHumanReviewResolver.amountPath(0)).resolution())
                .isEqualTo(EDITED);
    }

    @Test
    void removedSourceItemIsEditedEvenWhenOriginalFieldIsMissing() {
        AutoEntryReviewResponse.AutoEntryLineItem removed =
                mock(AutoEntryReviewResponse.AutoEntryLineItem.class);
        when(removed.itemDescription()).thenReturn(field(null, MISSING));
        when(removed.lineAmount()).thenReturn(field(new BigDecimal("100"), OK));

        ExpenseAutoEntryHumanReviewState state = resolver.resolve(
                review(List.of(removed)),
                application(List.of(manualItem())),
                document(),
                List.of());

        assertThat(state.fields().get(
                ExpenseAutoEntryHumanReviewResolver.descriptionPath(0)).resolution())
                .isEqualTo(EDITED);
        assertThat(state.fields().get(
                ExpenseAutoEntryHumanReviewResolver.amountPath(0)).resolution())
                .isEqualTo(EDITED);
    }

    @Test
    void invoiceReconciliationUsesCurrentHumanInvoiceAndLatestDraftLineTotal() {
        ExpenseAutoEntryHumanReviewState state = new ExpenseAutoEntryHumanReviewState(
                1,
                new ExpenseAutoEntryHumanReviewState.Document(
                        null, null, new BigDecimal("30800")),
                List.of(),
                java.util.Map.of());
        AutoEntryReviewResponse review = review(List.of());

        assertThat(ExpenseAutoEntryDraftService.warnings(
                state, new BigDecimal("28000"), review))
                .isEmpty();
        assertThat(ExpenseAutoEntryDraftService.warnings(
                state, new BigDecimal("28002"), review))
                .containsExactly(Warning.INVOICE_TOTAL_DIFFERS_FROM_DRAFT_TOTAL);

        ExpenseAutoEntryHumanReviewState editedInvoice = new ExpenseAutoEntryHumanReviewState(
                1,
                new ExpenseAutoEntryHumanReviewState.Document(
                        null, null, new BigDecimal("28002")),
                List.of(),
                java.util.Map.of());
        assertThat(ExpenseAutoEntryDraftService.warnings(
                editedInvoice, new BigDecimal("28000"), review))
                .containsExactly(Warning.INVOICE_TOTAL_DIFFERS_FROM_DRAFT_TOTAL);
    }

    private void assertInvalidMapping(
            List<ExpenseAutoEntryDraftContentRequest.Item> items,
            AutoEntryReviewResponse review) {
        assertThatThrownBy(() -> resolver.resolve(
                review, application(items), document(), List.of()))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo("EXPENSE_AUTO_ENTRY_SOURCE_MAPPING_INVALID"));
    }

    private static ExpenseAutoEntryHumanReviewState.HumanResolution resolve(
            String original,
            AutoEntryReviewResponse.AutoEntryFieldStatus status,
            String current,
            boolean confirmed) {
        return ExpenseAutoEntryHumanReviewResolver.resolution(
                field(original, status), current, confirmed);
    }

    private static <T> AutoEntryField<T> field(
            T value,
            AutoEntryReviewResponse.AutoEntryFieldStatus status) {
        return new AutoEntryField<>(value, null, status, List.of(), List.of());
    }

    private static AutoEntryReviewResponse review(
            List<AutoEntryReviewResponse.AutoEntryLineItem> lineItems) {
        AutoEntryReviewResponse review = mock(AutoEntryReviewResponse.class);
        AutoEntryReviewResponse.AutoEntryReviewDocument document =
                mock(AutoEntryReviewResponse.AutoEntryReviewDocument.class);
        when(review.document()).thenReturn(document);
        when(document.issuerName()).thenReturn(field("株式会社ABC", REVIEW));
        when(document.issuerTaxRegistrationNumber()).thenReturn(field(null, MISSING));
        when(document.totalAmount()).thenReturn(field(new BigDecimal("100"), OK));
        when(document.taxAmount()).thenReturn(field(new BigDecimal("2800"), OK));
        when(document.adjustments()).thenReturn(field(List.of(), OK));
        when(document.currencyCode()).thenReturn(field("JPY", OK));
        when(document.lineItems()).thenReturn(field(lineItems, OK));
        when(review.taxMode()).thenReturn(new AutoEntryDerivedField<>(
                AutoEntryTaxMode.TAX_EXCLUDED, OK, List.of()));
        return review;
    }

    private static AutoEntryReviewResponse.AutoEntryLineItem line(
            String description,
            String amount) {
        AutoEntryReviewResponse.AutoEntryLineItem item =
                mock(AutoEntryReviewResponse.AutoEntryLineItem.class);
        when(item.itemDescription()).thenReturn(field(description, OK));
        when(item.lineAmount()).thenReturn(field(new BigDecimal(amount), OK));
        return item;
    }

    private static ExpenseAutoEntryDraftContentRequest application(
            List<ExpenseAutoEntryDraftContentRequest.Item> items) {
        return new ExpenseAutoEntryDraftContentRequest(
                ExpenseCategory.OTHER,
                "請求書精算",
                "テスト",
                LocalDate.of(2026, 8, 13),
                null,
                items);
    }

    private static ExpenseAutoEntryDraftContentRequest.Document document() {
        return new ExpenseAutoEntryDraftContentRequest.Document(
                "株式会社ABC", null, new BigDecimal("100"));
    }

    private static ExpenseAutoEntryDraftContentRequest.Item manualItem() {
        return item(null, "Manual item", "100");
    }

    private static ExpenseAutoEntryDraftContentRequest.Item item(
            Integer sourceIndex,
            String description,
            String amount) {
        return new ExpenseAutoEntryDraftContentRequest.Item(
                sourceIndex,
                LocalDate.of(2026, 8, 13),
                description,
                new BigDecimal(amount),
                "株式会社ABC",
                null,
                null,
                null,
                null);
    }
}
