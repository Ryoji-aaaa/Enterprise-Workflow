locals {
  bootstrap_secret_names = var.database_bootstrap == null ? [] : [
    var.database_bootstrap.admin_secret_name,
    var.database_bootstrap.role_secret_name,
  ]
  secret_names = setunion(
    toset(values(var.secret_environment_variables)),
    toset(local.bootstrap_secret_names),
  )
}

resource "azurerm_container_app" "this" {
  name                         = var.name
  resource_group_name          = var.resource_group_name
  container_app_environment_id = var.container_app_environment_id
  revision_mode                = var.revision_mode
  workload_profile_name        = var.workload_profile_name

  identity {
    type         = "UserAssigned"
    identity_ids = concat([var.identity_id], tolist(var.additional_identity_ids))
  }

  registry {
    server   = var.registry_server
    identity = var.identity_id
  }

  dynamic "secret" {
    for_each = local.secret_names
    content {
      name                = secret.value
      identity            = var.identity_id
      key_vault_secret_id = "${var.key_vault_uri}secrets/${secret.value}"
    }
  }

  template {
    min_replicas = var.min_replicas
    max_replicas = var.max_replicas

    dynamic "init_container" {
      for_each = var.database_bootstrap == null ? [] : [var.database_bootstrap]
      content {
        name    = "database-bootstrap"
        image   = init_container.value.postgres_image
        cpu     = 0.25
        memory  = "0.5Gi"
        command = ["/bin/sh", "-c"]
        args = [<<-SCRIPT
          set -eu
          export PGPASSWORD="$POSTGRES_ADMIN_PASSWORD"
          psql "host=$POSTGRES_HOST dbname=postgres user=$POSTGRES_ADMIN_USER sslmode=require" \
            --set=role_password="$DATABASE_PASSWORD" <<'SQL'
          SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', '${init_container.value.database_role}', :'role_password')
          WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${init_container.value.database_role}') \gexec
          SELECT format('ALTER ROLE %I PASSWORD %L', '${init_container.value.database_role}', :'role_password') \gexec
          GRANT CONNECT ON DATABASE ${init_container.value.database_name} TO ${init_container.value.database_role};
          SQL
          psql "host=$POSTGRES_HOST dbname=${init_container.value.database_name} user=$POSTGRES_ADMIN_USER sslmode=require" <<'SQL'
          ${join("\n", [for extension in init_container.value.extensions : "CREATE EXTENSION IF NOT EXISTS ${extension};"])}
          GRANT ALL ON SCHEMA public TO ${init_container.value.database_role};
          ALTER SCHEMA public OWNER TO ${init_container.value.database_role};
          SQL
        SCRIPT
        ]

        env {
          name  = "POSTGRES_HOST"
          value = init_container.value.host
        }
        env {
          name  = "POSTGRES_ADMIN_USER"
          value = init_container.value.administrator_login
        }
        env {
          name        = "POSTGRES_ADMIN_PASSWORD"
          secret_name = init_container.value.admin_secret_name
        }
        env {
          name        = "DATABASE_PASSWORD"
          secret_name = init_container.value.role_secret_name
        }
      }
    }

    container {
      name   = var.name
      image  = var.image
      cpu    = var.cpu
      memory = var.memory

      dynamic "env" {
        for_each = var.environment_variables
        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "env" {
        for_each = var.secret_environment_variables
        content {
          name        = env.key
          secret_name = env.value
        }
      }

      dynamic "startup_probe" {
        for_each = var.startup_probe == null ? [] : [var.startup_probe]
        content {
          transport               = "HTTP"
          path                    = startup_probe.value.path
          port                    = startup_probe.value.port
          initial_delay           = startup_probe.value.initial_delay_seconds
          interval_seconds        = startup_probe.value.interval_seconds
          timeout                 = startup_probe.value.timeout
          failure_count_threshold = startup_probe.value.failure_threshold
        }
      }

      dynamic "liveness_probe" {
        for_each = var.liveness_probe == null ? [] : [var.liveness_probe]
        content {
          transport               = "HTTP"
          path                    = liveness_probe.value.path
          port                    = liveness_probe.value.port
          initial_delay           = liveness_probe.value.initial_delay_seconds
          interval_seconds        = liveness_probe.value.interval_seconds
          timeout                 = liveness_probe.value.timeout
          failure_count_threshold = liveness_probe.value.failure_threshold
        }
      }

      dynamic "readiness_probe" {
        for_each = var.readiness_probe == null ? [] : [var.readiness_probe]
        content {
          transport               = "HTTP"
          path                    = readiness_probe.value.path
          port                    = readiness_probe.value.port
          interval_seconds        = readiness_probe.value.interval_seconds
          timeout                 = readiness_probe.value.timeout
          failure_count_threshold = readiness_probe.value.failure_threshold
          success_count_threshold = readiness_probe.value.success_threshold
        }
      }
    }
  }

  ingress {
    external_enabled           = var.external_enabled
    allow_insecure_connections = false
    target_port                = var.target_port
    transport                  = "auto"

    traffic_weight {
      latest_revision = true
      percentage      = 100
    }
  }
}
