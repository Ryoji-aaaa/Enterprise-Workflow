# Workflow prototype

Keycloakで認証し、Next.jsのBFFを経由してSpring Bootから業務ユーザー情報を取得する、
ローカル開発用ワークフローアプリのプロトタイプです。

## Status

実装中です。現在の詳細な実装計画と受け入れ条件は
[`init_tasks.md`](./init_tasks.md)を参照してください。
実装済み仕様は使用技術別に[`docs`](./docs/README.md)へ記録しています。

## Prerequisites

- Windows 11 / WSL2
- Ubuntu 24.04 LTS
- Docker Engine and Docker Compose V2
- GNU Make
- Git

ホスト側の依存関係は次のスクリプトで導入できます。

```bash
./scripts/install-host-dependencies.sh
```

Node.js、Java、Mavenなどのアプリケーション依存関係はコンテナ内で管理します。

## Initial setup

```bash
cp .env.example .env
make setup
```

`.env`内の`replace-with-`で始まる値は、サービスを起動する前にローカル専用の
ランダムな値へ変更してください。`.env`はGitの管理対象外です。

## Planned commands

```bash
make init
make up
make down
make test
make verify
```

各コマンドの一覧は`make help`で確認できます。

## Planned local URLs

- Application: http://localhost:3000
- Keycloak: http://localhost:8180
- Mailpit: http://localhost:8025

PostgreSQL is not published to the host. The single PostgreSQL container creates
separate `workflow` and `keycloak` databases with separate login roles. Its
initialization scripts run only when the development volume is empty; `make reset`
will eventually recreate that volume.

The Keycloak realm disables self-registration, implicit flow, and direct access
grants. Its confidential OIDC client requires Authorization Code Flow with PKCE
S256. Realm configuration is rendered from environment variables into the
ignored `keycloak/generated/` directory before startup.

Realmの初回作成にはstartup importを使用し、import後の設定更新と検証には
内部ネットワーク上のKeycloak Admin REST APIを使用します。`kcadm.sh`には
依存しません。検証スクリプトはGETのみを実行します。

### Known limitation

Keycloak 26.7.0では、GETしたUser Profile設定に
`unmanagedAttributePolicy`が含まれていない状態から`"DISABLED"`を追加して
PUTするとHTTP 400になることを確認しています。そのため、未管理属性ポリシーは
Keycloakのデフォルト状態を維持し、初期化処理では存在有無と既存値を変更しません。

## Local development accounts

以下はローカル開発専用であり、本番環境では使用しません。

- Administrator: `example.admin1@sdcj.co.jp` / `password`
- User: `example.user1@sdcj.co.jp` / `password`
