package jp.co.sdcj.workflow.service.documentanalysis.autoentry;

import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFieldStatus.MISSING;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFieldStatus.OK;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFieldStatus.REVIEW;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFindingCode.ADJUSTMENT_DIRECTION_UNKNOWN;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFindingCode.ENUM_VALUE_UNKNOWN;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFindingCode.LINE_AMOUNT_INCONSISTENT;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFindingCode.LOW_CONFIDENCE;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFindingCode.PAYMENT_DUE_BEFORE_ISSUE_DATE;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFindingCode.TAX_BREAKDOWN_INCONSISTENT;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFindingCode.TAX_MODE_AMBIGUOUS;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFindingCode.TAX_TOTAL_INCONSISTENT;
import static jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFindingCode.TOTAL_INCONSISTENT;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jp.co.sdcj.workflow.config.AutoEntryReviewProperties;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryAdjustment;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryDerivedField;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryField;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFieldStatus;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFindingCode;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryLineItem;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryObjectReview;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryReviewDocument;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryReviewSummary;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntrySourceRef;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryTaxBreakdown;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryTaxMode;

@Component
@ConditionalOnProperty(prefix = "workflow.document-analysis", name = "enabled", havingValue = "true")
public class AutoEntryReviewRules {

    static final BigDecimal LINE_AMOUNT_TOLERANCE = new BigDecimal("0.01");
    static final BigDecimal MONETARY_ROUNDING_TOLERANCE = BigDecimal.ONE;

    private static final Set<String> DOCUMENT_TYPES = Set.of(
            "INVOICE", "PURCHASE_ORDER", "ORDER_CONFIRMATION", "OTHER");
    private static final Set<String> TAX_CATEGORIES = Set.of(
            "STANDARD", "REDUCED", "NON_TAXABLE", "EXEMPT");
    private static final Set<String> ADJUSTMENT_TYPES = Set.of(
            "WITHHOLDING_TAX", "DISCOUNT", "SHIPPING_FEE", "SERVICE_FEE", "ROUNDING", "OTHER");
    private static final Set<String> ADJUSTMENT_DIRECTIONS = Set.of(
            "DEDUCTION", "ADDITION", "UNKNOWN");

    private final BigDecimal confidenceThreshold;

    public AutoEntryReviewRules(AutoEntryReviewProperties properties) {
        this.confidenceThreshold = properties.reviewConfidenceThreshold();
    }

    public <T> AutoEntryField<T> field(
            T value,
            BigDecimal confidence,
            List<AutoEntrySourceRef> sources) {
        List<AutoEntryFindingCode> findings = value != null
                && confidence != null
                && confidence.compareTo(confidenceThreshold) < 0
                        ? List.of(LOW_CONFIDENCE)
                        : List.of();
        return new AutoEntryField<>(
                value,
                confidence,
                status(value, findings),
                List.copyOf(sources),
                findings);
    }

    public AutoEntryField<String> documentType(
            String value,
            BigDecimal confidence,
            List<AutoEntrySourceRef> sources) {
        return enumField(value, confidence, sources, DOCUMENT_TYPES);
    }

    public AutoEntryField<String> taxCategory(
            String value,
            BigDecimal confidence,
            List<AutoEntrySourceRef> sources) {
        return enumField(value, confidence, sources, TAX_CATEGORIES);
    }

    public AutoEntryField<String> adjustmentType(
            String value,
            BigDecimal confidence,
            List<AutoEntrySourceRef> sources) {
        return enumField(value, confidence, sources, ADJUSTMENT_TYPES);
    }

    public AutoEntryField<String> adjustmentDirection(
            String value,
            BigDecimal confidence,
            List<AutoEntrySourceRef> sources) {
        return enumField(value, confidence, sources, ADJUSTMENT_DIRECTIONS);
    }

    public AutoEntryField<BigDecimal> lineAmount(
            AutoEntryField<BigDecimal> quantity,
            AutoEntryField<BigDecimal> unitPrice,
            AutoEntryField<BigDecimal> lineAmount) {
        if (quantity.value() == null || unitPrice.value() == null || lineAmount.value() == null) {
            return lineAmount;
        }
        BigDecimal expected = quantity.value().multiply(unitPrice.value());
        return within(lineAmount.value(), expected, LINE_AMOUNT_TOLERANCE)
                ? lineAmount
                : withFinding(lineAmount, LINE_AMOUNT_INCONSISTENT);
    }

    public AutoEntryTaxBreakdown taxBreakdown(
            AutoEntryObjectReview review,
            AutoEntryField<BigDecimal> taxRatePercent,
            AutoEntryField<BigDecimal> taxableAmount,
            AutoEntryField<BigDecimal> taxAmount,
            AutoEntryField<String> categoryNotation,
            AutoEntryField<String> category) {
        AutoEntryField<BigDecimal> reviewedTax = taxAmount;
        if (taxRatePercent.value() != null
                && taxableAmount.value() != null
                && taxAmount.value() != null) {
            BigDecimal expected = taxableAmount.value()
                    .multiply(taxRatePercent.value())
                    .movePointLeft(2);
            if (!within(taxAmount.value(), expected, MONETARY_ROUNDING_TOLERANCE)) {
                reviewedTax = withFinding(taxAmount, TAX_BREAKDOWN_INCONSISTENT);
            }
        }
        return new AutoEntryTaxBreakdown(
                review, taxRatePercent, taxableAmount, reviewedTax, categoryNotation, category);
    }

    public AutoEntryAdjustment adjustment(
            AutoEntryObjectReview review,
            AutoEntryField<String> type,
            AutoEntryField<String> direction,
            AutoEntryField<String> description,
            AutoEntryField<BigDecimal> rawAmount) {
        AutoEntryField<String> reviewedDirection = direction;
        BigDecimal normalized = null;
        List<AutoEntryFindingCode> derivedFindings = List.of();
        if (rawAmount.value() != null && direction.value() != null) {
            normalized = switch (direction.value()) {
                case "DEDUCTION" -> rawAmount.value().abs().negate();
                case "ADDITION" -> rawAmount.value().abs();
                default -> rawAmount.value();
            };
            if (!"DEDUCTION".equals(direction.value()) && !"ADDITION".equals(direction.value())) {
                reviewedDirection = withFinding(direction, ADJUSTMENT_DIRECTION_UNKNOWN);
                derivedFindings = List.of(ADJUSTMENT_DIRECTION_UNKNOWN);
            }
        }
        AutoEntryFieldStatus derivedStatus = normalized == null
                ? MISSING
                : derivedFindings.isEmpty() ? OK : REVIEW;
        return new AutoEntryAdjustment(
                review,
                type,
                reviewedDirection,
                description,
                rawAmount,
                new AutoEntryDerivedField<>(normalized, derivedStatus, derivedFindings));
    }

    public AutoEntryObjectReview objectReview(
            BigDecimal confidence,
            List<AutoEntrySourceRef> sources) {
        AutoEntryField<Boolean> field = field(Boolean.TRUE, confidence, sources);
        return new AutoEntryObjectReview(
                field.confidence(), field.status(), field.sources(), field.findings());
    }

    public AutoEntryField<BigDecimal> taxTotal(
            AutoEntryField<BigDecimal> totalTax,
            AutoEntryField<List<AutoEntryTaxBreakdown>> breakdowns) {
        if (totalTax.value() == null
                || breakdowns.value() == null
                || breakdowns.value().isEmpty()
                || breakdowns.value().stream().anyMatch(item -> item.taxAmount().value() == null)) {
            return totalTax;
        }
        BigDecimal sum = breakdowns.value().stream()
                .map(item -> item.taxAmount().value())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return within(totalTax.value(), sum, MONETARY_ROUNDING_TOLERANCE)
                ? totalTax
                : withFinding(totalTax, TAX_TOTAL_INCONSISTENT);
    }

    public AutoEntryField<BigDecimal> total(
            AutoEntryField<BigDecimal> subtotal,
            AutoEntryField<BigDecimal> tax,
            AutoEntryField<List<AutoEntryAdjustment>> adjustments,
            AutoEntryField<BigDecimal> total) {
        if (total.value() == null) {
            return total;
        }
        ReconciliationCandidates candidates = candidates(subtotal, tax, adjustments);
        return candidates.all().isEmpty()
                || candidates.all().stream().anyMatch(candidate ->
                        within(total.value(), candidate, MONETARY_ROUNDING_TOLERANCE))
                                ? total
                                : withFinding(total, TOTAL_INCONSISTENT);
    }

    public AutoEntryField<java.time.LocalDate> paymentDueDate(
            AutoEntryField<java.time.LocalDate> issueDate,
            AutoEntryField<java.time.LocalDate> paymentDueDate) {
        if (issueDate.value() != null
                && paymentDueDate.value() != null
                && paymentDueDate.value().isBefore(issueDate.value())) {
            return withFinding(paymentDueDate, PAYMENT_DUE_BEFORE_ISSUE_DATE);
        }
        return paymentDueDate;
    }

    public AutoEntryDerivedField<AutoEntryTaxMode> taxMode(
            AutoEntryField<String> notation,
            AutoEntryField<BigDecimal> subtotal,
            AutoEntryField<BigDecimal> tax,
            AutoEntryField<List<AutoEntryAdjustment>> adjustments,
            AutoEntryField<BigDecimal> total) {
        AutoEntryTaxMode explicit = explicitTaxMode(notation.value());
        if (explicit != AutoEntryTaxMode.UNKNOWN) {
            return new AutoEntryDerivedField<>(explicit, OK, List.of());
        }
        if (total.value() == null) {
            return new AutoEntryDerivedField<>(AutoEntryTaxMode.UNKNOWN, MISSING, List.of());
        }
        ReconciliationCandidates candidates = candidates(subtotal, tax, adjustments);
        if (candidates.included().isEmpty() || candidates.excluded().isEmpty()) {
            return new AutoEntryDerivedField<>(AutoEntryTaxMode.UNKNOWN, MISSING, List.of());
        }
        boolean included = candidates.included().stream().anyMatch(candidate ->
                within(total.value(), candidate, MONETARY_ROUNDING_TOLERANCE));
        boolean excluded = candidates.excluded().stream().anyMatch(candidate ->
                within(total.value(), candidate, MONETARY_ROUNDING_TOLERANCE));
        if (included ^ excluded) {
            return new AutoEntryDerivedField<>(
                    included ? AutoEntryTaxMode.TAX_INCLUDED : AutoEntryTaxMode.TAX_EXCLUDED,
                    OK,
                    List.of());
        }
        return new AutoEntryDerivedField<>(
                AutoEntryTaxMode.UNKNOWN,
                REVIEW,
                List.of(TAX_MODE_AMBIGUOUS));
    }

    public AutoEntryReviewSummary summary(AutoEntryReviewDocument document) {
        List<AutoEntryField<?>> fields = new ArrayList<>();
        fields.addAll(List.of(
                document.documentType(),
                document.documentNumber(),
                document.issueDate(),
                document.issuerTaxRegistrationNumber(),
                document.recipientName(),
                document.recipientDepartment(),
                document.recipientContactPerson(),
                document.recipientPostalCode(),
                document.recipientAddress(),
                document.issuerName(),
                document.issuerDepartment(),
                document.issuerContactPerson(),
                document.issuerPostalCode(),
                document.issuerAddress(),
                document.issuerPhoneNumber(),
                document.issuerEmail(),
                document.subject(),
                document.currencyCode(),
                document.lineItems(),
                document.subtotalAmount(),
                document.taxAmount(),
                document.totalAmount(),
                document.taxBreakdown(),
                document.adjustments(),
                document.taxInclusionNotation(),
                document.paymentDueDate(),
                document.bankTransferDestination()));
        if (document.lineItems().value() != null) {
            document.lineItems().value().forEach(item -> fields.addAll(lineItemFields(item)));
        }
        if (document.taxBreakdown().value() != null) {
            document.taxBreakdown().value().forEach(item -> fields.addAll(List.of(
                    item.taxRatePercent(), item.taxableAmount(), item.taxAmount(),
                    item.categoryNotation(), item.category())));
        }
        if (document.adjustments().value() != null) {
            document.adjustments().value().forEach(item -> fields.addAll(List.of(
                    item.type(), item.direction(), item.description(), item.rawAmount())));
        }
        if (document.bankTransferDestination().value() != null) {
            var bank = document.bankTransferDestination().value();
            fields.addAll(List.of(
                    bank.bankName(), bank.branchName(), bank.accountType(),
                    bank.accountNumber(), bank.accountHolderName()));
        }
        int ok = count(fields, OK);
        int review = count(fields, REVIEW);
        int missing = count(fields, MISSING);
        return new AutoEntryReviewSummary(fields.size(), ok, review, missing);
    }

    private AutoEntryField<String> enumField(
            String value,
            BigDecimal confidence,
            List<AutoEntrySourceRef> sources,
            Set<String> expected) {
        AutoEntryField<String> field = field(value, confidence, sources);
        return value != null && !expected.contains(value)
                ? withFinding(field, ENUM_VALUE_UNKNOWN)
                : field;
    }

    private static <T> AutoEntryField<T> withFinding(
            AutoEntryField<T> field,
            AutoEntryFindingCode finding) {
        LinkedHashSet<AutoEntryFindingCode> findings = new LinkedHashSet<>(field.findings());
        findings.add(finding);
        List<AutoEntryFindingCode> copied = List.copyOf(findings);
        return new AutoEntryField<>(
                field.value(),
                field.confidence(),
                status(field.value(), copied),
                field.sources(),
                copied);
    }

    private static AutoEntryFieldStatus status(
            Object value,
            List<AutoEntryFindingCode> findings) {
        if (value == null) {
            return MISSING;
        }
        return findings.isEmpty() ? OK : REVIEW;
    }

    private static boolean within(
            BigDecimal actual,
            BigDecimal expected,
            BigDecimal exclusiveTolerance) {
        return actual.subtract(expected).abs().compareTo(exclusiveTolerance) < 0;
    }

    private static AutoEntryTaxMode explicitTaxMode(String rawNotation) {
        if (rawNotation == null) {
            return AutoEntryTaxMode.UNKNOWN;
        }
        String notation = rawNotation.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (notation.contains("税込") || notation.contains("内税")) {
            return AutoEntryTaxMode.TAX_INCLUDED;
        }
        if (notation.contains("税抜") || notation.contains("外税") || notation.contains("税別")) {
            return AutoEntryTaxMode.TAX_EXCLUDED;
        }
        return AutoEntryTaxMode.UNKNOWN;
    }

    private static ReconciliationCandidates candidates(
            AutoEntryField<BigDecimal> subtotal,
            AutoEntryField<BigDecimal> tax,
            AutoEntryField<List<AutoEntryAdjustment>> adjustments) {
        List<BigDecimal> included = new ArrayList<>();
        List<BigDecimal> excluded = new ArrayList<>();
        BigDecimal subtotalValue = subtotal.value();
        BigDecimal taxValue = tax.value();
        BigDecimal adjustmentValue = adjustmentSum(adjustments);
        if (subtotalValue != null) {
            included.add(subtotalValue);
            if (adjustmentValue != null) {
                included.add(subtotalValue.add(adjustmentValue));
            }
            if (taxValue != null) {
                excluded.add(subtotalValue.add(taxValue));
                if (adjustmentValue != null) {
                    excluded.add(subtotalValue.add(taxValue).add(adjustmentValue));
                }
            }
        }
        List<BigDecimal> all = new ArrayList<>(excluded);
        all.addAll(included);
        return new ReconciliationCandidates(
                List.copyOf(included), List.copyOf(excluded), List.copyOf(all));
    }

    private static BigDecimal adjustmentSum(
            AutoEntryField<List<AutoEntryAdjustment>> adjustments) {
        if (adjustments.value() == null
                || adjustments.value().stream()
                        .anyMatch(item -> item.normalizedSignedAmount().value() == null)) {
            return null;
        }
        return adjustments.value().stream()
                .map(item -> item.normalizedSignedAmount().value())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<AutoEntryField<?>> lineItemFields(AutoEntryLineItem item) {
        return List.of(
                item.itemDate(), item.productCode(), item.itemDescription(), item.quantity(),
                item.unit(), item.unitPriceAmount(), item.taxIndicator(), item.taxRatePercent(),
                item.taxCategory(), item.lineAmount());
    }

    private static int count(
            List<AutoEntryField<?>> fields,
            AutoEntryFieldStatus status) {
        return (int) fields.stream().filter(field -> field.status() == status).count();
    }

    private record ReconciliationCandidates(
            List<BigDecimal> included,
            List<BigDecimal> excluded,
            List<BigDecimal> all) {
    }
}
