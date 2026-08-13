package jp.co.sdcj.workflow.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryAdjustment;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryTaxMode;

final class ExpenseAutoEntryInvoiceTotalReconciler {

    private static final BigDecimal JPY_TOLERANCE = BigDecimal.ONE;

    private ExpenseAutoEntryInvoiceTotalReconciler() {
    }

    static Status reconcile(
            BigDecimal invoiceTotal,
            BigDecimal draftLineTotal,
            BigDecimal taxAmount,
            List<AutoEntryAdjustment> adjustments,
            AutoEntryTaxMode taxMode) {
        if (invoiceTotal == null || draftLineTotal == null) {
            return Status.UNAVAILABLE;
        }

        BigDecimal adjustmentTotal = adjustmentTotal(adjustments);
        List<BigDecimal> withoutTax = new ArrayList<>();
        List<BigDecimal> withTax = new ArrayList<>();
        withoutTax.add(draftLineTotal);
        if (adjustmentTotal != null) {
            withoutTax.add(draftLineTotal.add(adjustmentTotal));
        }
        if (taxAmount != null) {
            withTax.add(draftLineTotal.add(taxAmount));
            if (adjustmentTotal != null) {
                withTax.add(draftLineTotal.add(taxAmount).add(adjustmentTotal));
            }
        }

        List<BigDecimal> candidates = new ArrayList<>();
        if (taxMode == AutoEntryTaxMode.TAX_EXCLUDED) {
            candidates.addAll(withTax);
            candidates.addAll(withoutTax);
        } else {
            candidates.addAll(withoutTax);
            candidates.addAll(withTax);
        }
        if (candidates.stream().anyMatch(candidate -> withinTolerance(invoiceTotal, candidate))) {
            return Status.MATCHED;
        }
        if (taxAmount == null || adjustmentTotal == null) {
            return Status.UNAVAILABLE;
        }
        return Status.MISMATCH;
    }

    private static BigDecimal adjustmentTotal(List<AutoEntryAdjustment> adjustments) {
        if (adjustments == null
                || adjustments.stream().anyMatch(adjustment ->
                        adjustment.normalizedSignedAmount() == null
                                || adjustment.normalizedSignedAmount().value() == null)) {
            return null;
        }
        return adjustments.stream()
                .map(adjustment -> adjustment.normalizedSignedAmount().value())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static boolean withinTolerance(BigDecimal invoiceTotal, BigDecimal candidate) {
        return invoiceTotal.subtract(candidate).abs().compareTo(JPY_TOLERANCE) <= 0;
    }

    enum Status {
        MATCHED,
        MISMATCH,
        UNAVAILABLE
    }
}
