# 技術文書

この索引は現在の実装仕様と意思決定記録を案内します。操作方法は
[ルートREADME](../README.md)を参照してください。

## アーキテクチャ

- [システム概要](architecture/system-overview.md)
- [認証フロー](architecture/authentication-flow.md)
- [ネットワーク境界](architecture/network-boundaries.md)

## 実装仕様

- Frontend: [Next.js・Better Auth](frontend/nextjs-better-auth.md)
- Frontend UI: [shadcn/ui・Tailwind CSS](frontend/shadcn-tailwind.md)
- Frontend features:
  [組織図](frontend/organization-chart.md) /
  [ユーザー管理](frontend/user-management.md) /
  [経費精算申請](frontend/expense-application.md) /
  [メール通知履歴](frontend/mail-notification-history.md) /
  [Document Analysis](frontend/document-analysis.md)
- Backend:
  [Spring Boot](backend/spring-boot.md) /
  [Flyway](backend/flyway.md) /
  [ユーザー管理](backend/user-management.md) /
  [組織・所属・役職管理](backend/organization-management.md) /
  [業務認可](backend/authorization.md) /
  [監査ログ](backend/audit-logging.md) /
  [経費精算申請](backend/expense-application.md) /
  [ローカルメール通知Outbox](backend/notification-outbox.md) /
  [Document Analysis](backend/document-analysis.md) /
  [開発・staging用seedデータ](backend/development-seed-data.md)
- Infrastructure:
  [Docker Compose](infrastructure/docker-compose.md) /
  [Keycloak](infrastructure/keycloak.md) /
  [PostgreSQL](infrastructure/postgresql.md) /
  [Azure architecture](infrastructure/azure-architecture.md) /
  [経費証憑Blob Storage](infrastructure/expense-attachment-storage.md) /
  [Terraform](infrastructure/terraform.md) /
  [GitHub Actions](infrastructure/github-actions.md) /
  [Keycloak on Azure](infrastructure/keycloak-azure.md)
- Testing: [統括テスト実行](testing/test-execution.md) / [Playwright E2E](testing/playwright.md)
- Operations:
  [本番移行・production readiness](operations/production-readiness.md) /
  [Azure deployment](operations/deployment.md) /
  [Azure Portal初回セットアップ](operations/azure-portal-setup.md) /
  [Revision rollback](operations/rollback.md) /
  [DB migration](operations/database-migration.md)

## 意思決定記録

- [ADR索引](decisions/README.md)
- [ADR-0001: 認証アーキテクチャ](decisions/ADR-0001-authentication-architecture.md)
- [ADR-0002: Better AuthのDBなしセッション](decisions/ADR-0002-stateless-better-auth-session.md)
- [ADR-0003: Keycloak設定をAdmin REST APIへ統一](decisions/ADR-0003-keycloak-configuration-api.md)
- [ADR-0004: User Profile設定方針](decisions/ADR-0004-user-profile-policy.md)
- [ADR-0005: アプリケーションのネットワーク境界](decisions/ADR-0005-application-network-boundaries.md)
- [ADR-0006: FlywayによるDBマイグレーション](decisions/ADR-0006-database-migration-with-flyway.md)
- [ADR-0007: GraphifyおよびEntireの導入保留](decisions/ADR-0007-deferred-ai-development-tools.md)
- [ADR-0008: Azure Container Apps](decisions/ADR-0008-azure-container-apps.md)
- [ADR-0009: Terraform](decisions/ADR-0009-terraform.md)
- [ADR-0010: Keycloak on Azure](decisions/ADR-0010-keycloak-on-azure.md)
- [ADR-0011: 業務ユーザーと外部認証IDの分離](decisions/ADR-0011-separate-business-users-from-external-identities.md)
- [ADR-0012: 組織・役職・業務権限の分離](decisions/ADR-0012-separate-organization-position-and-authorization.md)
- [ADR-0013: 追記専用の監査ログと変更履歴](decisions/ADR-0013-append-only-audit-and-change-history.md)
- [ADR-0014: 経費承認経路の申請時確定](decisions/ADR-0014-expense-approval-route-resolution.md)
- [ADR-0015: 経費証憑のBackend専用Blob Storage](decisions/ADR-0015-expense-attachment-blob-storage.md)
- [ADR-0016: ローカルMailpitとTransactional Outbox](decisions/ADR-0016-local-mailpit-transactional-outbox.md)
- [ADR-0017: Document AnalysisをBFF境界と非同期Jobで実装](decisions/ADR-0017-document-analysis-architecture.md)

## 履歴資料

[初期プロトタイプ構築Plan](archive/prototype-implementation-plan.md)は履歴資料であり、
現在の仕様や操作方法の正本ではありません。
