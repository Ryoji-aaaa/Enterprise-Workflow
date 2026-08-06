variable "environment" {
  type = string
  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "environment must be staging or production."
  }
}
variable "location" { type = string }
variable "resource_group_name" { type = string }
variable "acr_name" { type = string }
variable "acr_resource_group_name" { type = string }
variable "github_identity_principal_id" { type = string }
variable "container_app_environment_name" { type = string }
variable "frontend_container_app_name" { type = string }
variable "backend_container_app_name" { type = string }
variable "keycloak_container_app_name" { type = string }
variable "key_vault_name" { type = string }
variable "postgres_server_name" { type = string }
variable "attachment_storage_account_name" { type = string }
variable "postgres_administrator_login" {
  type    = string
  default = "workflowadmin"
}
variable "postgres_administrator_password" {
  type      = string
  sensitive = true
  default   = null
}
variable "image_tag" {
  type    = string
  default = "0000000000000000000000000000000000000000"
  validation {
    condition     = can(regex("^[0-9a-f]{40}$", var.image_tag))
    error_message = "image_tag must be a full lowercase 40-character Git commit SHA."
  }
}
variable "provision_workloads" {
  description = "False creates the platform and Key Vault only; true also creates PostgreSQL and apps."
  type        = bool
  default     = false
}
variable "contract_legacy_user_columns" {
  description = "False pins Flyway at V006 for the application-switch deployment; set true only in the later single-migrator contract deployment after legacy revisions and user-management writes are drained."
  type        = bool
  default     = false
}
variable "keycloak_realm" {
  type    = string
  default = "workflow"
}
variable "keycloak_client_id" {
  type    = string
  default = "workflow-web"
}
variable "allowed_email_domain" { type = string }
variable "mail_host" {
  type    = string
  default = "smtp-not-configured.invalid"
}
variable "mail_port" {
  type    = number
  default = 587
}
variable "mail_from" { type = string }
variable "vnet_address_space" {
  type    = list(string)
  default = ["10.40.0.0/16"]
}
variable "container_apps_subnet_prefixes" {
  type    = list(string)
  default = ["10.40.0.0/23"]
}
variable "postgres_subnet_prefixes" {
  type    = list(string)
  default = ["10.40.2.0/24"]
}
