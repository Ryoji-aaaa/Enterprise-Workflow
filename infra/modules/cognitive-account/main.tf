resource "azurerm_cognitive_account" "this" {
  name                          = var.name
  location                      = var.location
  resource_group_name           = var.resource_group_name
  kind                          = var.kind
  sku_name                      = var.sku_name
  custom_subdomain_name         = var.name
  local_auth_enabled            = false
  public_network_access_enabled = false
  project_management_enabled    = var.project_management_enabled
}
