output "id" { value = azurerm_container_app.this.id }
output "fqdn" { value = azurerm_container_app.this.ingress[0].fqdn }
output "latest_revision_name" { value = azurerm_container_app.this.latest_revision_name }
