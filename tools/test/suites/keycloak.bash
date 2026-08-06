run_keycloak_suite() {
  run_phase keycloak test contracts TEST \
    "Keycloak / contract tests" failed \
    "logs/keycloak/contracts.log" \
    compose run --rm --no-deps keycloak-init \
      /opt/workflow/check-keycloak.sh \
        --format ndjson \
        --output /test-results/raw/cases/keycloak.ndjson
  print_suite_result keycloak
}
