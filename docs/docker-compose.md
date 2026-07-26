# Docker Compose仕様

## 採用バージョン

- Docker Engine 29.6.2で動作確認
- Docker Compose 5.3.1で動作確認
- Composeプロジェクト名の既定値は`workflow`

アプリケーション依存のNode.js、Java、Mavenなどはホストへ導入せず、
各コンテナ内で固定する。

## サービス

| サービス | イメージまたはビルド | 公開ポート | 現在の責務 |
| --- | --- | --- | --- |
| `postgres` | `postgres:18.4` | なし | Workflow DBとKeycloak DB |
| `keycloak` | `quay.io/keycloak/keycloak:26.7.0` | `8180:8080` | OIDC認証基盤 |
| `keycloak-init` | `curlimages/curl:8.17.0`を基にビルド | なし | Admin REST APIによる設定・検証 |
| `mailpit` | `axllent/mailpit:v1.30.5` | `8025:8025` | 開発用メール確認 |
| `frontend` | Phase 5で実装 | `3000:3000` | Next.js BFF |
| `backend` | Phase 4で実装 | なし | Spring Boot業務API |
| `e2e` | Phase 7で実装 | なし | Playwright |

`keycloak-init`は`init`プロファイルに属する一時サービスである。
通常起動には含めず、設定または検証時に`docker compose run --rm`で実行する。

## ネットワーク境界

- `public-network`: ホスト公開が必要なKeycloak、Mailpitと、今後のfrontend
- `application-network`: frontend、backend、Mailpit間
- `database-network`: PostgreSQLへ接続するbackendとKeycloak

`application-network`と`database-network`は`internal: true`である。
PostgreSQLとSpring Bootのポートはホストへ公開しない。

## 永続化

PostgreSQLの`/var/lib/postgresql`だけを名前付きボリューム`postgres-data`へ保存する。
KeycloakのRealmやユーザーもKeycloak専用DBを通じて同じボリューム内へ永続化される。

通常の再起動やPhase検証ではボリュームを削除しない。`make reset`だけが明示的に
開発データを削除する破壊的操作である。

## healthcheck

- PostgreSQL: `pg_isready`
- Mailpit: `/readyz`
- Keycloak: 管理ポート9000の`/health/ready`を確認し、JSONの`status`が`UP`であることを検証

Keycloak公式イメージへ`curl`などを追加せず、Bashの`/dev/tcp`を使用する。
