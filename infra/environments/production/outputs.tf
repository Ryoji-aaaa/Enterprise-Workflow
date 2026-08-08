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
output "document_intelligence_name" { value = module.environment.document_intelligence_name }
output "document_intelligence_endpoint" { value = module.environment.document_intelligence_endpoint }
output "content_understanding_name" { value = module.environment.content_understanding_name }
output "content_understanding_endpoint" { value = module.environment.content_understanding_endpoint }
output "document_analysis_storage_account_name" { value = module.environment.document_analysis_storage_account_name }
output "document_analysis_storage_blob_endpoint" { value = module.environment.document_analysis_storage_blob_endpoint }
output "document_analysis_ai_identity_client_id" { value = module.environment.document_analysis_ai_identity_client_id }
output "document_analysis_storage_identity_client_id" { value = module.environment.document_analysis_storage_identity_client_id }
