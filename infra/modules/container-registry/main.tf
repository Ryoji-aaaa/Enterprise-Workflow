data "azurerm_container_registry" "this" {
  name                = var.name
  resource_group_name = var.resource_group_name
}
