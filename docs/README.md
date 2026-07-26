# 実装仕様書

このディレクトリでは、ワークフロープロトタイプの実装済み仕様を使用技術ごとに記録する。
受け入れ条件とPhaseごとの作業順序は、リポジトリ直下の
[`init_tasks.md`](../init_tasks.md)を正とする。

## Phase 1〜3

| 分類 | 仕様書 | 主な内容 |
| --- | --- | --- |
| コンテナ | [Docker Compose](docker-compose.md) | サービス、ネットワーク、永続化、healthcheck |
| データベース | [PostgreSQL](postgresql.md) | DBとロールの分離、初期化、接続制限 |
| 認証 | [Keycloak / OpenID Connect](keycloak.md) | Realm、Client、User Profile、初期化・検証 |
| 開発運用 | [Make / Shell](development-tools.md) | ホスト依存、主要コマンド、検証スクリプト |

今後のPhaseで技術を追加した場合も、実装と同じコミット単位で本ディレクトリを更新する。
