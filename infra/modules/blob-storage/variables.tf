variable "name" { type = string }
variable "location" { type = string }
variable "resource_group_name" { type = string }
variable "container_name" {
  type    = string
  default = "expense-evidence"
}
variable "soft_delete_retention_days" {
  type    = number
  default = 30
  validation {
    condition     = var.soft_delete_retention_days >= 1 && var.soft_delete_retention_days <= 365
    error_message = "soft_delete_retention_days must be between 1 and 365."
  }
}
