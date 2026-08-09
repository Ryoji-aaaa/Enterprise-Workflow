variable "name" { type = string }
variable "location" { type = string }
variable "resource_group_name" { type = string }
variable "kind" {
  type = string
  validation {
    condition     = contains(["AIServices", "FormRecognizer"], var.kind)
    error_message = "kind must be AIServices or FormRecognizer."
  }
}
variable "sku_name" {
  type    = string
  default = "S0"
}
variable "project_management_enabled" {
  type    = bool
  default = false
}
