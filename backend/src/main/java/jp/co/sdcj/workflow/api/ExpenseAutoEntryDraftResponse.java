package jp.co.sdcj.workflow.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jp.co.sdcj.workflow.service.ExpenseAutoEntryDraftDetails;
import jp.co.sdcj.workflow.service.ExpenseAutoEntryHumanReviewState;
import jp.co.sdcj.workflow.service.ExpenseAutoEntryHumanReviewState.HumanResolution;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryAdjustment;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryDerivedField;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryField;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryTaxMode;

public record ExpenseAutoEntryDraftResponse(
        Application application,
        AutoEntry autoEntry) {

    public record Application(
            UUID id,
            String applicationNumber,
            String category,
            String title,
            String purpose,
            LocalDate expenseDate,
            BigDecimal totalAmount,
            String currencyCode,
            String remarks,
            String status,
            long version,
            List<Item> items) {
    }

    public record Item(
            UUID id,
            int displayOrder,
            Integer sourceLineItemIndex,
            LocalDate expenseDate,
            String description,
            BigDecimal amount,
            String merchantName,
            String origin,
            String destination,
            String transportationType,
            String participants) {
    }

    public record AutoEntry(
            UUID analysisId,
            long contextVersion,
            int contextSchemaVersion,
            UUID sourceAttachmentId,
            String schemaVersion,
            Original original,
            ExpenseAutoEntryHumanReviewState.Document currentDocument,
            Map<String, ExpenseAutoEntryHumanReviewState.FieldState> fields,
            int unresolvedCount,
            List<ExpenseAutoEntryDraftDetails.Warning> warnings) {
    }

    public record Original(
            AutoEntryField<String> issuerName,
            AutoEntryField<String> issuerTaxRegistrationNumber,
            AutoEntryField<BigDecimal> invoiceTotalAmount,
            AutoEntryField<BigDecimal> taxAmount,
            AutoEntryDerivedField<AutoEntryTaxMode> taxMode,
            AutoEntryField<List<AutoEntryAdjustment>> adjustments,
            List<OriginalLineItem> lineItems) {
    }

    public record OriginalLineItem(
            int sourceLineItemIndex,
            AutoEntryField<String> itemDescription,
            AutoEntryField<BigDecimal> lineAmount) {
    }

    public static ExpenseAutoEntryDraftResponse from(ExpenseAutoEntryDraftDetails details) {
        var application = details.applicationDetails().application();
        var applicationItems = details.applicationDetails().items();
        var stateItems = details.humanReviewState().items();
        if (applicationItems.size() != stateItems.size()) {
            throw new IllegalStateException("Expense AUTO_ENTRY item state is inconsistent");
        }
        List<Item> items = java.util.stream.IntStream.range(0, applicationItems.size())
                .mapToObj(index -> {
                    var item = applicationItems.get(index);
                    var state = stateItems.get(index);
                    return new Item(
                            item.getId(), item.getDisplayOrder(), state.sourceLineItemIndex(),
                            item.getExpenseDate(), item.getDescription(), item.getAmount(),
                            item.getMerchantName(), item.getOrigin(), item.getDestination(),
                            item.getTransportationType(), item.getParticipants());
                }).toList();
        AutoEntryReviewResponse.AutoEntryReviewDocument reviewDocument =
                details.review().document();
        List<AutoEntryReviewResponse.AutoEntryLineItem> reviewLineItems =
                reviewDocument.lineItems().value() == null
                        ? List.of() : reviewDocument.lineItems().value();
        Original original = new Original(
                reviewDocument.issuerName(),
                reviewDocument.issuerTaxRegistrationNumber(),
                reviewDocument.totalAmount(),
                reviewDocument.taxAmount(),
                details.review().taxMode(),
                reviewDocument.adjustments(),
                java.util.stream.IntStream.range(0, reviewLineItems.size())
                        .mapToObj(index -> new OriginalLineItem(
                                index,
                                reviewLineItems.get(index).itemDescription(),
                                reviewLineItems.get(index).lineAmount()))
                        .toList());
        int unresolved = (int) details.humanReviewState().fields().values().stream()
                .filter(field -> field.resolution() == HumanResolution.UNRESOLVED)
                .count();
        return new ExpenseAutoEntryDraftResponse(
                new Application(
                        application.getId(), application.getApplicationNumber(),
                        application.getCategory().name(), application.getTitle(),
                        application.getPurpose(), application.getExpenseDate(),
                        application.getTotalAmount(), application.getCurrencyCode(),
                        application.getRemarks(), application.getStatus().name(),
                        application.getVersion(), items),
                new AutoEntry(
                        details.context().getAnalysisId(), details.context().getVersion(),
                        details.context().getContextSchemaVersion(),
                        details.context().getSourceAttachmentId(),
                        details.context().getAutoEntrySchemaVersion(), original,
                        details.humanReviewState().document(),
                        details.humanReviewState().fields(), unresolved, details.warnings()));
    }
}
