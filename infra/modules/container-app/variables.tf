variable "name" { type = string }
variable "resource_group_name" { type = string }
variable "container_app_environment_id" { type = string }
variable "revision_mode" {
  type    = string
  default = "Single"
}
variable "identity_id" { type = string }
variable "registry_server" { type = string }
variable "image" { type = string }
variable "target_port" { type = number }
variable "external_enabled" { type = bool }
variable "cpu" {
  type    = number
  default = 0.5
}
variable "memory" {
  type    = string
  default = "1Gi"
}
variable "min_replicas" {
  type    = number
  default = 1
}
variable "max_replicas" {
  type    = number
  default = 1
}
variable "environment_variables" {
  type    = map(string)
  default = {}
}
variable "secret_environment_variables" {
  description = "Map of environment variable name to Key Vault secret name."
  type        = map(string)
  default     = {}
}
variable "key_vault_uri" { type = string }
variable "startup_probe" {
  type = object({
    path                  = string
    port                  = number
    initial_delay_seconds = optional(number, 1)
    interval_seconds      = optional(number, 10)
    timeout               = optional(number, 5)
    failure_threshold     = optional(number, 30)
  })
  default = null
}
variable "liveness_probe" {
  type = object({
    path                  = string
    port                  = number
    initial_delay_seconds = optional(number, 30)
    interval_seconds      = optional(number, 30)
    timeout               = optional(number, 5)
    failure_threshold     = optional(number, 3)
  })
  default = null
}
variable "readiness_probe" {
  type = object({
    path              = string
    port              = number
    interval_seconds  = optional(number, 10)
    timeout           = optional(number, 5)
    failure_threshold = optional(number, 12)
    success_threshold = optional(number, 1)
  })
  default = null
}
variable "database_bootstrap" {
  type = object({
    host                = string
    administrator_login = string
    database_name       = string
    database_role       = string
    admin_secret_name   = string
    role_secret_name    = string
    postgres_image      = optional(string, "postgres:18.4-alpine")
  })
  default = null

  validation {
    condition = var.database_bootstrap == null || contains(
      ["workflow", "keycloak"],
      try(var.database_bootstrap.database_role, "")
    )
    error_message = "database_role must be workflow or keycloak."
  }
}
