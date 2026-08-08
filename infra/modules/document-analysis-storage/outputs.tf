output "id" { value = azurerm_storage_account.this.id }
output "name" { value = azurerm_storage_account.this.name }
output "primary_blob_endpoint" { value = azurerm_storage_account.this.primary_blob_endpoint }
output "input_container_name" { value = azurerm_storage_container.input.name }
output "result_container_name" { value = azurerm_storage_container.result.name }
output "input_container_scope" {
  value = "${azurerm_storage_account.this.id}/blobServices/default/containers/${azurerm_storage_container.input.name}"
}
output "result_container_scope" {
  value = "${azurerm_storage_account.this.id}/blobServices/default/containers/${azurerm_storage_container.result.name}"
}
