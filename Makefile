SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

COMPOSE := docker compose

.PHONY: help setup init build up down restart logs ps clean reset \
	test test-backend test-frontend test-e2e verify verify-infra \
	audit audit-frontend audit-e2e render-keycloak-config terraform-check

help: ## Show available targets
	@awk 'BEGIN {FS = ":.*## "; printf "Usage: make <target>\n\nTargets:\n"} /^[a-zA-Z0-9_-]+:.*## / {printf "  %-24s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

setup: ## Prepare local configuration and validate host dependencies
	@./scripts/setup.sh

init: setup ## Build and initialize the complete development environment
	@./scripts/init.sh

build: ## Build application container images
	$(COMPOSE) build

up: setup ## Start services and wait for their health checks
	@./scripts/up.sh

down: ## Stop services
	$(COMPOSE) down --remove-orphans

restart: down up ## Restart services

logs: ## Follow service logs
	$(COMPOSE) logs --follow --tail=200

ps: ## Show service status
	$(COMPOSE) ps

clean: ## Stop services and remove generated build/test artifacts
	@./scripts/clean.sh

reset: setup ## Recreate development volumes and initialize all services
	@./scripts/reset.sh

test: test-backend test-frontend test-e2e ## Run all backend, frontend, and end-to-end tests

test-backend: ## Run backend tests and PostgreSQL migration checks
	@./scripts/test-backend.sh

test-frontend: ## Run frontend lint, type checks, unit tests, and production build
	@./scripts/test-frontend.sh

test-e2e: ## Prepare the environment and run Playwright end-to-end tests
	@./scripts/test-e2e.sh

verify: ## Verify the running local environment and architecture boundaries
	@./scripts/verify.sh

verify-infra: ## Validate Terraform and infrastructure configuration
	@./scripts/verify-infra.sh

audit: audit-frontend audit-e2e ## Audit all production npm dependencies

audit-frontend: ## Audit frontend production npm dependencies
	@./scripts/audit-dependencies.sh frontend

audit-e2e: ## Audit E2E production npm dependencies
	@./scripts/audit-dependencies.sh e2e

render-keycloak-config: ## Render local Keycloak realm configuration
	./keycloak/scripts/initialize-keycloak.sh render

terraform-check: ## Deprecated alias for verify-infra
	@printf '%s\n' "[WARN] 'make terraform-check' is deprecated. Use 'make verify-infra'."
	@$(MAKE) --no-print-directory verify-infra
