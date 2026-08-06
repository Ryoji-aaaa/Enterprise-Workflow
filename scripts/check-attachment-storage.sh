#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly STACK_FILE="${PROJECT_DIRECTORY}/infra/modules/environment-stack/main.tf"
readonly STORAGE_FILE="${PROJECT_DIRECTORY}/infra/modules/blob-storage/main.tf"
readonly CONTAINER_APP_FILE="${PROJECT_DIRECTORY}/infra/modules/container-app/main.tf"
readonly TERRAFORM_PLAN_WORKFLOW="${PROJECT_DIRECTORY}/.github/workflows/terraform-plan.yml"

grep -Fq 'shared_access_key_enabled       = false' "${STORAGE_FILE}"
grep -Fq 'allow_nested_items_to_be_public = false' "${STORAGE_FILE}"
grep -Fq 'container_access_type = "private"' "${STORAGE_FILE}"
grep -Fq 'soft_delete_retention_days = 30' "${STACK_FILE}"
grep -Fq 'role_definition_name = "Storage Blob Data Contributor"' "${STACK_FILE}"
grep -Fq 'scope                = module.attachment_storage.container_scope' "${STACK_FILE}"
grep -Fq 'additional_identity_ids      = [module.backend_blob_identity.id]' "${STACK_FILE}"
grep -Fq 'AZURE_STORAGE_BLOB_ENDPOINT' "${STACK_FILE}"
grep -Fq 'AZURE_CLIENT_ID' "${STACK_FILE}"
grep -Fq 'ATTACHMENT_STORAGE_CREATE_CONTAINER = "false"' "${STACK_FILE}"
grep -Fq 'identity_ids = concat([var.identity_id], tolist(var.additional_identity_ids))' \
  "${CONTAINER_APP_FILE}"
grep -Fq \
  'TF_VAR_attachment_storage_account_name: ${{ vars.AZURE_ATTACHMENT_STORAGE_ACCOUNT_NAME }}' \
  "${TERRAFORM_PLAN_WORKFLOW}"
[[ "$(grep -Fc 'TF_VAR_attachment_storage_account_name' "${TERRAFORM_PLAN_WORKFLOW}")" == "2" ]]
grep -Fq 'target_environment: staging' "${TERRAFORM_PLAN_WORKFLOW}"
grep -Fq 'target_environment: production' "${TERRAFORM_PLAN_WORKFLOW}"

if grep -Fq 'stewfattach' "${TERRAFORM_PLAN_WORKFLOW}"; then
  echo "Terraform plan workflow must not contain a fixed attachment Storage Account name." >&2
  exit 1
fi

[[ "$(grep -Fc 'additional_identity_ids      = [module.backend_blob_identity.id]' \
  "${STACK_FILE}")" == "1" ]]
[[ "$(grep -Fc 'role_definition_name = "Storage Blob Data Contributor"' \
  "${STACK_FILE}")" == "1" ]]

for container_app_module in keycloak frontend; do
  module_block="$(sed -n "/module \"${container_app_module}\" {/,/^}/p" "${STACK_FILE}")"
  if grep -Fq 'backend_blob_identity' <<<"${module_block}"; then
    echo "${container_app_module} must not receive the Backend Blob identity." >&2
    exit 1
  fi
done

if grep -Eq 'AZURE_STORAGE_CONNECTION_STRING|storage[_ -]?key|shared[_ -]?key' "${STACK_FILE}"; then
  echo "Azure Backend configuration must not contain Blob connection strings or storage keys." >&2
  exit 1
fi

echo "Expense attachment Blob Storage and Backend-only identity configuration are valid."
