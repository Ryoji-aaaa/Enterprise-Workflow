run_frontend_suite() {
  run_phase frontend check image-build BUILD \
    "Frontend / test image" failed \
    "logs/frontend/image-build.log" \
    compose build frontend-test
  local image_status="${LAST_PHASE_STATUS}"

  if [[ "${image_status}" == "passed" ]]; then
    run_phase frontend check lint CHECK \
      "Frontend / lint" failed \
      "logs/frontend/lint.log" \
      compose run --rm --no-deps frontend-test npm run lint

    run_phase frontend check typecheck CHECK \
      "Frontend / typecheck" failed \
      "logs/frontend/typecheck.log" \
      compose run --rm --no-deps frontend-test npm run typecheck

    run_phase frontend test unit TEST \
      "Frontend / unit" failed \
      "logs/frontend/unit.log" \
      compose run --rm --no-deps frontend-test sh -c \
        'node --experimental-strip-types --test --test-reporter=junit src/**/*.test.ts > /test-results/raw/junit/frontend/junit.xml'

    run_phase frontend check production-build BUILD \
      "Frontend / production build" failed \
      "logs/frontend/production-build.log" \
      compose run --rm --no-deps frontend-test npm run build
  else
    record_skipped_check frontend lint "Frontend test image could not be built"
    record_skipped_check frontend typecheck "Frontend test image could not be built"
    mark_suite_error frontend unit "Frontend test image could not be built"
    record_skipped_check frontend production-build "Frontend test image could not be built"
  fi

  print_suite_result frontend
}
