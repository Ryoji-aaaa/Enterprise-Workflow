module "environment" {
  source = "../../modules/environment-stack"

  environment                     = "production"
  location                        = var.location
  resource_group_name             = var.resource_group_name
  acr_name                        = var.acr_name
  acr_resource_group_name         = var.acr_resource_group_name
  github_identity_principal_id    = var.github_identity_principal_id
  container_app_environment_name  = var.container_app_environment_name
  frontend_container_app_name     = var.frontend_container_app_name
  backend_container_app_name      = var.backend_container_app_name
  keycloak_container_app_name     = var.keycloak_container_app_name
  key_vault_name                  = var.key_vault_name
  postgres_server_name            = var.postgres_server_name
  postgres_administrator_login    = var.postgres_administrator_login
  postgres_administrator_password = var.postgres_administrator_password
  image_tag                       = var.image_tag
  provision_workloads             = var.provision_workloads
  contract_legacy_user_columns    = var.contract_legacy_user_columns
  allowed_email_domain            = var.allowed_email_domain
  mail_host                       = var.mail_host
  mail_port                       = var.mail_port
  mail_from                       = var.mail_from
  vnet_address_space              = var.vnet_address_space
  container_apps_subnet_prefixes  = var.container_apps_subnet_prefixes
  postgres_subnet_prefixes        = var.postgres_subnet_prefixes
}
