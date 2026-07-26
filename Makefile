SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

COMPOSE := docker compose

.PHONY: help setup init build up down restart logs ps clean reset \
	test test-backend test-frontend test-e2e verify keycloak-config \
	phase1-check phase2-check phase3-check phase4-check phase5-check

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

reset: setup ## Recreate prototype volumes and initialize all services
	@./scripts/reset.sh

test: test-backend test-frontend test-e2e ## Run all automated tests

test-backend: ## Run Spring Boot tests
	@./scripts/test-backend.sh

test-frontend: ## Run frontend lint and type checks
	@./scripts/test-frontend.sh

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
