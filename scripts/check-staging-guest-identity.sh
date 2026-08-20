#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly STACK_FILE="${PROJECT_DIRECTORY}/infra/modules/environment-stack/main.tf"
readonly STACK_VARIABLES_FILE="${PROJECT_DIRECTORY}/infra/modules/environment-stack/variables.tf"
readonly STAGING_MAIN_FILE="${PROJECT_DIRECTORY}/infra/environments/staging/main.tf"
readonly PRODUCTION_DIRECTORY="${PROJECT_DIRECTORY}/infra/environments/production"
readonly PRODUCTION_WORKFLOW="${PROJECT_DIRECTORY}/.github/workflows/deploy-production.yml"
readonly BACKEND_GUEST_CATALOG="${PROJECT_DIRECTORY}/backend/seed/guest-users.tsv"
readonly KEYCLOAK_GUEST_CATALOG="${PROJECT_DIRECTORY}/keycloak/guest-users.tsv"
readonly SEED_DOCKERFILE="${PROJECT_DIRECTORY}/backend/Dockerfile"
readonly SEED_SCRIPT="${PROJECT_DIRECTORY}/backend/scripts/seed-keycloak-users.sh"

diff --unified \
  "${KEYCLOAK_GUEST_CATALOG}" \
  <(tail -n +2 "${BACKEND_GUEST_CATALOG}")

mapfile -t guest_emails < <(
  awk -F '\t' '$1 !~ /^#/ && $1 != "" { print $1 }' "${BACKEND_GUEST_CATALOG}"
)
readonly -a expected_guest_emails=(
  guest00@example.com
  guest01@example.com
  guest02@example.com
  guest03@example.com
)
[[ "${guest_emails[*]}" == "${expected_guest_emails[*]}" ]] || {
  echo "The staging Guest catalog must contain exactly guest00@example.com through guest03@example.com." >&2
  exit 1
}

grep -Fq 'COPY seed/development-users.tsv /app/development-users.tsv' "${SEED_DOCKERFILE}"
grep -Fq 'COPY seed/guest-users.tsv /app/guest-users.tsv' "${SEED_DOCKERFILE}"
grep -Fq ': "${GUEST_SEED_PASSWORD:?GUEST_SEED_PASSWORD is required}"' "${SEED_SCRIPT}"
if grep -Eq 'GUEST_SEED_PASSWORD=.*DEV_SEED_PASSWORD|GUEST_SEED_PASSWORD:-?\$\{?DEV_SEED_PASSWORD' \
  "${SEED_SCRIPT}"; then
  echo "Guest seed password must not fall back to the development seed password." >&2
  exit 1
fi

allowed_external_emails_variable="$(sed -n \
  '/variable "allowed_external_emails" {/,/^}/p' "${STACK_VARIABLES_FILE}")"
grep -Fq 'type    = list(string)' <<<"${allowed_external_emails_variable}"
grep -Fq 'default = []' <<<"${allowed_external_emails_variable}"

staging_allowlist_block="$(awk '
  /allowed_external_emails[[:space:]]*=[[:space:]]*\[/ { in_list = 1 }
  in_list { print }
  in_list && /^[[:space:]]*\]/ { exit }
' "${STAGING_MAIN_FILE}")"
for guest_email in "${expected_guest_emails[@]}"; do
  grep -Fq "\"${guest_email}\"," <<<"${staging_allowlist_block}"
done
[[ "$(grep -Ec 'guest[0-9]+@example\.com' <<<"${staging_allowlist_block}")" == '4' ]]

backend_module_block="$(sed -n '/module "backend" {/,/^}/p' "${STACK_FILE}")"
grep -Eq 'ALLOWED_EXTERNAL_EMAILS[[:space:]]*=[[:space:]]*join\(",", var.allowed_external_emails\)' \
  <<<"${backend_module_block}"
staging_backend_block="$(sed -n '/var.environment == "staging" ? {/,/} : {},/p' \
  <<<"${backend_module_block}")"
grep -Eq 'ALLOWED_EXTERNAL_EMAILS[[:space:]]*=[[:space:]]*join\(",", var.allowed_external_emails\)' \
  <<<"${staging_backend_block}"

manual_seed_block="$(sed -n '/resource "azurerm_container_app_job" "manual_seed"/,$p' \
  "${STACK_FILE}")"
for required_value in \
  'name                = "guest-seed-password"' \
  'key_vault_secret_id = "${module.key_vault.vault_uri}secrets/guest-seed-password"' \
  'name        = "GUEST_SEED_PASSWORD"' \
  'secret_name = "guest-seed-password"' \
  'name  = "ALLOWED_EMAIL_DOMAIN"' \
  'name  = "ALLOWED_EXTERNAL_EMAILS"'; do
  grep -Fq "${required_value}" <<<"${manual_seed_block}"
done

grep -Fq 'manual_seed_jobs = var.provision_workloads && var.environment == "staging"' \
  "${STACK_FILE}"
if grep -R -Eq 'guest0[0-3]@example\.com|guest-seed-password|GUEST_SEED_PASSWORD|allowed_external_emails' \
  "${PRODUCTION_DIRECTORY}"; then
  echo "Production Terraform root must not opt in to staging Guest identity configuration." >&2
  exit 1
fi
if grep -Eq 'guest0[0-3]@example\.com|guest-seed-password|GUEST_SEED_PASSWORD|ALLOWED_EXTERNAL_EMAILS' \
  "${PRODUCTION_WORKFLOW}"; then
  echo "Production deployment workflow must not contain staging Guest identity configuration." >&2
  exit 1
fi
if grep -R -Fq 'resource "azurerm_key_vault_secret"' "${PROJECT_DIRECTORY}/infra"; then
  echo "Terraform must not manage Key Vault secret values." >&2
  exit 1
fi
if grep -Eq 'job-ewf-stg-seed-(guest|external)' "${STACK_FILE}"; then
  echo "Guest identity integration must not add a dedicated Container Apps Job." >&2
  exit 1
fi
if grep -Eq '(module|resource) "[^"]*guest[^"]*"' "${STACK_FILE}"; then
  echo "Guest identity integration must not add a dedicated Azure identity or resource." >&2
  exit 1
fi

echo "Staging Guest identity infrastructure boundaries are valid."
