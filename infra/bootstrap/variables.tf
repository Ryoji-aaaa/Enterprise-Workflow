variable "subscription_id" {
  description = "Azure subscription ID."
  type        = string
}

variable "location" {
  description = "Azure region shared by the initial resources."
  type        = string
}

variable "github_organization" {
  type    = string
  default = "Ryoji-aaaa"
}

variable "github_repository" {
  type    = string
  default = "Enterprise-Workflow"
}

variable "tfstate_resource_group_name" {
  type = string
}

variable "tfstate_storage_account_name" {
  type = string
}

variable "tfstate_container_name" {
  type    = string
  default = "tfstate"
}

variable "acr_resource_group_name" {
  type = string
}

variable "acr_name" {
  type = string
}

variable "staging_resource_group_name" {
  type = string
}

variable "production_resource_group_name" {
  type = string
}
