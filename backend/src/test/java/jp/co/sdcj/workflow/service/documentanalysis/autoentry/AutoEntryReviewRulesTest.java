package jp.co.sdcj.workflow.service.documentanalysis.autoentry;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.co.sdcj.workflow.config.AutoEntryReviewProperties;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryAdjustment;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryField;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFieldStatus;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFindingCode;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryTaxBreakdown;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryTaxMode;

class AutoEntryReviewRulesTest {

    private AutoEntryReviewRules rules;

    @BeforeEach
    void setUp() {
        rules = new AutoEntryReviewRules(
                new AutoEntryReviewProperties(new BigDecimal("0.60")));
    }

    @Test
    void valueAndConfidenceDetermineOkReviewAndMissingWithoutDefaulting() {
        assertThat(field(BigDecimal.ZERO).status()).isEqualTo(AutoEntryFieldStatus.OK);
        assertThat(rules.field("", null, List.of()).status()).isEqualTo(AutoEntryFieldStatus.OK);
        assertThat(rules.field(List.of(), null, List.of()).status()).isEqualTo(AutoEntryFieldStatus.OK);

        AutoEntryField<String> low = rules.field("raw", new BigDecimal("0.59"), List.of());
        assertThat(low.status()).isEqualTo(AutoEntryFieldStatus.REVIEW);
        assertThat(low.findings()).containsExactly(AutoEntryFindingCode.LOW_CONFIDENCE);

        AutoEntryField<String> missing = rules.field(null, new BigDecimal("0.10"), List.of());
        assertThat(missing.status()).isEqualTo(AutoEntryFieldStatus.MISSING);
        assertThat(missing.value()).isNull();
        assertThat(missing.findings()).isEmpty();
    }

    @Test
    void unknownEnumPreservesRawValueAndRequiresReview() {
        AutoEntryField<String> documentType = rules.documentType("CREDIT_NOTE", decimal("0.90"), List.of());

        assertThat(documentType.value()).isEqualTo("CREDIT_NOTE");
        assertThat(documentType.status()).isEqualTo(AutoEntryFieldStatus.REVIEW);
        assertThat(documentType.findings()).containsExactly(AutoEntryFindingCode.ENUM_VALUE_UNKNOWN);
    }

    @Test
    void lineAmountUsesBigDecimalAndExclusiveCentTolerance() {
        AutoEntryField<BigDecimal> consistent = rules.lineAmount(
                field(decimal("0.1")), field(decimal("0.2")), field(decimal("0.02")));
        AutoEntryField<BigDecimal> withinTolerance = rules.lineAmount(
                field(decimal("3")), field(decimal("2")), field(decimal("6.009")));
        AutoEntryField<BigDecimal> inconsistent = rules.lineAmount(
                field(decimal("3")), field(decimal("2")), field(decimal("6.01")));

        assertThat(consistent.findings()).isEmpty();
        assertThat(withinTolerance.findings()).isEmpty();
        assertThat(inconsistent.value()).isEqualByComparingTo("6.01");
        assertThat(inconsistent.findings())
                .containsExactly(AutoEntryFindingCode.LINE_AMOUNT_INCONSISTENT);
    }

    @Test
    void taxBreakdownAllowsSubUnitRoundingButFlagsClearMismatch() {
        AutoEntryTaxBreakdown rounded = rules.taxBreakdown(
                rules.objectReview(null, List.of()),
                field(decimal("8")),
                field(decimal("100")),
                field(decimal("8.99")),
                stringField("8%対象"),
                rules.taxCategory("REDUCED", null, List.of()));
        AutoEntryTaxBreakdown mismatch = rules.taxBreakdown(
                rules.objectReview(null, List.of()),
                field(decimal("10")),
                field(decimal("680000")),
                field(decimal("88000")),
                stringField("10%対象"),
                rules.taxCategory("STANDARD", null, List.of()));

        assertThat(rounded.taxAmount().findings()).isEmpty();
        assertThat(mismatch.taxAmount().value()).isEqualByComparingTo("88000");
        assertThat(mismatch.taxAmount().findings())
                .containsExactly(AutoEntryFindingCode.TAX_BREAKDOWN_INCONSISTENT);
    }

    @Test
    void taxTotalRequiresAllBreakdownAmountsAndPreservesExtractedTotal() {
        AutoEntryTaxBreakdown first = breakdown("500");
        AutoEntryTaxBreakdown second = breakdown("300");
        AutoEntryField<List<AutoEntryTaxBreakdown>> complete = rules.field(
                List.of(first, second), null, List.of());
        AutoEntryField<BigDecimal> mismatch = rules.taxTotal(field(decimal("900")), complete);

        assertThat(mismatch.value()).isEqualByComparingTo("900");
        assertThat(mismatch.findings()).contains(AutoEntryFindingCode.TAX_TOTAL_INCONSISTENT);

        AutoEntryTaxBreakdown missing = breakdown(null);
        assertThat(rules.taxTotal(
                field(decimal("900")), rules.field(List.of(first, missing), null, List.of())).findings())
                        .isEmpty();
    }

    @Test
    void adjustmentsNormalizeSignFromDirectionWithoutChangingRawAmount() {
        AutoEntryAdjustment deductionPositive = adjustment("DEDUCTION", "500");
        AutoEntryAdjustment deductionNegative = adjustment("DEDUCTION", "-500");
        AutoEntryAdjustment additionNegative = adjustment("ADDITION", "-500");
        AutoEntryAdjustment unknown = adjustment("UNKNOWN", "-500");
        AutoEntryAdjustment future = adjustment("FUTURE_DIRECTION", "500");

        assertThat(deductionPositive.rawAmount().value()).isEqualByComparingTo("500");
        assertThat(deductionPositive.normalizedSignedAmount().value()).isEqualByComparingTo("-500");
        assertThat(deductionNegative.rawAmount().value()).isEqualByComparingTo("-500");
        assertThat(deductionNegative.normalizedSignedAmount().value()).isEqualByComparingTo("-500");
        assertThat(additionNegative.normalizedSignedAmount().value()).isEqualByComparingTo("500");
        assertThat(unknown.normalizedSignedAmount().value()).isEqualByComparingTo("-500");
        assertThat(unknown.direction().findings())
                .contains(AutoEntryFindingCode.ADJUSTMENT_DIRECTION_UNKNOWN);
        assertThat(future.direction().value()).isEqualTo("FUTURE_DIRECTION");
        assertThat(future.direction().findings()).containsExactly(
                AutoEntryFindingCode.ENUM_VALUE_UNKNOWN,
                AutoEntryFindingCode.ADJUSTMENT_DIRECTION_UNKNOWN);
    }

    @Test
    void totalAcceptsEveryDocumentedCandidateAndRejectsNoMatch() {
        AutoEntryField<BigDecimal> subtotal = field(decimal("100"));
        AutoEntryField<BigDecimal> tax = field(decimal("10"));
        AutoEntryField<List<AutoEntryAdjustment>> adjustments = rules.field(
                List.of(adjustment("DEDUCTION", "5")), null, List.of());

        for (String accepted : List.of("110", "105", "100", "95")) {
            assertThat(rules.total(subtotal, tax, adjustments, field(decimal(accepted))).findings())
                    .as("candidate %s", accepted)
                    .isEmpty();
        }
        assertThat(rules.total(subtotal, tax, adjustments, field(decimal("106"))).findings())
                .containsExactly(AutoEntryFindingCode.TOTAL_INCONSISTENT);

        AutoEntryField<List<AutoEntryAdjustment>> missingAdjustments = rules.field(
                null, null, List.of());
        assertThat(rules.total(subtotal, tax, missingAdjustments, field(decimal("110"))).findings())
                .isEmpty();
    }

    @Test
    void taxModePrefersNotationThenUsesArithmeticAndReportsAmbiguity() {
        AutoEntryField<BigDecimal> subtotal = field(decimal("100"));
        AutoEntryField<BigDecimal> tax = field(decimal("10"));
        AutoEntryField<List<AutoEntryAdjustment>> noAdjustments = rules.field(
                List.of(), null, List.of());

        assertThat(rules.taxMode(
                stringField("税込み"), subtotal, tax, noAdjustments, field(decimal("110"))).value())
                        .isEqualTo(AutoEntryTaxMode.TAX_INCLUDED);
        assertThat(rules.taxMode(
                stringField("外税"), subtotal, tax, noAdjustments, field(decimal("100"))).value())
                        .isEqualTo(AutoEntryTaxMode.TAX_EXCLUDED);
        assertThat(rules.taxMode(
                stringField(null), subtotal, tax, noAdjustments, field(decimal("110"))).value())
                        .isEqualTo(AutoEntryTaxMode.TAX_EXCLUDED);
        assertThat(rules.taxMode(
                stringField(null), subtotal, tax, noAdjustments, field(decimal("100"))).value())
                        .isEqualTo(AutoEntryTaxMode.TAX_INCLUDED);

        var ambiguous = rules.taxMode(
                stringField(null),
                subtotal,
                field(BigDecimal.ZERO),
                noAdjustments,
                field(decimal("100")));
        assertThat(ambiguous.value()).isEqualTo(AutoEntryTaxMode.UNKNOWN);
        assertThat(ambiguous.status()).isEqualTo(AutoEntryFieldStatus.REVIEW);
        assertThat(ambiguous.findings()).containsExactly(AutoEntryFindingCode.TAX_MODE_AMBIGUOUS);
    }

    @Test
    void paymentDueBeforeIssueDateIsReviewWithoutCorrection() {
        AutoEntryField<LocalDate> due = rules.paymentDueDate(
                rules.field(LocalDate.parse("2026-08-10"), null, List.of()),
                rules.field(LocalDate.parse("2026-08-09"), null, List.of()));

        assertThat(due.value()).isEqualTo(LocalDate.parse("2026-08-09"));
        assertThat(due.findings())
                .containsExactly(AutoEntryFindingCode.PAYMENT_DUE_BEFORE_ISSUE_DATE);
    }

    private AutoEntryAdjustment adjustment(String direction, String rawAmount) {
        return rules.adjustment(
                rules.objectReview(null, List.of()),
                rules.adjustmentType("DISCOUNT", null, List.of()),
                rules.adjustmentDirection(direction, null, List.of()),
                stringField("adjustment"),
                field(decimal(rawAmount)));
    }

    private AutoEntryTaxBreakdown breakdown(String taxAmount) {
        return new AutoEntryTaxBreakdown(
                rules.objectReview(null, List.of()),
                field(decimal("10")),
                field(decimal("5000")),
                field(decimal(taxAmount)),
                stringField("10%対象"),
                rules.taxCategory("STANDARD", null, List.of()));
    }

    private <T> AutoEntryField<T> field(T value) {
        return rules.field(value, null, List.of());
    }

    private AutoEntryField<String> stringField(String value) {
        return rules.field(value, null, List.of());
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
