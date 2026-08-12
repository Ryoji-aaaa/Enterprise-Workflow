package jp.co.sdcj.workflow.service.documentanalysis.autoentry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AutoEntryReviewResponse(
        UUID analysisId,
        String schemaVersion,
        List<AutoEntryPageRef> pages,
        AutoEntryReviewDocument document,
        AutoEntryDerivedField<AutoEntryTaxMode> taxMode,
        AutoEntryReviewSummary summary) {

    public record AutoEntryReviewDocument(
            AutoEntryField<String> documentType,
            AutoEntryField<String> documentNumber,
            AutoEntryField<LocalDate> issueDate,
            AutoEntryField<String> issuerTaxRegistrationNumber,
            AutoEntryField<String> recipientName,
            AutoEntryField<String> recipientDepartment,
            AutoEntryField<String> recipientContactPerson,
            AutoEntryField<String> recipientPostalCode,
            AutoEntryField<String> recipientAddress,
            AutoEntryField<String> issuerName,
            AutoEntryField<String> issuerDepartment,
            AutoEntryField<String> issuerContactPerson,
            AutoEntryField<String> issuerPostalCode,
            AutoEntryField<String> issuerAddress,
            AutoEntryField<String> issuerPhoneNumber,
            AutoEntryField<String> issuerEmail,
            AutoEntryField<String> subject,
            AutoEntryField<String> currencyCode,
            AutoEntryField<List<AutoEntryLineItem>> lineItems,
            AutoEntryField<BigDecimal> subtotalAmount,
            AutoEntryField<BigDecimal> taxAmount,
            AutoEntryField<BigDecimal> totalAmount,
            AutoEntryField<List<AutoEntryTaxBreakdown>> taxBreakdown,
            AutoEntryField<List<AutoEntryAdjustment>> adjustments,
            AutoEntryField<String> taxInclusionNotation,
            AutoEntryField<LocalDate> paymentDueDate,
            AutoEntryField<AutoEntryBankTransferDestination> bankTransferDestination) {
    }

    public record AutoEntryField<T>(
            T value,
            BigDecimal confidence,
            AutoEntryFieldStatus status,
            List<AutoEntrySourceRef> sources,
            List<AutoEntryFindingCode> findings) {
    }

    public record AutoEntryDerivedField<T>(
            T value,
            AutoEntryFieldStatus status,
            List<AutoEntryFindingCode> findings) {
    }

    public record AutoEntryObjectReview(
            BigDecimal confidence,
            AutoEntryFieldStatus status,
            List<AutoEntrySourceRef> sources,
            List<AutoEntryFindingCode> findings) {
    }

    public record AutoEntrySourceRef(
            int pageNumber,
            List<AutoEntryPoint> polygon) {
    }

    public record AutoEntryPoint(
            BigDecimal x,
            BigDecimal y) {
    }

    public record AutoEntryPageRef(
            int pageNumber,
            BigDecimal width,
            BigDecimal height,
            String unit,
            BigDecimal angleDegrees) {
    }

    public record AutoEntryLineItem(
            AutoEntryObjectReview review,
            AutoEntryField<String> itemDate,
            AutoEntryField<String> productCode,
            AutoEntryField<String> itemDescription,
            AutoEntryField<BigDecimal> quantity,
            AutoEntryField<String> unit,
            AutoEntryField<BigDecimal> unitPriceAmount,
            AutoEntryField<String> taxIndicator,
            AutoEntryField<BigDecimal> taxRatePercent,
            AutoEntryField<String> taxCategory,
            AutoEntryField<BigDecimal> lineAmount) {
    }

    public record AutoEntryTaxBreakdown(
            AutoEntryObjectReview review,
            AutoEntryField<BigDecimal> taxRatePercent,
            AutoEntryField<BigDecimal> taxableAmount,
            AutoEntryField<BigDecimal> taxAmount,
            AutoEntryField<String> categoryNotation,
            AutoEntryField<String> category) {
    }

    public record AutoEntryAdjustment(
            AutoEntryObjectReview review,
            AutoEntryField<String> type,
            AutoEntryField<String> direction,
            AutoEntryField<String> description,
            AutoEntryField<BigDecimal> rawAmount,
            AutoEntryDerivedField<BigDecimal> normalizedSignedAmount) {
    }

    public record AutoEntryBankTransferDestination(
            AutoEntryField<String> bankName,
            AutoEntryField<String> branchName,
            AutoEntryField<String> accountType,
            AutoEntryField<String> accountNumber,
            AutoEntryField<String> accountHolderName) {
    }

    public record AutoEntryReviewSummary(
            int fieldCount,
            int okCount,
            int reviewCount,
            int missingCount) {
    }

    public enum AutoEntryFieldStatus {
        OK,
        REVIEW,
        MISSING
    }

    public enum AutoEntryFindingCode {
        LOW_CONFIDENCE,
        ENUM_VALUE_UNKNOWN,
        LINE_AMOUNT_INCONSISTENT,
        TAX_BREAKDOWN_INCONSISTENT,
        TAX_TOTAL_INCONSISTENT,
        TOTAL_INCONSISTENT,
        ADJUSTMENT_DIRECTION_UNKNOWN,
        TAX_MODE_AMBIGUOUS,
        PAYMENT_DUE_BEFORE_ISSUE_DATE
    }

    public enum AutoEntryTaxMode {
        TAX_INCLUDED,
        TAX_EXCLUDED,
        UNKNOWN
    }
}
