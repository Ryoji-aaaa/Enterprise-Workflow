# Architecture Decision Records

ADRは現在の設計判断と、その理由・制約を記録します。`Accepted`は現行方針、
`Superseded`は後続ADRで置き換え済み、`Temporary`は終了条件を持つ暫定方針です。

| ADR | Status | 判断 |
| --- | --- | --- |
| [ADR-0001](ADR-0001-authentication-architecture.md) | Accepted | Next.js BFFとSpring Boot Resource Server |
| [ADR-0002](ADR-0002-stateless-better-auth-session.md) | Accepted | Better AuthのDBなしセッション |
| [ADR-0003](ADR-0003-keycloak-configuration-api.md) | Accepted | Keycloak Admin REST APIによる設定 |
| [ADR-0004](ADR-0004-user-profile-policy.md) | Accepted | Keycloak User Profileの最小更新 |
| [ADR-0005](ADR-0005-application-network-boundaries.md) | Accepted | アプリケーションのネットワーク境界 |
| [ADR-0006](ADR-0006-database-migration-with-flyway.md) | Accepted | FlywayによるDBマイグレーション |
| [ADR-0007](ADR-0007-deferred-ai-development-tools.md) | Temporary | GraphifyおよびEntireの導入保留 |
| [ADR-0008](ADR-0008-azure-container-apps.md) | Accepted | Azure Container Appsと公開境界 |
| [ADR-0009](ADR-0009-terraform.md) | Accepted | Azure resourceとstateのTerraform管理 |
| [ADR-0010](ADR-0010-keycloak-on-azure.md) | Accepted | Azureでもoptimized Keycloakを継続 |
