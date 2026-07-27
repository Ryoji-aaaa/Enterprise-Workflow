variable "name" { type = string }
variable "location" { type = string }
variable "resource_group_name" { type = string }
variable "log_analytics_workspace_id" { type = string }
variable "vnet_name" { type = string }
variable "infrastructure_subnet_name" {
  type    = string
  default = "snet-container-apps"
}
variable "postgres_subnet_name" {
  type    = string
  default = "snet-postgres"
}
variable "vnet_address_space" {
  type    = list(string)
  default = ["10.40.0.0/16"]
}
variable "infrastructure_subnet_prefixes" {
  type    = list(string)
  default = ["10.40.0.0/23"]
}
variable "postgres_subnet_prefixes" {
  type    = list(string)
  default = ["10.40.2.0/24"]
}
