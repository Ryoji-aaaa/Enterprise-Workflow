#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly GATE_SCRIPT="${SCRIPT_DIRECTORY}/check-terraform-plan-safety.sh"
readonly FIXTURE_DIRECTORY="${SCRIPT_DIRECTORY}/fixtures"
readonly TEMPORARY_DIAGNOSTIC_ADDRESS='module.environment.azurerm_monitor_diagnostic_setting.attachment_blob_write_diagnosis[0]'

if ! "${GATE_SCRIPT}" \
  "${FIXTURE_DIRECTORY}/terraform-plan-safety-allowed.json" \
  --allow-delete "${TEMPORARY_DIAGNOSTIC_ADDRESS}"; then
  echo "Plan safety gate rejected the exact temporary diagnostic-setting deletion." >&2
  exit 1
fi

if "${GATE_SCRIPT}" "${FIXTURE_DIRECTORY}/terraform-plan-safety-unsafe.json" >/dev/null 2>&1; then
  echo "Plan safety gate accepted protected resource deletion or replacement." >&2
  exit 1
fi

echo "Terraform plan safety gate regression tests passed."
