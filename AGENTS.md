# AGENTS.md

## Repository Purpose

This repository contains an enterprise workflow application prototype. It is also a
modern web application development exercise and a DevOps and Git workflow training
environment.

The main technology stack is:

- Frontend: Next.js
- Backend: Spring Boot
- Database: PostgreSQL
- Authentication: Keycloak, with a future migration to Microsoft Entra ID
- Infrastructure: Azure Container Apps and Azure Database for PostgreSQL
- CI/CD: GitHub Actions and Terraform

This file is the entry point for Codex. Detailed specifications and operational
procedures remain in the existing documents under `docs/`.

## Required Reading

Before changing anything, read:

1. This `AGENTS.md`.
2. The repository [`README.md`](README.md).
3. The documentation index at [`docs/README.md`](docs/README.md).
4. The documents related to the requested change.
5. Existing tests next to, or covering, the affected code.

Use these existing documents as the primary references for specific changes:

- Database or Flyway: [`docs/backend/flyway.md`](docs/backend/flyway.md) and
  [`docs/operations/database-migration.md`](docs/operations/database-migration.md)
- Users, organizations, roles, and permissions:
  [`docs/backend/user-management.md`](docs/backend/user-management.md),
  [`docs/backend/organization-management.md`](docs/backend/organization-management.md),
  [`docs/backend/authorization.md`](docs/backend/authorization.md), and
  [`docs/backend/development-seed-data.md`](docs/backend/development-seed-data.md)
- Authentication or Keycloak:
  [`docs/architecture/authentication-flow.md`](docs/architecture/authentication-flow.md),
  [`docs/frontend/nextjs-better-auth.md`](docs/frontend/nextjs-better-auth.md),
  [`docs/infrastructure/keycloak.md`](docs/infrastructure/keycloak.md), and
  [`docs/infrastructure/keycloak-azure.md`](docs/infrastructure/keycloak-azure.md)
- Audit logging: [`docs/backend/audit-logging.md`](docs/backend/audit-logging.md)
- Azure, Terraform, or GitHub Actions:
  [`docs/infrastructure/azure-architecture.md`](docs/infrastructure/azure-architecture.md),
  [`docs/infrastructure/terraform.md`](docs/infrastructure/terraform.md),
  [`docs/infrastructure/github-actions.md`](docs/infrastructure/github-actions.md), and
  [`docs/operations/deployment.md`](docs/operations/deployment.md)
- Frontend organization chart or user management:
  [`docs/frontend/organization-chart.md`](docs/frontend/organization-chart.md),
  [`docs/frontend/user-management.md`](docs/frontend/user-management.md), and
  [`docs/testing/playwright.md`](docs/testing/playwright.md)

## Architecture Invariants

- Browser code must not call Spring Boot directly. Browser-to-backend access must go
  through Next.js server-side code or Route Handlers.
- Only Spring Boot may access the workflow PostgreSQL database. Frontend code must not
  receive database credentials or implement database access.
- Keycloak authenticates users. It is not the source of application authorization.
- Application roles and permissions are stored and evaluated in PostgreSQL.
- Access tokens, refresh tokens, and ID tokens must not be exposed to browser
  JavaScript.
- Email is read-only in the application because Microsoft Entra ID will become its
  source of truth.
- Frontend permission checks are UI controls only. Backend authorization is mandatory,
  including for direct URL and API access.
- Administrative changes to users, organizations, roles, permissions, account status,
  and assignments must follow the existing audit policy.
- Development seed data must not run automatically in staging or production.
- Azure resources are managed by Terraform.

## Investigation Before Changes

- Inspect the current branch, working tree, and latest available `main` before editing.
- Search for existing implementations before adding a file, API, entity, table,
  configuration value, or Azure resource.
- Confirm the latest migration in `backend/src/main/resources/db/migration/` before
  creating a Flyway migration.
- Confirm existing API naming, error responses, authorization, transaction, and audit
  patterns.
- Inspect nearby unit, integration, and end-to-end tests before selecting an approach.
- Do not assume that a class, endpoint, table, role, permission, environment variable,
  Container Apps Job, or Azure resource exists.
- Before coding, report the documents read, existing implementation inspected,
  constraints found, and planned changes.

## Development Rules

### Backend

- Preserve controller, service, repository, and domain responsibilities.
- Enforce authorization in the Backend and reuse the existing permission and role
  mechanisms.
- Record audit logs where required and preserve the existing transaction boundary
  between a successful state change, its history, and its audit record.
- Use optimistic locking where editable resources already use `version`.
- Avoid N+1 queries and follow the query-count patterns used by nearby tests.
- Keep response DTOs, validation, status codes, and error handling consistent with
  existing endpoints.
- Do not add direct database access to Frontend code.

### Frontend

- Use the existing BFF and server-side Backend access pattern.
- Never expose access tokens to Client Components, browser storage, or browser-visible
  responses.
- Permission-based UI must fail closed. Backend authorization must still reject direct
  URL or API access.
- Preserve existing responsive layout and mobile navigation behavior.
- Implement applicable loading, empty, forbidden, conflict, and generic error states.
- Reuse existing components and styling conventions.

### Database and Flyway

- Never modify, rename, or delete an already applied Flyway migration.
- Add a new versioned migration for schema or shared master-data changes.
- Update the JPA model and migration in the same change.
- Verify both a fresh migration and an upgrade migration.
- Do not manually alter a shared staging or production database unless an approved
  runbook explicitly requires it.
- Do not use `flyway repair` without a dedicated reviewed recovery plan.
- Never reset a shared environment.
- Plan destructive changes with an expand-contract sequence.
- Always inspect the current latest migration; do not rely on a version number copied
  into this file.

### Authentication and Authorization

- Keycloak authenticates users; PostgreSQL roles and permissions authorize business
  operations.
- Do not use Keycloak realm or client roles as application authorization.
- Email is not editable by the application, and the design must keep the future Entra
  ID migration possible.
- Authorization changes require Backend tests and Frontend visibility tests.
- Organization chart access requires both the database permission and an allowed
  employment type.

### Audit Logging

- Administrative mutations require audit records.
- Successful state changes and their audit records must follow the existing transaction
  policy.
- Authentication tokens, passwords, client secrets, cookies, and authorization headers
  must never be logged.
- Authorization failures must follow the existing audit policy.

### Development and Staging Seed Data

- Development initializers run only with the `development` profile and only when
  explicitly enabled.
- Normal staging and production Backend revisions keep seed disabled.
- Staging sample data is inserted only through the manual Container Apps Jobs
  `job-ewf-stg-seed-db`, `job-ewf-stg-seed-kc`, and `job-ewf-stg-seed-all`.
- Production manual seed is prohibited, and production must not create seed Jobs.
- Seed processing must remain idempotent.
- Database and Keycloak seed operations are separate systems, not one distributed
  transaction.
- Follow [`docs/backend/development-seed-data.md`](docs/backend/development-seed-data.md)
  for the detailed execution procedure.

### Azure and Terraform

- Terraform is the source of truth for Azure resources.
- Do not make persistent manual Azure configuration changes unless a documented runbook
  requires them.
- Create or update Key Vault secret values manually only when the documented deployment
  procedure requires it. Never place secret values in Terraform state, Git, logs, pull
  requests, or documentation.
- Staging-only and production-only resources require explicit environment guards.
- Use immutable full commit-SHA image tags.
- When the deployment policy requires promotion, production must use the tested image;
  do not rebuild a different artifact.
- Record all required manual deployment steps in existing documentation.
- `CONTRACT_LEGACY_USER_COLUMNS` must remain `true` after V007 has been applied to an
  environment. Follow the Flyway and deployment documents for the transition procedure.

## Testing Requirements

Run the smallest relevant set during development and the full required set before
completion:

- Backend changes: `make test-backend`
- Frontend changes: `make test-frontend`
- User flow, authentication, authorization, or navigation changes: `make test-e2e`
- Cross-cutting, database, authentication, or infrastructure changes: `make test` and
  `make verify`
- Terraform changes: `make terraform-check`
- All changes: `git diff --check`

Use additional existing checks invoked by the affected scripts or workflows. Do not
claim that a test passed unless it was actually executed. If a required test cannot run,
report the reason and the resulting risk.

## Git and Pull Request Rules

- Do not commit directly to `main`.
- Keep one pull request focused on one purpose and do not mix unrelated refactoring.
- Inspect `git status` and the complete diff before staging.
- Stage only intended files.
- Do not commit, push, open or update a pull request, or merge unless explicitly
  requested.
- Do not rewrite shared history.

## Prohibited Actions

- Do not expose Spring Boot directly to browsers.
- Do not expose database access or database credentials to Frontend code.
- Do not store business permissions in Keycloak roles.
- Do not edit an applied Flyway migration or reset a shared database.
- Do not enable development seed in normal staging or production Backend revisions.
- Do not run manual seed in production.
- Do not set `CONTRACT_LEGACY_USER_COLUMNS` back to `false` after V007 is applied.
- Do not store secrets in source code, logs, pull request descriptions, or
  documentation.
- Do not treat hidden Frontend controls as authorization.
- Do not claim tests passed unless they were executed successfully.

## Documentation Rules

- Write `AGENTS.md` in English.
- Write project documentation under `docs/` in Japanese.
- Code identifiers, commands, API paths, and configuration names may remain in English.
- Update an existing document instead of creating a duplicate document.
- Describe implemented and verified behavior, not planned or assumed behavior.
- When behavior and documentation conflict, inspect implementation and tests before
  updating the document.
- Keep detailed specifications in `docs/`; this file should remain an entry point and a
  list of repository-wide constraints.

## Completion Report

Use the following headings in the completion report:

```markdown
## Summary

## Documents Read

## Existing Implementation Inspected

## Changed Files

## Design Decisions

## Database and Migration Impact

## Authentication, Authorization, and Audit Impact

## Azure and Deployment Impact

## Tests Executed

## Test Results

## Documentation Updated

## Remaining Risks or Follow-up Items
```

State why any required test was not executed.
