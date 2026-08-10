run_keycloak_suite() {
  run_phase keycloak test manual-seed-contract TEST \
    "Keycloak / manual seed contract" failed \
    "logs/keycloak/manual-seed-contract.log" \
    bash "${PROJECT_DIRECTORY}/backend/scripts/test-seed-keycloak-users.sh"
  run_phase keycloak test contracts TEST \
    "Keycloak / contract tests" failed \
    "logs/keycloak/contracts.log" \
    compose run --rm --no-deps keycloak-init \
      /opt/workflow/check-keycloak.sh \
        --format ndjson \
        --output /test-results/raw/cases/keycloak.ndjson
  print_suite_result keycloak
}
