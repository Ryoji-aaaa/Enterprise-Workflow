variable "name" { type = string }
variable "location" { type = string }
variable "resource_group_name" { type = string }
variable "log_analytics_workspace_id" { type = string }
variable "enable_consumption_workload_profile" {
  type    = bool
  default = false
}
variable "vnet_name" { type = string }
variable "infrastructure_subnet_name" {
  type    = string
  default = "snet-container-apps"
}
variable "postgres_subnet_name" {
  type    = string
  default = "snet-postgres"
}
variable "private_endpoint_subnet_name" {
  type    = string
  default = "snet-private-endpoints"
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
variable "private_endpoint_subnet_prefixes" {
  type    = list(string)
  default = ["10.40.3.0/24"]
}
