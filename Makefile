SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

COMPOSE := docker compose

.PHONY: help setup init build up down restart logs ps clean reset \
	test test-backend test-frontend test-e2e verify keycloak-config \
	phase1-check phase2-check phase3-check phase4-check phase5-check

help: ## Show available targets
	@awk 'BEGIN {FS = ":.*## "; printf "Usage: make <target>\n\nTargets:\n"} /^[a-zA-Z0-9_-]+:.*## / {printf "  %-16s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

setup: ## Prepare local configuration and validate host dependencies
	@command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }
	@docker compose version >/dev/null
	@command -v git >/dev/null || { echo "git is required" >&2; exit 1; }
	@test -f .env || { cp .env.example .env; echo "Created .env from .env.example"; }
	@chmod +x scripts/*.sh
	@if grep -q 'replace-with-' .env; then \
		echo "WARNING: Replace placeholder secrets in .env before starting services."; \
	fi

init: setup build up verify ## Build and initialize the complete development environment

build: ## Build application container images
	$(COMPOSE) build

up: keycloak-config ## Start services and wait for their health checks
	$(COMPOSE) up -d --wait

down: ## Stop services
	$(COMPOSE) down --remove-orphans

restart: down up ## Restart services

logs: ## Follow service logs
	$(COMPOSE) logs --follow --tail=200

ps: ## Show service status
	$(COMPOSE) ps

clean: ## Stop services and remove generated build/test artifacts
	$(COMPOSE) down --remove-orphans
	@find frontend -maxdepth 1 -type d \( -name .next -o -name node_modules \) -prune -exec rm -rf -- {} +
	@find backend -maxdepth 1 -type d -name target -prune -exec rm -rf -- {} +
	@find tests/e2e -maxdepth 1 -type d \( -name playwright-report -o -name test-results \) -prune -exec rm -rf -- {} +

reset: ## Recreate prototype volumes and initialize all services
	@echo "WARNING: Removing prototype development volumes and data."
	$(COMPOSE) down --volumes --remove-orphans
	$(MAKE) init

test: test-backend test-frontend test-e2e ## Run all automated tests

test-backend: ## Run Spring Boot tests
	docker build \
		--build-arg JAVA_VERSION=$${JAVA_VERSION:-21} \
		--build-arg MAVEN_VERSION=$${MAVEN_VERSION:-3.9.16} \
		--build-arg TEST_RUN_ID=$$(date +%s%N) \
		--target test \
		--tag workflow-backend-test \
		backend

test-frontend: ## Run frontend lint and type checks
	$(COMPOSE) run --rm frontend npm run check

test-e2e: ## Run Playwright end-to-end tests
	$(COMPOSE) run --rm e2e

verify: ## Verify service readiness
	./scripts/verify.sh

keycloak-config: ## Render local Keycloak realm configuration
	./keycloak/scripts/initialize-keycloak.sh render

phase1-check: ## Validate the Phase 1 repository skeleton
	@test -f .env.example
	@test -f docker-compose.yml
	@test -f README.md
	@$(COMPOSE) --env-file .env.example config --quiet

phase2-check: ## Start and verify PostgreSQL and Mailpit
	$(COMPOSE) up -d --wait postgres mailpit
	./scripts/verify.sh postgres mailpit

phase3-check: keycloak-config ## Start and verify Keycloak realm configuration
	./scripts/phase3-check.sh

phase4-check: test-backend keycloak-config ## Test and verify the Spring Boot business API
	$(COMPOSE) build backend
	$(COMPOSE) up -d --wait postgres mailpit keycloak backend
	./scripts/verify.sh postgres mailpit keycloak backend

phase5-check: ## Test the database-less Better Auth and BFF login flow
	./scripts/phase5-check.sh
