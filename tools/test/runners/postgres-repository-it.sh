#!/usr/bin/env bash

set -Eeuo pipefail

readonly FIXTURE_PATH="${TEST_RUN_DIRECTORY:?}/raw/fixtures/postgresql-repository-it.dump"
readonly RESULT_DIRECTORY="${TEST_RUN_DIRECTORY}/raw/junit/backend/postgresql"
readonly BACKEND_TEST_IMAGE="${BACKEND_TEST_IMAGE:?}"
readonly POSTGRES_IMAGE="postgres:${POSTGRES_VERSION:-18.4}"
readonly RUNNER_SUFFIX="$$"
readonly NETWORK_NAME="workflow-postgresql-it-network-${RUNNER_SUFFIX}"
readonly POSTGRES_CONTAINER="workflow-postgresql-it-postgres-${RUNNER_SUFFIX}"
readonly DATABASE_PASSWORD="repository-it-password"

cleanup() {
  docker rm --force "${POSTGRES_CONTAINER}" >/dev/null 2>&1 || true
  docker network rm "${NETWORK_NAME}" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

runner_error() {
  printf 'PostgreSQL repository IT runner error: %s\n' "$*" >&2
  exit 2
}

[[ -s "${FIXTURE_PATH}" ]] || runner_error "fixture is missing: ${FIXTURE_PATH}"
mkdir -p "${RESULT_DIRECTORY}"

docker network create "${NETWORK_NAME}" >/dev/null \
  || runner_error "could not create the dedicated Docker network"
docker run --detach --rm \
  --name "${POSTGRES_CONTAINER}" \
  --network "${NETWORK_NAME}" \
  --env "POSTGRES_PASSWORD=${DATABASE_PASSWORD}" \
  "${POSTGRES_IMAGE}" >/dev/null \
  || runner_error "could not start PostgreSQL"

postgres_ready=0
for _ in $(seq 1 60); do
  if docker exec "${POSTGRES_CONTAINER}" pg_isready --username postgres --dbname postgres \
      >/dev/null 2>&1; then
    postgres_ready=1
    break
  fi
  sleep 1
done
((postgres_ready == 1)) || runner_error "PostgreSQL did not become ready"

docker exec --interactive "${POSTGRES_CONTAINER}" \
  psql --username postgres --dbname postgres --set ON_ERROR_STOP=1 <<SQL >/dev/null \
  || runner_error "could not create the workflow role and database"
CREATE ROLE workflow LOGIN PASSWORD '${DATABASE_PASSWORD}';
CREATE DATABASE workflow_repository_it OWNER workflow;
SQL

docker exec --interactive \
  --env "PGPASSWORD=${DATABASE_PASSWORD}" \
  "${POSTGRES_CONTAINER}" \
  pg_restore --host 127.0.0.1 --username workflow --dbname workflow_repository_it \
    --no-owner --no-privileges --exit-on-error \
  <"${FIXTURE_PATH}" \
  || runner_error "could not restore the PostgreSQL repository fixture"

set +e
docker run --rm \
  --network "${NETWORK_NAME}" \
  --user "${TEST_UID:-1000}:${TEST_GID:-1000}" \
  --volume "${TEST_RUN_DIRECTORY}:/test-results" \
  --env "POSTGRES_TEST_URL=jdbc:postgresql://${POSTGRES_CONTAINER}:5432/workflow_repository_it" \
  --env "POSTGRES_TEST_USERNAME=workflow" \
  --env "POSTGRES_TEST_PASSWORD=${DATABASE_PASSWORD}" \
  "${BACKEND_TEST_IMAGE}" \
  mvn --batch-mode --no-transfer-progress \
    -Dtest=PostgreSqlRepositoryIT \
    -Dsurefire.reportsDirectory=/test-results/raw/junit/backend/postgresql \
    test
maven_status=$?
set -e

mapfile -t result_files < <(find "${RESULT_DIRECTORY}" -maxdepth 1 -type f -name '*.xml' -print)
((${#result_files[@]} > 0)) || runner_error "JUnit XML was not produced"

if ((maven_status == 0)); then
  exit 0
fi
if grep -Eq '<(failure|error)([[:space:]>])' "${result_files[@]}"; then
  exit 1
fi
runner_error "Maven exited ${maven_status} without a failing structured test case"
