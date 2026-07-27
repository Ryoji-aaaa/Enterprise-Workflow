output "key_vault_name" { value = module.environment.key_vault_name }
output "frontend_url" { value = module.environment.frontend_url }
output "keycloak_url" { value = module.environment.keycloak_url }
output "backend_fqdn" {
  value     = module.environment.backend_fqdn
  sensitive = true
}
output "postgres_fqdn" {
  value     = module.environment.postgres_fqdn
  sensitive = true
}
