# 技術文書

この索引は現在の実装仕様と意思決定記録を案内します。操作方法は
[ルートREADME](../README.md)を参照してください。

## アーキテクチャ

- [システム概要](architecture/system-overview.md)
- [認証フロー](architecture/authentication-flow.md)
- [ネットワーク境界](architecture/network-boundaries.md)

## 実装仕様

- Frontend: [Next.js・Better Auth](frontend/nextjs-better-auth.md)
- Backend: [Spring Boot](backend/spring-boot.md)
- Infrastructure:
  [Docker Compose](infrastructure/docker-compose.md) /
  [Keycloak](infrastructure/keycloak.md) /
  [PostgreSQL](infrastructure/postgresql.md)
- Testing: [Playwright E2E](testing/playwright.md)
- Operations: [本番移行・production readiness](operations/production-readiness.md)

## 意思決定記録

- [ADR索引](decisions/README.md)
- [ADR-0001: 認証アーキテクチャ](decisions/ADR-0001-authentication-architecture.md)
- [ADR-0002: Better AuthのDBなしセッション](decisions/ADR-0002-stateless-better-auth-session.md)
- [ADR-0003: Keycloak設定をAdmin REST APIへ統一](decisions/ADR-0003-keycloak-configuration-api.md)
- [ADR-0004: User Profile設定方針](decisions/ADR-0004-user-profile-policy.md)
- [ADR-0005: アプリケーションのネットワーク境界](decisions/ADR-0005-application-network-boundaries.md)

## 履歴資料

[初期プロトタイプ構築Plan](archive/prototype-implementation-plan.md)は履歴資料であり、
現在の仕様や操作方法の正本ではありません。
