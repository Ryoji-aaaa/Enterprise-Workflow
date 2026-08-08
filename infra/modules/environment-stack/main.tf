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

  name                             = var.container_app_environment_name
  location                         = var.location
  resource_group_name              = data.azurerm_resource_group.this.name
  log_analytics_workspace_id       = module.monitoring.id
  vnet_name                        = "vnet-enterprise-workflow-${var.environment}"
  vnet_address_space               = var.vnet_address_space
  infrastructure_subnet_prefixes   = var.container_apps_subnet_prefixes
  postgres_subnet_prefixes         = var.postgres_subnet_prefixes
  private_endpoint_subnet_prefixes = var.private_endpoint_subnet_prefixes
}

module "runtime_identity" {
  source = "../identity"

  name                = "uami-enterprise-workflow-${var.environment}-runtime"
  location            = var.location
  resource_group_name = data.azurerm_resource_group.this.name
}

module "backend_blob_identity" {
  source = "../identity"

  name                = "uami-enterprise-workflow-${var.environment}-backend-blob"
  location            = var.location
  resource_group_name = data.azurerm_resource_group.this.name
}

module "attachment_storage" {
  source = "../blob-storage"

  name                       = var.attachment_storage_account_name
  location                   = var.location
  resource_group_name        = data.azurerm_resource_group.this.name
  container_name             = "expense-evidence"
  soft_delete_retention_days = 30
}

resource "azurerm_role_assignment" "backend_attachment_blob" {
  scope                = module.attachment_storage.container_scope
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = module.backend_blob_identity.principal_id
}

module "document_analysis_ai_identity" {
  source = "../identity"

  name                = "uami-enterprise-workflow-${var.environment}-backend-document-analysis-ai"
  location            = var.location
  resource_group_name = data.azurerm_resource_group.this.name
}

module "document_analysis_storage_identity" {
  source = "../identity"

  name                = "uami-enterprise-workflow-${var.environment}-backend-document-analysis-storage"
  location            = var.location
  resource_group_name = data.azurerm_resource_group.this.name
}

module "document_intelligence" {
  source = "../cognitive-account"

  name                       = var.document_intelligence_account_name
  location                   = var.location
  resource_group_name        = data.azurerm_resource_group.this.name
  kind                       = "FormRecognizer"
  sku_name                   = "S0"
  project_management_enabled = false
}

module "content_understanding" {
  source = "../cognitive-account"

  name                       = var.content_understanding_account_name
  location                   = var.location
  resource_group_name        = data.azurerm_resource_group.this.name
  kind                       = "AIServices"
  sku_name                   = "S0"
  project_management_enabled = true
}

module "document_analysis_storage" {
  source = "../document-analysis-storage"

  name                       = var.document_analysis_storage_account_name
  location                   = var.location
  resource_group_name        = data.azurerm_resource_group.this.name
  input_container_name       = "document-analysis-input"
  result_container_name      = "document-analysis-result"
  soft_delete_retention_days = 7
}

resource "azurerm_role_assignment" "document_intelligence_reader" {
  scope                = module.document_intelligence.id
  role_definition_name = "Cognitive Services Data Reader"
  principal_id         = module.document_analysis_ai_identity.principal_id
}

resource "azurerm_role_assignment" "content_understanding_reader" {
  scope                = module.content_understanding.id
  role_definition_name = "Cognitive Services Content Understanding Reader"
  principal_id         = module.document_analysis_ai_identity.principal_id
}

resource "azurerm_role_assignment" "document_analysis_input_blob" {
  scope                = module.document_analysis_storage.input_container_scope
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = module.document_analysis_storage_identity.principal_id
}

resource "azurerm_role_assignment" "document_analysis_result_blob" {
  scope                = module.document_analysis_storage.result_container_scope
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = module.document_analysis_storage_identity.principal_id
}

resource "azurerm_private_dns_zone" "cognitive_services" {
  name                = "privatelink.cognitiveservices.azure.com"
  resource_group_name = data.azurerm_resource_group.this.name
}

resource "azurerm_private_dns_zone" "openai" {
  name                = "privatelink.openai.azure.com"
  resource_group_name = data.azurerm_resource_group.this.name
}

resource "azurerm_private_dns_zone" "services_ai" {
  name                = "privatelink.services.ai.azure.com"
  resource_group_name = data.azurerm_resource_group.this.name
}

resource "azurerm_private_dns_zone" "blob" {
  name                = "privatelink.blob.core.windows.net"
  resource_group_name = data.azurerm_resource_group.this.name
}

resource "azurerm_private_dns_zone_virtual_network_link" "cognitive_services" {
  name                  = "vnet-enterprise-workflow-${var.environment}-cognitive-services"
  private_dns_zone_name = azurerm_private_dns_zone.cognitive_services.name
  resource_group_name   = data.azurerm_resource_group.this.name
  virtual_network_id    = module.container_app_environment.vnet_id
  registration_enabled  = false
}

resource "azurerm_private_dns_zone_virtual_network_link" "openai" {
  name                  = "vnet-enterprise-workflow-${var.environment}-openai"
  private_dns_zone_name = azurerm_private_dns_zone.openai.name
  resource_group_name   = data.azurerm_resource_group.this.name
  virtual_network_id    = module.container_app_environment.vnet_id
  registration_enabled  = false
}

resource "azurerm_private_dns_zone_virtual_network_link" "services_ai" {
  name                  = "vnet-enterprise-workflow-${var.environment}-services-ai"
  private_dns_zone_name = azurerm_private_dns_zone.services_ai.name
  resource_group_name   = data.azurerm_resource_group.this.name
  virtual_network_id    = module.container_app_environment.vnet_id
  registration_enabled  = false
}

resource "azurerm_private_dns_zone_virtual_network_link" "blob" {
  name                  = "vnet-enterprise-workflow-${var.environment}-blob"
  private_dns_zone_name = azurerm_private_dns_zone.blob.name
  resource_group_name   = data.azurerm_resource_group.this.name
  virtual_network_id    = module.container_app_environment.vnet_id
  registration_enabled  = false
}

resource "azurerm_private_endpoint" "document_intelligence" {
  name                = "pe-enterprise-workflow-${var.environment}-document-intelligence"
  location            = var.location
  resource_group_name = data.azurerm_resource_group.this.name
  subnet_id           = module.container_app_environment.private_endpoint_subnet_id

  private_service_connection {
    name                           = "psc-enterprise-workflow-${var.environment}-document-intelligence"
    private_connection_resource_id = module.document_intelligence.id
    is_manual_connection           = false
    subresource_names              = ["account"]
  }

  private_dns_zone_group {
    name                 = "document-intelligence"
    private_dns_zone_ids = [azurerm_private_dns_zone.cognitive_services.id]
  }

  depends_on = [azurerm_private_dns_zone_virtual_network_link.cognitive_services]
}

resource "azurerm_private_endpoint" "content_understanding" {
  name                = "pe-enterprise-workflow-${var.environment}-content-understanding"
  location            = var.location
  resource_group_name = data.azurerm_resource_group.this.name
  subnet_id           = module.container_app_environment.private_endpoint_subnet_id

  private_service_connection {
    name                           = "psc-enterprise-workflow-${var.environment}-content-understanding"
    private_connection_resource_id = module.content_understanding.id
    is_manual_connection           = false
    subresource_names              = ["account"]
  }

  private_dns_zone_group {
    name = "content-understanding"
    private_dns_zone_ids = [
      azurerm_private_dns_zone.cognitive_services.id,
      azurerm_private_dns_zone.openai.id,
      azurerm_private_dns_zone.services_ai.id,
    ]
  }

  depends_on = [
    azurerm_private_dns_zone_virtual_network_link.cognitive_services,
    azurerm_private_dns_zone_virtual_network_link.openai,
    azurerm_private_dns_zone_virtual_network_link.services_ai,
  ]
}

resource "azurerm_private_endpoint" "document_analysis_blob" {
  name                = "pe-enterprise-workflow-${var.environment}-document-analysis-blob"
  location            = var.location
  resource_group_name = data.azurerm_resource_group.this.name
  subnet_id           = module.container_app_environment.private_endpoint_subnet_id

  private_service_connection {
    name                           = "psc-enterprise-workflow-${var.environment}-document-analysis-blob"
    private_connection_resource_id = module.document_analysis_storage.id
    is_manual_connection           = false
    subresource_names              = ["blob"]
  }

  private_dns_zone_group {
    name                 = "document-analysis-blob"
    private_dns_zone_ids = [azurerm_private_dns_zone.blob.id]
  }

  depends_on = [azurerm_private_dns_zone_virtual_network_link.blob]
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
  additional_identity_ids = [
    module.backend_blob_identity.id,
    module.document_analysis_ai_identity.id,
    module.document_analysis_storage_identity.id,
  ]
  registry_server  = module.registry.login_server
  image            = "${module.registry.login_server}/enterprise-workflow-backend:${var.image_tag}"
  target_port      = 8080
  external_enabled = false
  key_vault_uri    = module.key_vault.vault_uri
  environment_variables = merge({
    SPRING_DATASOURCE_URL               = "jdbc:postgresql://${module.postgres[0].fqdn}:5432/workflow?sslmode=require"
    SPRING_DATASOURCE_USERNAME          = "workflow"
    KEYCLOAK_ISSUER                     = local.keycloak_issuer
    KEYCLOAK_INTERNAL_ISSUER            = local.keycloak_issuer
    KEYCLOAK_CLIENT_ID                  = var.keycloak_client_id
    ALLOWED_EMAIL_DOMAIN                = var.allowed_email_domain
    WORKFLOW_DEPLOYMENT_ENVIRONMENT     = var.environment
    WORKFLOW_SEED_ENABLED               = "false"
    AZURE_STORAGE_BLOB_ENDPOINT         = module.attachment_storage.primary_blob_endpoint
    AZURE_STORAGE_CONTAINER_NAME        = module.attachment_storage.container_name
    AZURE_CLIENT_ID                     = module.backend_blob_identity.client_id
    ATTACHMENT_STORAGE_CREATE_CONTAINER = "false"
    WORKFLOW_DOCUMENT_ANALYSIS_ENABLED  = tostring(var.document_analysis_enabled)
    WORKFLOW_DOCUMENT_ANALYSIS_EXECUTION_MODE = (
      var.document_analysis_enabled ? "azure" : "disabled"
    )
    AZURE_DOCUMENT_ANALYSIS_CLIENT_ID                    = module.document_analysis_ai_identity.client_id
    DOCUMENT_INTELLIGENCE_ENABLED                        = tostring(var.document_analysis_enabled && var.document_intelligence_enabled)
    DOCUMENT_INTELLIGENCE_ENDPOINT                       = module.document_intelligence.endpoint
    DOCUMENT_INTELLIGENCE_MODEL_ID                       = "prebuilt-layout"
    DOCUMENT_INTELLIGENCE_API_VERSION                    = "2024-11-30"
    DOCUMENT_INTELLIGENCE_ANALYSIS_TIMEOUT               = "25m"
    CONTENT_UNDERSTANDING_ENABLED                        = tostring(var.document_analysis_enabled && var.content_understanding_enabled)
    CONTENT_UNDERSTANDING_ENDPOINT                       = module.content_understanding.endpoint
    CONTENT_UNDERSTANDING_ANALYZER_ID                    = "prebuilt-layout"
    CONTENT_UNDERSTANDING_API_VERSION                    = "2025-11-01"
    CONTENT_UNDERSTANDING_ANALYSIS_TIMEOUT               = "25m"
    DOCUMENT_ANALYSIS_STORAGE_BLOB_ENDPOINT              = module.document_analysis_storage.primary_blob_endpoint
    DOCUMENT_ANALYSIS_STORAGE_MANAGED_IDENTITY_CLIENT_ID = module.document_analysis_storage_identity.client_id
    DOCUMENT_ANALYSIS_INPUT_CONTAINER_NAME               = module.document_analysis_storage.input_container_name
    DOCUMENT_ANALYSIS_RESULT_CONTAINER_NAME              = module.document_analysis_storage.result_container_name
    DOCUMENT_ANALYSIS_STORAGE_CREATE_CONTAINERS          = "false"
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

  depends_on = [
    module.postgres,
    module.key_vault,
    azurerm_role_assignment.acr_pull,
    azurerm_role_assignment.backend_attachment_blob,
    azurerm_role_assignment.document_intelligence_reader,
    azurerm_role_assignment.content_understanding_reader,
    azurerm_role_assignment.document_analysis_input_blob,
    azurerm_role_assignment.document_analysis_result_blob,
    azurerm_private_endpoint.document_intelligence,
    azurerm_private_endpoint.content_understanding,
    azurerm_private_endpoint.document_analysis_blob,
  ]
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
  manual_seed_job_names_by_target = {
    db       = "job-ewf-stg-seed-db"
    keycloak = "job-ewf-stg-seed-kc"
    all      = "job-ewf-stg-seed-all"
  }
  manual_seed_jobs = var.provision_workloads && var.environment == "staging" ? local.manual_seed_job_names_by_target : {}
}

resource "azurerm_container_app_job" "manual_seed" {
  for_each = local.manual_seed_jobs

  name                         = each.value
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
