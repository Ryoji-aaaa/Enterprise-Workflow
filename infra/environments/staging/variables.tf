variable "subscription_id" { type = string }
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
}
variable "provision_workloads" {
  type    = bool
  default = false
}
variable "contract_legacy_user_columns" {
  type    = bool
  default = false
}
variable "allowed_email_domain" { type = string }
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
