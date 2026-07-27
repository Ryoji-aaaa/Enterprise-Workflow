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
| `frontend` | Node.js 24.18.0 / Next.js 16.2.11でビルド | `3000:3000` | Next.js BFF |
| `backend` | Java 21 / Maven 3.9.16でビルド | なし | Spring Boot業務API |
| `e2e` | Node.js 24.18.0 / Playwright 1.62.0でビルド | なし | Chromium E2E |

`keycloak-init`は`init`プロファイルに属する一時サービスである。
通常起動には含めず、設定または検証時に`docker compose run --rm`で実行する。
生成したRealm JSONはCompose secretとしてKeycloakへ渡し、host側の`0600`を維持する。

ローカルとE2Eでは短時間に複数のOAuth loginを繰り返すため、Better Authのrate limitを
`BETTER_AUTH_RATE_LIMIT_ENABLED=false`で無効化する。Azureではこの変数を設定せず、
production既定のrate limitを有効なままにする。

ネットワークの許可・禁止経路は
[ネットワーク境界](../architecture/network-boundaries.md)を正本とする。

## 永続化

PostgreSQLの`/var/lib/postgresql`だけを名前付きボリューム`postgres-data`へ保存する。
KeycloakのRealmやユーザーもKeycloak専用DBを通じて同じボリューム内へ永続化される。

通常の再起動や検証ではボリュームを削除しない。`make reset`だけが明示的に
開発データを削除する破壊的操作である。

## healthcheck

- PostgreSQL: `pg_isready`
- Mailpit: `/readyz`
- Keycloak: 管理ポート9000の`/health/ready`を確認し、JSONの`status`が`UP`であることを検証
- backend: runtime image内から`/actuator/health`のHTTP 200と`UP`を確認
- frontend: Node.jsの`fetch`で`/login`のHTTP成功を確認

Keycloak公式イメージへ`curl`などを追加せず、Bashの`/dev/tcp`を使用する。

## 起動順序

`make init`は次の順序で処理する。

1. Keycloak startup import用JSONを生成し、frontend/backend imageをbuildする
2. PostgreSQL、Mailpit、Keycloakをhealthyまで待機する
3. 内部Admin REST APIでKeycloak設定を冪等更新する
4. backendを起動し、業務DBスキーマと開発ユーザーを冪等初期化する
5. frontendを起動する
6. HTTP応答、公開ポート、実network境界を検証する

待機時間は既定300秒で、失敗時は対象サービスと直近100行のログを表示する。
