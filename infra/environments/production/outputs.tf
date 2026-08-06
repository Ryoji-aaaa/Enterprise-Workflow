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
output "manual_seed_job_names" { value = module.environment.manual_seed_job_names }
output "attachment_storage_account_name" { value = module.environment.attachment_storage_account_name }
output "attachment_storage_blob_endpoint" { value = module.environment.attachment_storage_blob_endpoint }
output "backend_blob_identity_client_id" { value = module.environment.backend_blob_identity_client_id }
