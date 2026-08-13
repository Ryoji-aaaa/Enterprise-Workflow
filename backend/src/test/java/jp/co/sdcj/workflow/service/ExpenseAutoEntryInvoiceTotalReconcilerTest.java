package jp.co.sdcj.workflow.service;

import static jp.co.sdcj.workflow.service.ExpenseAutoEntryInvoiceTotalReconciler.Status.MATCHED;
import static jp.co.sdcj.workflow.service.ExpenseAutoEntryInvoiceTotalReconciler.Status.MISMATCH;
import static jp.co.sdcj.workflow.service.ExpenseAutoEntryInvoiceTotalReconciler.Status.UNAVAILABLE;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFieldStatus.MISSING;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFieldStatus.OK;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryTaxMode.TAX_EXCLUDED;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryTaxMode.TAX_INCLUDED;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryTaxMode.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryAdjustment;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryDerivedField;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryField;

class ExpenseAutoEntryInvoiceTotalReconcilerTest {

    @Test
    void matchesEverySafeCandidateWithoutUsingTaxModeAsAHardSwitch() {
        assertThat(reconcile("110", "100", "10", List.of(), TAX_EXCLUDED))
                .isEqualTo(MATCHED);
        assertThat(reconcile("100", "100", "10", List.of(), TAX_EXCLUDED))
                .isEqualTo(MATCHED);
        assertThat(reconcile("105", "100", "10", List.of(adjustment("-5")), TAX_INCLUDED))
                .isEqualTo(MATCHED);
        assertThat(reconcile("105", "100", "10", List.of(adjustment("-5")), TAX_EXCLUDED))
                .isEqualTo(MATCHED);
        assertThat(reconcile("110", "100", "10", List.of(), UNKNOWN))
                .isEqualTo(MATCHED);
    }

    @Test
    void missingTaxOrAdjustmentIsNotFabricatedAsZero() {
        assertThat(reconcile("102", "100", null, List.of(), UNKNOWN))
                .isEqualTo(UNAVAILABLE);
        assertThat(reconcile("105", "100", "10", null, TAX_EXCLUDED))
                .isEqualTo(UNAVAILABLE);
        assertThat(reconcile(
                "105", "100", "10", List.of(adjustment(null)), TAX_EXCLUDED))
                .isEqualTo(UNAVAILABLE);
        assertThat(reconcile("100", "100", null, null, UNKNOWN))
                .isEqualTo(MATCHED);
    }

    @Test
    void usesAnInclusiveOneYenToleranceOnlyForInvoiceReconciliation() {
        assertThat(reconcile("101", "100", "0", List.of(), UNKNOWN)).isEqualTo(MATCHED);
        assertThat(reconcile("99", "100", "0", List.of(), UNKNOWN)).isEqualTo(MATCHED);
        assertThat(reconcile("102", "100", "0", List.of(), UNKNOWN)).isEqualTo(MISMATCH);
        assertThat(reconcile("98", "100", "0", List.of(), UNKNOWN)).isEqualTo(MISMATCH);
    }

    @Test
    void completeInputsThatDoNotMatchAnyCandidateAreAMismatch() {
        assertThat(reconcile("120", "100", "10", List.of(), TAX_EXCLUDED))
                .isEqualTo(MISMATCH);
        assertThat(ExpenseAutoEntryInvoiceTotalReconciler.reconcile(
                null, decimal("100"), decimal("10"), List.of(), TAX_EXCLUDED))
                .isEqualTo(UNAVAILABLE);
    }

    private static ExpenseAutoEntryInvoiceTotalReconciler.Status reconcile(
            String invoiceTotal,
            String draftLineTotal,
            String taxAmount,
            List<AutoEntryAdjustment> adjustments,
            jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryTaxMode taxMode) {
        return ExpenseAutoEntryInvoiceTotalReconciler.reconcile(
                decimal(invoiceTotal), decimal(draftLineTotal), decimal(taxAmount), adjustments, taxMode);
    }

    private static AutoEntryAdjustment adjustment(String normalizedAmount) {
        BigDecimal value = decimal(normalizedAmount);
        return new AutoEntryAdjustment(
                null,
                new AutoEntryField<>("ADJUSTMENT", null, OK, List.of(), List.of()),
                new AutoEntryField<>("DEDUCTION", null, OK, List.of(), List.of()),
                new AutoEntryField<>("調整", null, OK, List.of(), List.of()),
                new AutoEntryField<>(value == null ? null : value.abs(), null,
                        value == null ? MISSING : OK, List.of(), List.of()),
                new AutoEntryDerivedField<>(
                        value, value == null ? MISSING : OK, List.of()));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
