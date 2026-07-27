data "azurerm_client_config" "current" {}

resource "azurerm_resource_group" "tfstate" {
  name     = var.tfstate_resource_group_name
  location = var.location

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_storage_account" "tfstate" {
  name                            = var.tfstate_storage_account_name
  resource_group_name             = azurerm_resource_group.tfstate.name
  location                        = azurerm_resource_group.tfstate.location
  account_kind                    = "StorageV2"
  account_tier                    = "Standard"
  account_replication_type        = "LRS"
  min_tls_version                 = "TLS1_2"
  https_traffic_only_enabled      = true
  allow_nested_items_to_be_public = false
  shared_access_key_enabled       = false
  default_to_oauth_authentication = true
  public_network_access_enabled   = true

  blob_properties {
    versioning_enabled = true

    delete_retention_policy {
      days = 14
    }

    container_delete_retention_policy {
      days = 14
    }
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_storage_container" "tfstate" {
  name                  = var.tfstate_container_name
  storage_account_id    = azurerm_storage_account.tfstate.id
  container_access_type = "private"

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_resource_group" "acr" {
  name     = var.acr_resource_group_name
  location = var.location

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_container_registry" "shared" {
  name                          = var.acr_name
  resource_group_name           = azurerm_resource_group.acr.name
  location                      = azurerm_resource_group.acr.location
  sku                           = "Basic"
  admin_enabled                 = false
  public_network_access_enabled = true
}

resource "azurerm_resource_group" "environment" {
  for_each = {
    staging    = var.staging_resource_group_name
    production = var.production_resource_group_name
  }

  name     = each.value
  location = var.location

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_user_assigned_identity" "github" {
  for_each = azurerm_resource_group.environment

  name                = "uami-enterprise-workflow-${each.key}-github"
  resource_group_name = each.value.name
  location            = each.value.location
}

resource "azurerm_federated_identity_credential" "github" {
  for_each = azurerm_user_assigned_identity.github

  name                = "github-enterprise-workflow-${each.key}"
  resource_group_name = each.value.resource_group_name
  parent_id           = each.value.id
  audience            = ["api://AzureADTokenExchange"]
  issuer              = "https://token.actions.githubusercontent.com"
  subject             = "repo:${var.github_organization}/${var.github_repository}:environment:${each.key}"
}

locals {
  environment_roles = {
    for pair in setproduct(keys(azurerm_resource_group.environment), ["Contributor", "User Access Administrator"]) :
    "${pair[0]}-${pair[1]}" => {
      environment = pair[0]
      role        = pair[1]
    }
  }
}

resource "azurerm_role_assignment" "environment" {
  for_each = local.environment_roles

  scope                = azurerm_resource_group.environment[each.value.environment].id
  role_definition_name = each.value.role
  principal_id         = azurerm_user_assigned_identity.github[each.value.environment].principal_id
}

resource "azurerm_role_assignment" "state" {
  for_each = azurerm_user_assigned_identity.github

  scope                = azurerm_storage_account.tfstate.id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = each.value.principal_id
}

resource "azurerm_role_assignment" "acr_push" {
  for_each = azurerm_user_assigned_identity.github

  scope                = azurerm_container_registry.shared.id
  role_definition_name = "AcrPush"
  principal_id         = each.value.principal_id
}

resource "azurerm_role_assignment" "acr_role_administration" {
  for_each = azurerm_user_assigned_identity.github

  scope                = azurerm_container_registry.shared.id
  role_definition_name = "User Access Administrator"
  principal_id         = each.value.principal_id
}
