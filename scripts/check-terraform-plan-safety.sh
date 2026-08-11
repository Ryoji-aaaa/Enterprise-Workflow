#!/usr/bin/env bash

set -Eeuo pipefail

usage() {
  echo "Usage: $0 <terraform-plan-json> [--allow-delete <exact-resource-address>]" >&2
}

if (($# < 1)); then
  usage
  exit 2
fi

readonly PLAN_JSON="$1"
shift

if [[ ! -r "${PLAN_JSON}" ]]; then
  echo "Terraform plan JSON is not readable: ${PLAN_JSON}" >&2
  exit 2
fi

command -v jq >/dev/null || {
  echo "jq is required to check Terraform plan safety." >&2
  exit 2
}

declare -a allowed_delete_addresses=()
while (($# > 0)); do
  case "$1" in
    --allow-delete)
      if (($# < 2)); then
        usage
        exit 2
      fi
      allowed_delete_addresses+=("$2")
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 2
      ;;
  esac
done

readonly -a protected_resource_types=(
  azurerm_cognitive_account
  azurerm_storage_account
  azurerm_storage_container
  azurerm_container_app_environment
  azurerm_virtual_network
  azurerm_subnet
  azurerm_private_endpoint
  azurerm_private_dns_zone
  azurerm_postgresql_flexible_server
  azurerm_key_vault
)

is_allowed_delete() {
  local address="$1"
  local allowed_address

  for allowed_address in "${allowed_delete_addresses[@]}"; do
    if [[ "${address}" == "${allowed_address}" ]]; then
      return 0
    fi
  done

  return 1
}

is_protected_resource_type() {
  local resource_type="$1"
  local protected_type

  for protected_type in "${protected_resource_types[@]}"; do
    if [[ "${resource_type}" == "${protected_type}" ]]; then
      return 0
    fi
  done

  return 1
}

mapfile -t destructive_changes < <(
  jq -r '
    .resource_changes[]?
    | select(.mode == "managed")
    | select(.change.actions | index("delete"))
    | [.address, .type, (.change.actions | join(","))]
    | @tsv
  ' "${PLAN_JSON}"
)

if ((${#destructive_changes[@]} == 0)); then
  echo "Terraform plan safety check passed: no delete or replacement actions."
  exit 0
fi

failures=0
for destructive_change in "${destructive_changes[@]}"; do
  IFS=$'\t' read -r address resource_type actions <<<"${destructive_change}"

  if is_allowed_delete "${address}"; then
    echo "Terraform plan safety check allowed exact temporary deletion: ${address} (${actions})."
    continue
  fi

  if is_protected_resource_type "${resource_type}"; then
    classification="protected infrastructure"
  else
    classification="managed resource"
  fi

  echo "Terraform plan safety check rejected ${classification} ${address} (${resource_type}: ${actions})." >&2
  failures=1
done

if ((failures != 0)); then
  echo "Terraform plan contains delete or replacement actions outside the exact-address allowlist." >&2
  exit 1
fi

echo "Terraform plan safety check passed."
