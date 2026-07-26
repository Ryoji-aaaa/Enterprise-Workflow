#!/usr/bin/env bash

set -Eeuo pipefail

required_variables=(
  WORKFLOW_DB_NAME
  WORKFLOW_DB_USER
  WORKFLOW_DB_PASSWORD
  KEYCLOAK_DB_NAME
  KEYCLOAK_DB_USER
  KEYCLOAK_DB_PASSWORD
)

for variable_name in "${required_variables[@]}"; do
  [[ -n "${!variable_name:-}" ]] || {
    echo "Required variable ${variable_name} is not set." >&2
    exit 1
  }
done

for identifier in \
  "${WORKFLOW_DB_NAME}" \
  "${WORKFLOW_DB_USER}" \
  "${KEYCLOAK_DB_NAME}" \
  "${KEYCLOAK_DB_USER}"; do
  [[ "${identifier}" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]] || {
    echo "Unsafe PostgreSQL identifier: ${identifier}" >&2
    exit 1
  }
done

psql \
  --set ON_ERROR_STOP=1 \
  --username "${POSTGRES_USER}" \
  --dbname postgres \
  --set "workflow_database=${WORKFLOW_DB_NAME}" \
  --set "workflow_user=${WORKFLOW_DB_USER}" \
  --set "workflow_password=${WORKFLOW_DB_PASSWORD}" \
  --set "keycloak_database=${KEYCLOAK_DB_NAME}" \
  --set "keycloak_user=${KEYCLOAK_DB_USER}" \
  --set "keycloak_password=${KEYCLOAK_DB_PASSWORD}" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'workflow_user', :'workflow_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'workflow_user')
\gexec

SELECT format(
  'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
  :'workflow_user',
  :'workflow_password'
)
\gexec

SELECT format('CREATE DATABASE %I OWNER %I', :'workflow_database', :'workflow_user')
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'workflow_database')
\gexec

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'keycloak_user', :'keycloak_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'keycloak_user')
\gexec

SELECT format(
  'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
  :'keycloak_user',
  :'keycloak_password'
)
\gexec

SELECT format('CREATE DATABASE %I OWNER %I', :'keycloak_database', :'keycloak_user')
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'keycloak_database')
\gexec

SELECT format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC', :'workflow_database')
\gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'workflow_database', :'workflow_user')
\gexec

SELECT format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC', :'keycloak_database')
\gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'keycloak_database', :'keycloak_user')
\gexec
SQL
