data "azurerm_client_config" "current" {}

data "azurerm_resource_group" "this" {
  name = var.resource_group_name
}

module "registry" {
  source = "../container-registry"

  name                = var.acr_name
  resource_group_name = var.acr_resource_group_name
}

module "monitoring" {
  source = "../monitoring"

  name                = "log-enterprise-workflow-${var.environment}"
  location            = var.location
  resource_group_name = data.azurerm_resource_group.this.name
}

module "container_app_environment" {
  source = "../container-app-environment"

  name                           = var.container_app_environment_name
  location                       = var.location
  resource_group_name            = data.azurerm_resource_group.this.name
  log_analytics_workspace_id     = module.monitoring.id
  vnet_name                      = "vnet-enterprise-workflow-${var.environment}"
  vnet_address_space             = var.vnet_address_space
  infrastructure_subnet_prefixes = var.container_apps_subnet_prefixes
  postgres_subnet_prefixes       = var.postgres_subnet_prefixes
}

module "runtime_identity" {
  source = "../identity"

  name                = "uami-enterprise-workflow-${var.environment}-runtime"
  location            = var.location
  resource_group_name = data.azurerm_resource_group.this.name
}

module "key_vault" {
  source = "../key-vault"

  name                 = var.key_vault_name
  location             = var.location
  resource_group_name  = data.azurerm_resource_group.this.name
  tenant_id            = data.azurerm_client_config.current.tenant_id
  runtime_principal_id = module.runtime_identity.principal_id
  github_principal_id  = var.github_identity_principal_id
}

resource "azurerm_role_assignment" "acr_pull" {
  scope                = module.registry.id
  role_definition_name = "AcrPull"
  principal_id         = module.runtime_identity.principal_id
}

module "postgres" {
  count  = var.provision_workloads ? 1 : 0
  source = "../postgres"

  name                   = var.postgres_server_name
  location               = var.location
  resource_group_name    = data.azurerm_resource_group.this.name
  administrator_login    = var.postgres_administrator_login
  administrator_password = var.postgres_administrator_password
  delegated_subnet_id    = module.container_app_environment.postgres_subnet_id
  private_dns_zone_id    = module.container_app_environment.postgres_private_dns_zone_id
}

locals {
  frontend_url    = "https://${var.frontend_container_app_name}.${module.container_app_environment.default_domain}"
  keycloak_url    = "https://${var.keycloak_container_app_name}.${module.container_app_environment.default_domain}"
  keycloak_issuer = "${local.keycloak_url}/realms/${var.keycloak_realm}"
}

module "backend" {
  count  = var.provision_workloads ? 1 : 0
  source = "../container-app"

  name                         = var.backend_container_app_name
  resource_group_name          = data.azurerm_resource_group.this.name
  container_app_environment_id = module.container_app_environment.id
  identity_id                  = module.runtime_identity.id
  registry_server              = module.registry.login_server
  image                        = "${module.registry.login_server}/enterprise-workflow-backend:${var.image_tag}"
  target_port                  = 8080
  external_enabled             = false
  key_vault_uri                = module.key_vault.vault_uri
  environment_variables = merge({
    SPRING_DATASOURCE_URL           = "jdbc:postgresql://${module.postgres[0].fqdn}:5432/workflow?sslmode=require"
    SPRING_DATASOURCE_USERNAME      = "workflow"
    KEYCLOAK_ISSUER                 = local.keycloak_issuer
    KEYCLOAK_INTERNAL_ISSUER        = local.keycloak_issuer
    KEYCLOAK_CLIENT_ID              = var.keycloak_client_id
    ALLOWED_EMAIL_DOMAIN            = var.allowed_email_domain
    MAIL_HOST                       = var.mail_host
    MAIL_PORT                       = tostring(var.mail_port)
    MAIL_FROM                       = var.mail_from
    WORKFLOW_DEPLOYMENT_ENVIRONMENT = var.environment
    WORKFLOW_SEED_ENABLED           = "false"
    }, var.contract_legacy_user_columns ? tomap({}) : tomap({
      # Pin only the application-switch deployment. Once the legacy contract is
      # approved, omitting the target lets later Flyway versions apply normally.
      SPRING_FLYWAY_TARGET = "006"
  }))
  secret_environment_variables = {
    SPRING_DATASOURCE_PASSWORD = "workflow-db-password"
  }
  database_bootstrap = {
    host                = module.postgres[0].fqdn
    administrator_login = var.postgres_administrator_login
    database_name       = "workflow"
    database_role       = "workflow"
    admin_secret_name   = "postgres-admin-password"
    role_secret_name    = "workflow-db-password"
    extensions          = ["btree_gist"]
  }
  startup_probe = {
    path                  = "/actuator/health/readiness"
    port                  = 8080
    initial_delay_seconds = 10
    interval_seconds      = 10
    timeout               = 5
    failure_threshold     = 30
  }
  liveness_probe = {
    path = "/actuator/health/liveness"
    port = 8080
  }
  readiness_probe = {
    path = "/actuator/health/readiness"
    port = 8080
  }

  depends_on = [module.postgres, module.key_vault, azurerm_role_assignment.acr_pull]
}

module "keycloak" {
  count  = var.provision_workloads ? 1 : 0
  source = "../container-app"

  name                         = var.keycloak_container_app_name
  resource_group_name          = data.azurerm_resource_group.this.name
  container_app_environment_id = module.container_app_environment.id
  identity_id                  = module.runtime_identity.id
  registry_server              = module.registry.login_server
  image                        = "${module.registry.login_server}/enterprise-workflow-keycloak:${var.image_tag}"
  target_port                  = 8080
  external_enabled             = true
  cpu                          = 1
  memory                       = "2Gi"
  key_vault_uri                = module.key_vault.vault_uri
  environment_variables = {
    KC_DB                       = "postgres"
    KC_DB_URL                   = "jdbc:postgresql://${module.postgres[0].fqdn}:5432/keycloak?sslmode=require"
    KC_DB_USERNAME              = "keycloak"
    KC_HOSTNAME                 = local.keycloak_url
    KC_HTTP_ENABLED             = "true"
    KC_PROXY_HEADERS            = "xforwarded"
    KC_HEALTH_ENABLED           = "true"
    KC_METRICS_ENABLED          = "true"
    KC_BOOTSTRAP_ADMIN_USERNAME = "admin"
  }
  secret_environment_variables = {
    KC_DB_PASSWORD              = "keycloak-db-password"
    KC_BOOTSTRAP_ADMIN_PASSWORD = "keycloak-bootstrap-admin-password"
  }
  database_bootstrap = {
    host                = module.postgres[0].fqdn
    administrator_login = var.postgres_administrator_login
    database_name       = "keycloak"
    database_role       = "keycloak"
    admin_secret_name   = "postgres-admin-password"
    role_secret_name    = "keycloak-db-password"
  }
  startup_probe = {
    path                  = "/health/started"
    port                  = 9000
    initial_delay_seconds = 10
    interval_seconds      = 10
    timeout               = 5
    failure_threshold     = 60
  }
  liveness_probe = {
    path                  = "/health/live"
    port                  = 9000
    initial_delay_seconds = 30
    interval_seconds      = 30
    timeout               = 5
    failure_threshold     = 3
  }
  readiness_probe = {
    path              = "/health/ready"
    port              = 9000
    interval_seconds  = 10
    timeout           = 5
    failure_threshold = 18
    success_threshold = 1
  }

  depends_on = [module.postgres, module.key_vault, azurerm_role_assignment.acr_pull]
}

module "frontend" {
  count  = var.provision_workloads ? 1 : 0
  source = "../container-app"

  name                         = var.frontend_container_app_name
  resource_group_name          = data.azurerm_resource_group.this.name
  container_app_environment_id = module.container_app_environment.id
  identity_id                  = module.runtime_identity.id
  registry_server              = module.registry.login_server
  image                        = "${module.registry.login_server}/enterprise-workflow-frontend:${var.image_tag}"
  target_port                  = 3000
  external_enabled             = true
  key_vault_uri                = module.key_vault.vault_uri
  environment_variables = {
    HOSTNAME              = "0.0.0.0"
    NEXT_PUBLIC_APP_NAME  = "ワークフローシステム"
    BETTER_AUTH_URL       = local.frontend_url
    BACKEND_INTERNAL_URL  = "https://${module.backend[0].fqdn}"
    KEYCLOAK_ISSUER       = local.keycloak_issuer
    KEYCLOAK_INTERNAL_URL = local.keycloak_url
    KEYCLOAK_REALM        = var.keycloak_realm
    KEYCLOAK_CLIENT_ID    = var.keycloak_client_id
  }
  secret_environment_variables = {
    BETTER_AUTH_SECRET     = "better-auth-secret"
    KEYCLOAK_CLIENT_SECRET = "keycloak-client-secret"
  }
  startup_probe = {
    path                  = "/login"
    port                  = 3000
    initial_delay_seconds = 5
    interval_seconds      = 10
    timeout               = 5
    failure_threshold     = 30
  }
  liveness_probe = {
    path = "/login"
    port = 3000
  }
  readiness_probe = {
    path = "/login"
    port = 3000
  }

  depends_on = [
    module.backend,
    module.keycloak,
    module.key_vault,
    azurerm_role_assignment.acr_pull,
  ]
}

locals {
  manual_seed_targets = var.provision_workloads && var.environment == "staging" ? toset([
    "db",
    "keycloak",
    "all",
  ]) : toset([])
}

resource "azurerm_container_app_job" "manual_seed" {
  for_each = local.manual_seed_targets

  name                         = "job-enterprise-workflow-staging-seed-${each.key}"
  location                     = var.location
  resource_group_name          = data.azurerm_resource_group.this.name
  container_app_environment_id = module.container_app_environment.id
  replica_timeout_in_seconds   = 1800
  replica_retry_limit          = 0

  identity {
    type         = "UserAssigned"
    identity_ids = [module.runtime_identity.id]
  }

  registry {
    server   = module.registry.login_server
    identity = module.runtime_identity.id
  }

  secret {
    name                = "workflow-db-password"
    identity            = module.runtime_identity.id
    key_vault_secret_id = "${module.key_vault.vault_uri}secrets/workflow-db-password"
  }

  secret {
    name                = "keycloak-bootstrap-admin-password"
    identity            = module.runtime_identity.id
    key_vault_secret_id = "${module.key_vault.vault_uri}secrets/keycloak-bootstrap-admin-password"
  }

  secret {
    name                = "development-seed-password"
    identity            = module.runtime_identity.id
    key_vault_secret_id = "${module.key_vault.vault_uri}secrets/development-seed-password"
  }

  manual_trigger_config {
    parallelism              = 1
    replica_completion_count = 1
  }

  template {
    container {
      name    = "manual-seed-${each.key}"
      image   = "${module.registry.login_server}/enterprise-workflow-seed:${var.image_tag}"
      cpu     = 0.5
      memory  = "1Gi"
      command = ["/app/manual-seed.sh"]

      env {
        name  = "WORKFLOW_MANUAL_SEED_ENABLED"
        value = "true"
      }
      env {
        name  = "WORKFLOW_DEPLOYMENT_ENVIRONMENT"
        value = var.environment
      }
      env {
        name  = "WORKFLOW_MANUAL_SEED_TARGET"
        value = each.key
      }
      env {
        name  = "SPRING_DATASOURCE_URL"
        value = "jdbc:postgresql://${module.postgres[0].fqdn}:5432/workflow?sslmode=require"
      }
      env {
        name  = "SPRING_DATASOURCE_USERNAME"
        value = "workflow"
      }
      env {
        name        = "SPRING_DATASOURCE_PASSWORD"
        secret_name = "workflow-db-password"
      }
      env {
        name  = "KEYCLOAK_URL"
        value = local.keycloak_url
      }
      env {
        name  = "KEYCLOAK_ADMIN_USERNAME"
        value = "admin"
      }
      env {
        name        = "KEYCLOAK_ADMIN_PASSWORD"
        secret_name = "keycloak-bootstrap-admin-password"
      }
      env {
        name  = "KEYCLOAK_REALM"
        value = var.keycloak_realm
      }
      env {
        name        = "DEV_SEED_PASSWORD"
        secret_name = "development-seed-password"
      }
      env {
        name  = "DEV_ADMIN_EMAIL"
        value = "example.admin1@${var.allowed_email_domain}"
      }
      env {
        name  = "DEV_USER_EMAIL"
        value = "example.user1@${var.allowed_email_domain}"
      }
    }
  }

  depends_on = [
    module.backend,
    module.keycloak,
    module.key_vault,
    azurerm_role_assignment.acr_pull,
  ]
}
