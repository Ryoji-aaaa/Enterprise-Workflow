output "tenant_id" {
  value = data.azurerm_client_config.current.tenant_id
}

output "acr_login_server" {
  value = azurerm_container_registry.shared.login_server
}

output "github_identity_client_ids" {
  value = {
    for name, identity in azurerm_user_assigned_identity.github : name => identity.client_id
  }
}

output "github_identity_principal_ids" {
  value = {
    for name, identity in azurerm_user_assigned_identity.github : name => identity.principal_id
  }
}
