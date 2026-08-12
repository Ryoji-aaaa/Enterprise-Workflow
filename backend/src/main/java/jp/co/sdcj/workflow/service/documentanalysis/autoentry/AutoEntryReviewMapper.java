package jp.co.sdcj.workflow.service.documentanalysis.autoentry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryAdjustment;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryBankTransferDestination;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryDerivedField;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryField;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryLineItem;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryObjectReview;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryPageRef;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryPoint;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryReviewDocument;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntrySourceRef;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryTaxBreakdown;

@Component
@ConditionalOnProperty(prefix = "workflow.document-analysis", name = "enabled", havingValue = "true")
public class AutoEntryReviewMapper {

    private static final String AUTO_ENTRY_SCHEMA_VERSION = "2.1";

    private final ObjectMapper objectMapper;
    private final AutoEntryReviewRules rules;

    public AutoEntryReviewMapper(ObjectMapper objectMapper, AutoEntryReviewRules rules) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.rules = Objects.requireNonNull(rules);
    }

    public AutoEntryReviewResponse map(UUID analysisId, byte[] normalizedJson) {
        try {
            JsonNode root = objectMapper
                    .reader(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(normalizedJson);
            return mapRoot(analysisId, root);
        } catch (AutoEntryResultInvalidException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private AutoEntryReviewResponse mapRoot(UUID analysisId, JsonNode root) {
        requireObject(root);
        requireInt(root, "schemaVersion", 1);
        requireText(root, "analysisId", analysisId.toString());
        requireText(root, "provider", "CONTENT_UNDERSTANDING");
        requireText(root, "status", "SUCCEEDED");

        JsonNode documents = requiredArray(root, "documents");
        if (documents.size() != 1) {
            throw invalid();
        }
        JsonNode normalizedDocument = requireObject(documents.get(0));
        JsonNode documentFields = requiredObject(normalizedDocument, "fields");
        JsonNode autoEntry = requiredObject(documentFields, "autoEntry");
        requireText(autoEntry, "schemaVersion", AUTO_ENTRY_SCHEMA_VERSION);
        List<AutoEntryPageRef> pages = pages(requiredArray(autoEntry, "pages"));
        JsonNode fields = requiredObject(autoEntry, "fields");

        AutoEntryReviewDocument document = document(fields);
        AutoEntryDerivedField<AutoEntryReviewResponse.AutoEntryTaxMode> taxMode = rules.taxMode(
                document.taxInclusionNotation(),
                document.subtotalAmount(),
                document.taxAmount(),
                document.adjustments(),
                document.totalAmount());
        return new AutoEntryReviewResponse(
                analysisId,
                AUTO_ENTRY_SCHEMA_VERSION,
                pages,
                document,
                taxMode,
                rules.summary(document));
    }

    private AutoEntryReviewDocument document(JsonNode fields) {
        AutoEntryField<String> documentType = enumStringField(fields, "DocumentType", rules::documentType);
        AutoEntryField<String> documentNumber = stringField(fields, "DocumentNumber");
        AutoEntryField<LocalDate> issueDate = dateField(fields, "IssueDate");
        AutoEntryField<String> issuerTaxRegistrationNumber = stringField(
                fields, "IssuerTaxRegistrationNumber");
        AutoEntryField<String> recipientName = stringField(fields, "RecipientName");
        AutoEntryField<String> recipientDepartment = stringField(fields, "RecipientDepartment");
        AutoEntryField<String> recipientContactPerson = stringField(fields, "RecipientContactPerson");
        AutoEntryField<String> recipientPostalCode = stringField(fields, "RecipientPostalCode");
        AutoEntryField<String> recipientAddress = stringField(fields, "RecipientAddress");
        AutoEntryField<String> issuerName = stringField(fields, "IssuerName");
        AutoEntryField<String> issuerDepartment = stringField(fields, "IssuerDepartment");
        AutoEntryField<String> issuerContactPerson = stringField(fields, "IssuerContactPerson");
        AutoEntryField<String> issuerPostalCode = stringField(fields, "IssuerPostalCode");
        AutoEntryField<String> issuerAddress = stringField(fields, "IssuerAddress");
        AutoEntryField<String> issuerPhoneNumber = stringField(fields, "IssuerPhoneNumber");
        AutoEntryField<String> issuerEmail = stringField(fields, "IssuerEmail");
        AutoEntryField<String> subject = stringField(fields, "Subject");
        AutoEntryField<String> currencyCode = stringField(fields, "CurrencyCode");
        AutoEntryField<List<AutoEntryLineItem>> lineItems = lineItemsField(fields, "LineItems");
        AutoEntryField<BigDecimal> subtotalAmount = numberField(fields, "SubtotalAmount");
        AutoEntryField<BigDecimal> taxAmount = numberField(fields, "TaxAmount");
        AutoEntryField<List<AutoEntryTaxBreakdown>> taxBreakdown = taxBreakdownField(
                fields, "TaxBreakdown");
        taxAmount = rules.taxTotal(taxAmount, taxBreakdown);
        AutoEntryField<List<AutoEntryAdjustment>> adjustments = adjustmentsField(
                fields, "Adjustments");
        AutoEntryField<BigDecimal> totalAmount = rules.total(
                subtotalAmount, taxAmount, adjustments, numberField(fields, "TotalAmount"));
        AutoEntryField<String> taxInclusionNotation = stringField(
                fields, "TaxInclusionNotation");
        AutoEntryField<LocalDate> paymentDueDate = rules.paymentDueDate(
                issueDate, dateField(fields, "PaymentDueDate"));
        AutoEntryField<AutoEntryBankTransferDestination> bankTransferDestination =
                bankTransferDestinationField(fields, "BankTransferDestination");

        return new AutoEntryReviewDocument(
                documentType,
                documentNumber,
                issueDate,
                issuerTaxRegistrationNumber,
                recipientName,
                recipientDepartment,
                recipientContactPerson,
                recipientPostalCode,
                recipientAddress,
                issuerName,
                issuerDepartment,
                issuerContactPerson,
                issuerPostalCode,
                issuerAddress,
                issuerPhoneNumber,
                issuerEmail,
                subject,
                currencyCode,
                lineItems,
                subtotalAmount,
                taxAmount,
                totalAmount,
                taxBreakdown,
                adjustments,
                taxInclusionNotation,
                paymentDueDate,
                bankTransferDestination);
    }

    private AutoEntryField<String> stringField(JsonNode fields, String name) {
        ParsedField<String> parsed = parseField(fields, name, "string", this::textValue);
        return rules.field(parsed.value(), parsed.confidence(), parsed.sources());
    }

    private AutoEntryField<String> enumStringField(
            JsonNode fields,
            String name,
            StringFieldFactory factory) {
        ParsedField<String> parsed = parseField(fields, name, "string", this::textValue);
        return factory.create(parsed.value(), parsed.confidence(), parsed.sources());
    }

    private AutoEntryField<LocalDate> dateField(JsonNode fields, String name) {
        ParsedField<LocalDate> parsed = parseField(fields, name, "date", this::dateValue);
        return rules.field(parsed.value(), parsed.confidence(), parsed.sources());
    }

    private AutoEntryField<BigDecimal> numberField(JsonNode fields, String name) {
        ParsedField<BigDecimal> parsed = parseField(fields, name, "number", this::decimalValue);
        return rules.field(parsed.value(), parsed.confidence(), parsed.sources());
    }

    private AutoEntryField<List<AutoEntryLineItem>> lineItemsField(JsonNode fields, String name) {
        ParsedField<List<AutoEntryLineItem>> parsed = parseField(
                fields, name, "array", node -> arrayValue(node, this::lineItem));
        return rules.field(parsed.value(), parsed.confidence(), parsed.sources());
    }

    private AutoEntryLineItem lineItem(JsonNode item) {
        ParsedObjectElement object = objectElement(item);
        JsonNode fields = object.value();
        AutoEntryObjectReview review = rules.objectReview(
                object.confidence(), object.sources());
        AutoEntryField<String> itemDate = stringField(fields, "ItemDate");
        AutoEntryField<String> productCode = stringField(fields, "ProductCode");
        AutoEntryField<String> itemDescription = stringField(fields, "ItemDescription");
        AutoEntryField<BigDecimal> quantity = numberField(fields, "Quantity");
        AutoEntryField<String> unit = stringField(fields, "Unit");
        AutoEntryField<BigDecimal> unitPriceAmount = numberField(fields, "UnitPriceAmount");
        AutoEntryField<String> taxIndicator = stringField(fields, "TaxIndicator");
        AutoEntryField<BigDecimal> taxRatePercent = numberField(fields, "TaxRatePercent");
        AutoEntryField<String> taxCategory = enumStringField(
                fields, "TaxCategory", rules::taxCategory);
        AutoEntryField<BigDecimal> lineAmount = rules.lineAmount(
                quantity, unitPriceAmount, numberField(fields, "LineAmount"));
        return new AutoEntryLineItem(
                review,
                itemDate,
                productCode,
                itemDescription,
                quantity,
                unit,
                unitPriceAmount,
                taxIndicator,
                taxRatePercent,
                taxCategory,
                lineAmount);
    }

    private AutoEntryField<List<AutoEntryTaxBreakdown>> taxBreakdownField(
            JsonNode fields,
            String name) {
        ParsedField<List<AutoEntryTaxBreakdown>> parsed = parseField(
                fields, name, "array", node -> arrayValue(node, this::taxBreakdown));
        return rules.field(parsed.value(), parsed.confidence(), parsed.sources());
    }

    private AutoEntryTaxBreakdown taxBreakdown(JsonNode item) {
        ParsedObjectElement object = objectElement(item);
        JsonNode fields = object.value();
        return rules.taxBreakdown(
                rules.objectReview(object.confidence(), object.sources()),
                numberField(fields, "TaxRatePercent"),
                numberField(fields, "TaxableAmount"),
                numberField(fields, "TaxAmount"),
                stringField(fields, "CategoryNotation"),
                enumStringField(fields, "Category", rules::taxCategory));
    }

    private AutoEntryField<List<AutoEntryAdjustment>> adjustmentsField(
            JsonNode fields,
            String name) {
        ParsedField<List<AutoEntryAdjustment>> parsed = parseField(
                fields, name, "array", node -> arrayValue(node, this::adjustment));
        return rules.field(parsed.value(), parsed.confidence(), parsed.sources());
    }

    private AutoEntryAdjustment adjustment(JsonNode item) {
        ParsedObjectElement object = objectElement(item);
        JsonNode fields = object.value();
        return rules.adjustment(
                rules.objectReview(object.confidence(), object.sources()),
                enumStringField(fields, "Type", rules::adjustmentType),
                enumStringField(fields, "Direction", rules::adjustmentDirection),
                stringField(fields, "Description"),
                numberField(fields, "Amount"));
    }

    private AutoEntryField<AutoEntryBankTransferDestination> bankTransferDestinationField(
            JsonNode fields,
            String name) {
        ParsedField<AutoEntryBankTransferDestination> parsed = parseField(
                fields, name, "object", this::bankTransferDestination);
        return rules.field(parsed.value(), parsed.confidence(), parsed.sources());
    }

    private AutoEntryBankTransferDestination bankTransferDestination(JsonNode fields) {
        requireObject(fields);
        return new AutoEntryBankTransferDestination(
                stringField(fields, "BankName"),
                stringField(fields, "BranchName"),
                stringField(fields, "AccountType"),
                stringField(fields, "AccountNumber"),
                stringField(fields, "AccountHolderName"));
    }

    private <T> ParsedField<T> parseField(
            JsonNode fields,
            String name,
            String expectedType,
            Function<JsonNode, T> valueParser) {
        JsonNode field = fields.get(name);
        if (field == null || field.isNull()) {
            return new ParsedField<>(null, null, List.of());
        }
        requireObject(field);
        requireText(field, "type", expectedType);
        JsonNode valueNode = field.get("value");
        T value = valueNode == null || valueNode.isNull() ? null : valueParser.apply(valueNode);
        BigDecimal confidence = optionalConfidence(field.get("confidence"));
        List<AutoEntrySourceRef> sources = optionalSources(field.get("sources"));
        return new ParsedField<>(value, confidence, sources);
    }

    private ParsedObjectElement objectElement(JsonNode item) {
        requireObject(item);
        requireText(item, "type", "object");
        JsonNode value = item.get("value");
        return new ParsedObjectElement(
                requireObject(value),
                optionalConfidence(item.get("confidence")),
                optionalSources(item.get("sources")));
    }

    private <T> List<T> arrayValue(JsonNode node, Function<JsonNode, T> mapper) {
        if (!node.isArray()) {
            throw invalid();
        }
        List<T> values = new ArrayList<>();
        node.forEach(item -> values.add(mapper.apply(item)));
        return List.copyOf(values);
    }

    private List<AutoEntryPageRef> pages(JsonNode node) {
        if (node.isEmpty()) {
            throw invalid();
        }
        List<AutoEntryPageRef> pages = new ArrayList<>();
        node.forEach(page -> {
            requireObject(page);
            pages.add(new AutoEntryPageRef(
                    positiveInt(page, "pageNumber"),
                    optionalDecimal(page.get("width")),
                    optionalDecimal(page.get("height")),
                    optionalText(page.get("unit")),
                    optionalDecimal(page.get("angleDegrees"))));
        });
        return List.copyOf(pages);
    }

    private List<AutoEntrySourceRef> optionalSources(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw invalid();
        }
        List<AutoEntrySourceRef> sources = new ArrayList<>();
        node.forEach(source -> {
            requireObject(source);
            JsonNode polygonNode = requiredArray(source, "polygon");
            List<AutoEntryPoint> polygon = new ArrayList<>();
            polygonNode.forEach(point -> {
                requireObject(point);
                polygon.add(new AutoEntryPoint(
                        requiredDecimal(point, "x"),
                        requiredDecimal(point, "y")));
            });
            sources.add(new AutoEntrySourceRef(
                    positiveInt(source, "pageNumber"), List.copyOf(polygon)));
        });
        return List.copyOf(sources);
    }

    private BigDecimal optionalConfidence(JsonNode node) {
        BigDecimal confidence = optionalDecimal(node);
        if (confidence != null
                && (confidence.compareTo(BigDecimal.ZERO) < 0
                        || confidence.compareTo(BigDecimal.ONE) > 0)) {
            throw invalid();
        }
        return confidence;
    }

    private String textValue(JsonNode node) {
        if (!node.isString()) {
            throw invalid();
        }
        return node.stringValue();
    }

    private LocalDate dateValue(JsonNode node) {
        try {
            return LocalDate.parse(textValue(node));
        } catch (DateTimeParseException exception) {
            throw invalid();
        }
    }

    private BigDecimal decimalValue(JsonNode node) {
        if (!node.isNumber()) {
            throw invalid();
        }
        return node.decimalValue();
    }

    private static JsonNode requiredObject(JsonNode parent, String name) {
        return requireObject(parent.get(name));
    }

    private static JsonNode requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid();
        }
        return node;
    }

    private static JsonNode requiredArray(JsonNode parent, String name) {
        JsonNode node = parent.get(name);
        if (node == null || !node.isArray()) {
            throw invalid();
        }
        return node;
    }

    private static void requireInt(JsonNode parent, String name, int expected) {
        JsonNode node = parent.get(name);
        if (node == null || !node.isIntegralNumber() || node.intValue() != expected) {
            throw invalid();
        }
    }

    private static int positiveInt(JsonNode parent, String name) {
        JsonNode node = parent.get(name);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw invalid();
        }
        int value = node.intValue();
        if (value <= 0) {
            throw invalid();
        }
        return value;
    }

    private static void requireText(JsonNode parent, String name, String expected) {
        JsonNode node = parent.get(name);
        if (node == null || !node.isString() || !expected.equals(node.stringValue())) {
            throw invalid();
        }
    }

    private BigDecimal requiredDecimal(JsonNode parent, String name) {
        JsonNode node = parent.get(name);
        if (node == null) {
            throw invalid();
        }
        return decimalValue(node);
    }

    private BigDecimal optionalDecimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return decimalValue(node);
    }

    private String optionalText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return textValue(node);
    }

    private static AutoEntryResultInvalidException invalid() {
        return new AutoEntryResultInvalidException();
    }

    private record ParsedField<T>(
            T value,
            BigDecimal confidence,
            List<AutoEntrySourceRef> sources) {
    }

    private record ParsedObjectElement(
            JsonNode value,
            BigDecimal confidence,
            List<AutoEntrySourceRef> sources) {
    }

    @FunctionalInterface
    private interface StringFieldFactory {
        AutoEntryField<String> create(
                String value,
                BigDecimal confidence,
                List<AutoEntrySourceRef> sources);
    }
}
