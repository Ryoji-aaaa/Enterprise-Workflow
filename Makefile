SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

COMPOSE := docker compose

.PHONY: help setup init build up down restart logs ps clean reset \
	test test-backend test-frontend test-e2e verify render-keycloak-config \
	terraform-check

help: ## Show available targets
	@awk 'BEGIN {FS = ":.*## "; printf "Usage: make <target>\n\nTargets:\n"} /^[a-zA-Z0-9_-]+:.*## / {printf "  %-16s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

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

test: test-backend test-frontend test-e2e ## Run all automated tests

test-backend: ## Run Spring Boot tests
	@./scripts/test-backend.sh

test-frontend: ## Run frontend lint and type checks
	@./scripts/test-frontend.sh

test-e2e: ## Run Playwright end-to-end tests
	@./scripts/test-e2e.sh

verify: ## Verify service readiness
	./scripts/verify.sh

render-keycloak-config: ## Render local Keycloak realm configuration
	./keycloak/scripts/initialize-keycloak.sh render

terraform-check: ## Format-check and validate all Terraform roots
	terraform fmt -check -recursive infra
	./scripts/check-backend-probes.sh
	./scripts/check-backend-internal-url.sh
	./scripts/check-manual-seed-job-names.sh
	terraform -chdir=infra/bootstrap init -backend=false
	terraform -chdir=infra/bootstrap validate
	terraform -chdir=infra/environments/staging init -backend=false
	terraform -chdir=infra/environments/staging validate
	terraform -chdir=infra/environments/production init -backend=false
	terraform -chdir=infra/environments/production validate
