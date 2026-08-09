resource "azurerm_storage_account" "this" {
  name                            = var.name
  resource_group_name             = var.resource_group_name
  location                        = var.location
  account_kind                    = "StorageV2"
  account_tier                    = "Standard"
  account_replication_type        = "LRS"
  access_tier                     = "Hot"
  min_tls_version                 = "TLS1_2"
  https_traffic_only_enabled      = true
  allow_nested_items_to_be_public = false
  shared_access_key_enabled       = false
  default_to_oauth_authentication = true
  public_network_access_enabled   = false

  blob_properties {
    versioning_enabled = false

    delete_retention_policy {
      days = var.soft_delete_retention_days
    }

    container_delete_retention_policy {
      days = var.soft_delete_retention_days
    }
  }
}

resource "azurerm_storage_container" "input" {
  name                  = var.input_container_name
  storage_account_id    = azurerm_storage_account.this.id
  container_access_type = "private"
}

resource "azurerm_storage_container" "result" {
  name                  = var.result_container_name
  storage_account_id    = azurerm_storage_account.this.id
  container_access_type = "private"
}
