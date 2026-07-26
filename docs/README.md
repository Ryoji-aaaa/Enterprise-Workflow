# 実装仕様書

このディレクトリでは、ワークフロープロトタイプの実装済み仕様を使用技術ごとに記録する。
受け入れ条件とPhaseごとの作業順序は、リポジトリ直下の
[`init_tasks.md`](../init_tasks.md)を正とする。

## 実装済み仕様

| 分類 | 仕様書 | 主な内容 |
| --- | --- | --- |
| コンテナ | [Docker Compose](docker-compose.md) | サービス、起動順序、ネットワーク、永続化、healthcheck |
| データベース | [PostgreSQL](postgresql.md) | DBとロールの分離、初期化、接続制限 |
| 認証 | [Keycloak / OpenID Connect](keycloak.md) | Realm、Client、User Profile、初期化・検証 |
| バックエンド | [Spring Boot](spring-boot.md) | Resource Server、業務ユーザー、未登録通知、API |
| 開発運用 | [Make / Shell](development-tools.md) | ホスト依存、主要コマンド、検証スクリプト |
| frontend | [Next.js / Better Auth](nextjs-better-auth.md) | ステートレスOIDC、BFF、画面遷移、ログアウト |
| E2E | [Playwright](playwright.md) | ブラウザシナリオ、Mailpit・DB事後検証、成果物 |

## Phase 6

- [Phase 6完了報告書](phase6-completion-report.md)

## Phase 7

- [Playwright仕様](playwright.md)
- [Phase 7完了報告書](phase7-completion-report.md)

今後のPhaseで技術を追加した場合も、実装と同じコミット単位で本ディレクトリを更新する。
