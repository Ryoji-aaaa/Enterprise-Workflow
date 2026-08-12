#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly LEGACY_ANALYZER_FILE="${PROJECT_DIRECTORY}/infra/content-understanding/analyzers/enterprise_workflow_auto_entry_v2.1.json"
readonly ANALYZER_FILE="${PROJECT_DIRECTORY}/infra/content-understanding/analyzers/enterprise_workflow_auto_entry_v2.1.1.json"
readonly LEGACY_ANALYZER_SHA256="a3570d8bb3473d6c608bb0296a9c5cffe3ef50c65abbe240a389a16346855466"

fail() {
  echo "AUTO_ENTRY v2.1.1 Analyzer schema check failed: $1" >&2
  exit 1
}

for analyzer_file in "${LEGACY_ANALYZER_FILE}" "${ANALYZER_FILE}"; do
  [[ -f "${analyzer_file}" ]] || fail "Analyzer definition is missing: ${analyzer_file#"${PROJECT_DIRECTORY}/"}."
  jq empty "${analyzer_file}" || fail "Analyzer definition is not valid JSON: ${analyzer_file#"${PROJECT_DIRECTORY}/"}."
done

actual_legacy_sha256="$(sha256sum "${LEGACY_ANALYZER_FILE}" | awk '{print $1}')"
[[ "${actual_legacy_sha256}" == "${LEGACY_ANALYZER_SHA256}" ]] \
  || fail "Historical v2.1 Analyzer definition changed."

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
readonly TAX_BREAKDOWN_DESCRIPTION="帳票に明示された税率別の課税対象額・税額内訳。同じ税率は必ず1要素にまとめ、10%と8%が記載されている場合は10%要素と8%要素の2要素だけを抽出する。同じ税率の対象額行と、その直後または近傍にある消費税行を同じ要素へまとめる。各要素では、同じ税率別集計行・列・連続ブロックに記載された TaxRatePercent、TaxableAmount、TaxAmount、CategoryNotation を対応付ける。CategoryNotation 内に印字された 10% または 8% は明示された税率であり、TaxRatePercent にそれぞれ 10 または 8 を必ず抽出する。『10%対象額』『10%対象計』『軽減8%対象額』『8%対象計(*)』等の完全な税率別集計表記が帳票上に存在する場合は、その表記を CategoryNotation として省略せず原文どおり抽出する。TaxRatePercent または完全な CategoryNotation を抽出できない候補は TaxBreakdown 要素に含めない。数字が欠けた『%対象額』等のOCR断片、重複候補、税率が明示されない孤立した金額から追加要素を推測生成しない。"
readonly TAX_RATE_PERCENT_DESCRIPTION="税率の百分率値。CategoryNotation の帳票ラベルに印字された百分率の数字を必ず抽出し、『10%対象額』『10%対象計』なら 10、『軽減8%対象額』『8%対象計(*)』なら 8 とする。ラベル内の数字と%記号が別のOCR tokenでも、同じラベル領域に印字されていれば明示された税率であり、値を省略しない。同じ税率別集計行・列・連続ブロックの TaxableAmount、TaxAmount、CategoryNotation と対応付けて抽出する。明示された百分率の数字を抽出できない候補は TaxBreakdown 要素自体に含めない。Category や金額だけから TaxRatePercent を推測生成・補正・正規化しない。"
readonly CATEGORY_NOTATION_DESCRIPTION="同じ TaxBreakdown 要素の TaxRatePercent、TaxableAmount、TaxAmount と同じ税率別集計行・列・連続ブロックに記載された、当該税区分を示す帳票上のラベル全体を抽出する。『10%対象額』『10%対象計』『軽減8%対象額』『8%対象計(*)』等が帳票上に存在する場合は、税率の数字、%記号、軽減表記、注記を欠落させず原文どおり抽出する。完全なラベル全体を抽出できない候補は TaxBreakdown 要素自体に含めない。『%対象額』等の不完全なOCR断片を CategoryNotation として抽出したり、その断片から別の TaxBreakdown 要素を作ったりしない。Category や金額から CategoryNotation を推測生成・補正・正規化しない。記載がない場合は生成しない。"
readonly SECRET_VALUE_PATTERN='(-----BEGIN [A-Z ]*PRIVATE KEY-----|Bearer[[:space:]]+[A-Za-z0-9._~-]+|AccountKey=|SharedAccessSignature=|[?&]sig=|https://[^[:space:]]+\.blob\.core\.windows\.net/[^[:space:]]+)'

jq -e \
  --argjson expectedFields "${EXPECTED_FIELDS_JSON}" \
  --argjson taxCategoryEnum "${TAX_CATEGORY_ENUM_JSON}" \
  --arg taxBreakdownDescription "${TAX_BREAKDOWN_DESCRIPTION}" \
  --arg taxRatePercentDescription "${TAX_RATE_PERCENT_DESCRIPTION}" \
  --arg categoryNotationDescription "${CATEGORY_NOTATION_DESCRIPTION}" '
    .analyzerId == "enterprise_workflow_auto_entry_v2.1.1"
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
    and .fieldSchema.fields.TaxBreakdown.description
      == $taxBreakdownDescription
    and .fieldSchema.fields.TaxBreakdown.items.properties.TaxRatePercent.description
      == $taxRatePercentDescription
    and .fieldSchema.fields.TaxBreakdown.items.properties.CategoryNotation.type
      == "string"
    and .fieldSchema.fields.TaxBreakdown.items.properties.CategoryNotation.method
      == "extract"
    and .fieldSchema.fields.TaxBreakdown.items.properties.CategoryNotation.description
      == $categoryNotationDescription
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
  || fail "Frozen Analyzer fields, enums, models, processing config, or extraction instructions changed."

# The GA 2025-11-01 Create Or Replace request accepts only these top-level
# properties. Ignore path/response-only properties and the three intentional
# prompt changes, then require the v2.1.1 request body to equal historical v2.1.
readonly PUT_BODY_FILTER='
  with_entries(select(.key == "baseAnalyzerId"
    or .key == "config"
    or .key == "description"
    or .key == "dynamicFieldSchema"
    or .key == "fieldSchema"
    or .key == "knowledgeSources"
    or .key == "models"
    or .key == "processingLocation"
    or .key == "tags"))
  | .fieldSchema.fields.TaxBreakdown.description = "__PATCH_DESCRIPTION__"
  | .fieldSchema.fields.TaxBreakdown.items.properties.TaxRatePercent.description = "__PATCH_DESCRIPTION__"
  | .fieldSchema.fields.TaxBreakdown.items.properties.CategoryNotation.description = "__PATCH_DESCRIPTION__"
'
legacy_request_body="$(jq -S -c "${PUT_BODY_FILTER}" "${LEGACY_ANALYZER_FILE}")"
patched_request_body="$(jq -S -c "${PUT_BODY_FILTER}" "${ANALYZER_FILE}")"
[[ "${legacy_request_body}" == "${patched_request_body}" ]] \
  || fail "v2.1.1 changed more than the three permitted TaxBreakdown descriptions."

for analyzer_file in "${LEGACY_ANALYZER_FILE}" "${ANALYZER_FILE}"; do
  if jq -r '.. | strings' "${analyzer_file}" \
      | grep -Ei "${SECRET_VALUE_PATTERN}" >/dev/null; then
    fail "Analyzer definition contains a secret-like value or private Blob URL."
  fi

  if jq -e '
      [paths(scalars) as $path
        | select(($path[-1] | tostring)
            | test("(password|client.?secret|access.?token|api.?key|sas.?token)"; "i"))]
      | length > 0
    ' "${analyzer_file}" >/dev/null; then
    fail "Analyzer definition contains a secret-like property."
  fi
done

echo "AUTO_ENTRY v2.1.1 Analyzer schema is valid."
