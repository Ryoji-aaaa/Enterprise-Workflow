output "key_vault_name" { value = module.key_vault.name }
output "container_app_environment_id" { value = module.container_app_environment.id }
output "frontend_url" { value = var.provision_workloads ? local.frontend_url : null }
output "keycloak_url" { value = var.provision_workloads ? local.keycloak_url : null }
output "backend_fqdn" {
  value     = var.provision_workloads ? module.backend[0].fqdn : null
  sensitive = true
}
output "postgres_fqdn" {
  value     = var.provision_workloads ? module.postgres[0].fqdn : null
  sensitive = true
}
output "manual_seed_job_names" {
  value = {
    for target, job in azurerm_container_app_job.manual_seed : target => job.name
  }
}
output "attachment_storage_account_name" { value = module.attachment_storage.name }
output "attachment_storage_blob_endpoint" { value = module.attachment_storage.primary_blob_endpoint }
output "backend_blob_identity_client_id" { value = module.backend_blob_identity.client_id }
output "document_intelligence_name" { value = module.document_intelligence.name }
output "document_intelligence_endpoint" { value = module.document_intelligence.endpoint }
output "content_understanding_name" { value = module.content_understanding.name }
output "content_understanding_endpoint" { value = module.content_understanding.endpoint }
output "document_analysis_storage_account_name" { value = module.document_analysis_storage.name }
output "document_analysis_storage_blob_endpoint" { value = module.document_analysis_storage.primary_blob_endpoint }
output "document_analysis_ai_identity_client_id" { value = module.document_analysis_ai_identity.client_id }
output "document_analysis_storage_identity_client_id" { value = module.document_analysis_storage_identity.client_id }
