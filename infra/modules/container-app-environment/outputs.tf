output "id" { value = azurerm_container_app_environment.this.id }
output "default_domain" { value = azurerm_container_app_environment.this.default_domain }
output "static_ip_address" { value = azurerm_container_app_environment.this.static_ip_address }
output "postgres_subnet_id" { value = azurerm_subnet.postgres.id }
output "postgres_private_dns_zone_id" { value = azurerm_private_dns_zone.postgres.id }
