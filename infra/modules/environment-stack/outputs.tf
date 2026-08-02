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
