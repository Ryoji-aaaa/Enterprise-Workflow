#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIRECTORY
PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly PROJECT_DIRECTORY
# shellcheck source=scripts/lib/log.sh
source "${SCRIPT_DIRECTORY}/lib/log.sh"

readonly VERIFY_INFRA_START=${SECONDS}

cd "${PROJECT_DIRECTORY}"

log_section "Infrastructure configuration checks"
run_step "Checking Terraform formatting" terraform fmt -no-color -check -recursive infra
run_step "Checking backend health probe configuration" \
  "${SCRIPT_DIRECTORY}/check-backend-probes.sh"
run_step "Checking backend internal URL configuration" \
  "${SCRIPT_DIRECTORY}/check-backend-internal-url.sh"
run_step "Checking manual seed job names and environment guards" \
  "${SCRIPT_DIRECTORY}/check-manual-seed-job-names.sh"
run_step "Checking expense attachment storage and identity boundaries" \
  "${SCRIPT_DIRECTORY}/check-attachment-storage.sh"
run_step "Checking Document Analysis Azure infrastructure boundaries" \
  "${SCRIPT_DIRECTORY}/check-document-analysis-infra.sh"
run_step "Checking Content Understanding AUTO_ENTRY v2.1.1 Analyzer schema" \
  "${SCRIPT_DIRECTORY}/check-content-understanding-auto-entry-schema.sh"
run_step "Regression testing Terraform plan safety gate" \
  "${SCRIPT_DIRECTORY}/test-check-terraform-plan-safety.sh"
run_step "Regression testing Document Analysis Azure verifier" \
  "${SCRIPT_DIRECTORY}/test-verify-document-analysis-azure.sh"
run_step "Checking fail-closed Azure notification boundaries" \
  "${SCRIPT_DIRECTORY}/check-azure-notification-boundary.sh"

log_section "Terraform validation"
run_step "Initializing the bootstrap Terraform root without a backend" \
  terraform -chdir=infra/bootstrap init -no-color -backend=false
run_step "Validating the bootstrap Terraform root" \
  terraform -chdir=infra/bootstrap validate -no-color
run_step "Initializing the staging Terraform root without a backend" \
  terraform -chdir=infra/environments/staging init -no-color -backend=false
run_step "Validating the staging Terraform root" \
  terraform -chdir=infra/environments/staging validate -no-color
run_step "Initializing the production Terraform root without a backend" \
  terraform -chdir=infra/environments/production init -no-color -backend=false
run_step "Validating the production Terraform root" \
  terraform -chdir=infra/environments/production validate -no-color

log_pass "Infrastructure verification completed ($(format_duration "$((SECONDS - VERIFY_INFRA_START))"))"
