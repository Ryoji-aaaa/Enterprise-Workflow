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
| [ADR-0011](ADR-0011-separate-business-users-from-external-identities.md) | Accepted | 業務ユーザーと外部認証IDの分離 |
| [ADR-0012](ADR-0012-separate-organization-position-and-authorization.md) | Accepted | 組織・役職・業務権限の分離 |
| [ADR-0013](ADR-0013-append-only-audit-and-change-history.md) | Accepted | 監査ログと変更履歴の追記専用化 |
| [ADR-0014](ADR-0014-expense-approval-route-resolution.md) | Accepted | 経費承認経路を申請時に確定 |
| [ADR-0015](ADR-0015-expense-attachment-blob-storage.md) | Accepted | 経費証憑をBackend専用Blob Storageへ保存 |
| [ADR-0016](ADR-0016-local-mailpit-transactional-outbox.md) | Accepted | メール配送をローカルMailpitとTransactional Outboxへ限定 |
