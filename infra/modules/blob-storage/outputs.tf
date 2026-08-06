output "id" { value = azurerm_storage_account.this.id }
output "name" { value = azurerm_storage_account.this.name }
output "primary_blob_endpoint" { value = azurerm_storage_account.this.primary_blob_endpoint }
output "container_name" { value = azurerm_storage_container.this.name }
output "container_scope" {
  value = "${azurerm_storage_account.this.id}/blobServices/default/containers/${azurerm_storage_container.this.name}"
}
