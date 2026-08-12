#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly ANALYZER_FILE="${PROJECT_DIRECTORY}/infra/content-understanding/analyzers/enterprise_workflow_auto_entry_v2.1.json"

fail() {
  echo "AUTO_ENTRY v2.1 Analyzer schema check failed: $1" >&2
  exit 1
}

[[ -f "${ANALYZER_FILE}" ]] || fail "Analyzer definition is missing."
jq empty "${ANALYZER_FILE}" || fail "Analyzer definition is not valid JSON."

readonly EXPECTED_FIELDS_JSON='[
  "Adjustments",
  "BankTransferDestination",
  "CurrencyCode",
  "DocumentNumber",
  "DocumentType",
  "IssueDate",
  "IssuerAddress",
  "IssuerContactPerson",
  "IssuerDepartment",
  "IssuerEmail",
  "IssuerName",
  "IssuerPhoneNumber",
  "IssuerPostalCode",
  "IssuerTaxRegistrationNumber",
  "LineItems",
  "PaymentDueDate",
  "RecipientAddress",
  "RecipientContactPerson",
  "RecipientDepartment",
  "RecipientName",
  "RecipientPostalCode",
  "Subject",
  "SubtotalAmount",
  "TaxAmount",
  "TaxBreakdown",
  "TaxInclusionNotation",
  "TotalAmount"
]'
readonly TAX_CATEGORY_ENUM_JSON='[
  "STANDARD",
  "REDUCED",
  "NON_TAXABLE",
  "EXEMPT"
]'
readonly SECRET_VALUE_PATTERN='(-----BEGIN [A-Z ]*PRIVATE KEY-----|Bearer[[:space:]]+[A-Za-z0-9._~-]+|AccountKey=|SharedAccessSignature=|[?&]sig=|https://[^[:space:]]+\.blob\.core\.windows\.net/[^[:space:]]+)'

jq -e \
  --argjson expectedFields "${EXPECTED_FIELDS_JSON}" \
  --argjson taxCategoryEnum "${TAX_CATEGORY_ENUM_JSON}" '
    .analyzerId == "enterprise_workflow_auto_entry_v2.1"
    and .baseAnalyzerId == "prebuilt-document"
    and .processingLocation == "geography"
    and .config.returnDetails == true
    and .config.estimateFieldSourceAndConfidence == true
    and .models == {
      "completion": "gpt-5.2",
      "embedding": "text-embedding-3-large"
    }
    and ((.fieldSchema.fields | keys) == ($expectedFields | sort))
    and .fieldSchema.fields.DocumentType.enum == [
      "INVOICE",
      "PURCHASE_ORDER",
      "ORDER_CONFIRMATION",
      "OTHER"
    ]
    and .fieldSchema.fields.LineItems.items.properties.TaxCategory.enum
      == $taxCategoryEnum
    and .fieldSchema.fields.TaxBreakdown.items.properties.Category.enum
      == $taxCategoryEnum
    and .fieldSchema.fields.TaxBreakdown.items.properties.CategoryNotation.type
      == "string"
    and .fieldSchema.fields.TaxBreakdown.items.properties.CategoryNotation.method
      == "extract"
    and .fieldSchema.fields.Adjustments.items.properties.Type.enum == [
      "WITHHOLDING_TAX",
      "DISCOUNT",
      "SHIPPING_FEE",
      "SERVICE_FEE",
      "ROUNDING",
      "OTHER"
    ]
    and .fieldSchema.fields.Adjustments.items.properties.Direction.enum == [
      "DEDUCTION",
      "ADDITION",
      "UNKNOWN"
    ]
    and .fieldSchema.fields.BankTransferDestination.type == "object"
    and (.fieldSchema.fields.BankTransferDestination.properties | keys) == [
      "AccountHolderName",
      "AccountNumber",
      "AccountType",
      "BankName",
      "BranchName"
    ]
  ' "${ANALYZER_FILE}" >/dev/null \
  || fail "Frozen Analyzer fields, enums, models, or processing config changed."

if jq -r '.. | strings' "${ANALYZER_FILE}" \
    | grep -Ei "${SECRET_VALUE_PATTERN}" >/dev/null; then
  fail "Analyzer definition contains a secret-like value or private Blob URL."
fi

if jq -e '
    [paths(scalars) as $path
      | select(($path[-1] | tostring)
          | test("(password|client.?secret|access.?token|api.?key|sas.?token)"; "i"))]
    | length > 0
  ' "${ANALYZER_FILE}" >/dev/null; then
  fail "Analyzer definition contains a secret-like property."
fi

echo "AUTO_ENTRY v2.1 Analyzer schema is valid."
