# Workflow prototype

Next.jsをBFF、Spring Bootを業務API、KeycloakをOpenID Connect Provider、
PostgreSQLを業務データストアとするワークフローアプリのローカル開発用プロトタイプです。
FrontendのUI基盤にはTailwind CSSとshadcn/uiを使用します。
ブラウザへアクセストークンを公開せず、業務権限はPostgreSQLで管理します。

構成と設計判断の詳細は[技術文書索引](docs/README.md)を参照してください。
UI基盤の構成は[shadcn/ui・Tailwind CSS仕様](docs/frontend/shadcn-tailwind.md)に
記載しています。

## アーキテクチャ概要

```text
browser ──> Next.js BFF ──> Spring Boot ──> PostgreSQL
   │                              └───────> Mailpit
   └──────> Keycloak ────────────────────> PostgreSQL
```

Spring BootとPostgreSQLはホストへポートを公開しません。Next.jsはPostgreSQLへ
接続せず、業務データをSpring Boot APIから取得します。

## 前提条件

- UbuntuまたはWSL2
- Docker EngineとDocker Compose plugin
- GNU Make
- Git
- `curl`、`jq`、`openssl`、`envsubst`、`grep`
- 利用ポート: `3000`、`8180`、`8025`

Ubuntu/WSL2では`./scripts/install-host-dependencies.sh`でホスト依存を導入できます。
Node.js、Java、Maven、PostgreSQL、Keycloak、Playwrightはコンテナ内で実行します。

## 初回セットアップ

```bash
make setup
make init
```

`make setup`は未作成の場合だけ`.env.example`から`.env`を作成します。`.env`の
`replace-with-`で始まる値と開発用パスワードは、隔離されたローカル環境以外では
使用しないでください。

`make init`はイメージをビルドし、サービスのhealthを待機してKeycloakを冪等設定し、
全サービスとネットワーク境界を検証します。既存のRealm、ユーザー、DBデータが
あっても再実行できます。

## 起動・停止・リセット

```bash
make up
make down
make restart
make ps
make logs
```

`make down`はDocker volumeを保持します。開発データを削除して完全に作り直す場合だけ、
次を実行します。

```bash
make reset
```

`make reset`は現在のCompose projectのvolumeを削除する破壊的操作です。

## テスト

```bash
make verify
make test
make test-backend
make test-frontend
make test-e2e
```

`make test`はSpring Boot結合テスト、frontendのlint・型検査・production build・
production依存監査、Playwright E2E、E2E後のDB・通知・JWT・ネットワーク境界検証を
実行します。

Keycloak設定JSONだけを生成する場合は次を実行します。

```bash
make render-keycloak-config
```

## ローカルURL

| 用途 | URL |
| --- | --- |
| アプリケーション | <http://localhost:3000> |
| Keycloak | <http://localhost:8180> |
| Mailpit | <http://localhost:8025> |

Spring BootとPostgreSQLにホスト公開URLはありません。

## 開発用アカウント

| 用途 | ユーザー名 | パスワード |
| --- | --- | --- |
| 管理者 | `example.admin1@sdcj.co.jp` | `password` |
| 一般ユーザー | `example.user1@sdcj.co.jp` | `password` |
| 業務DB未登録 | `example.pending1@sdcj.co.jp` | `password` |

これらはローカル開発専用です。

## 主要な注意事項

- `.env`と`keycloak/generated/`はGit管理対象外です。
- `BETTER_AUTH_SECRET`を変更すると既存の認証Cookieは無効になります。
- Keycloak issuerは外部URLの`http://localhost:8180/realms/workflow`です。
- 起動失敗時は`make ps`と`make logs`で対象サービスを確認してください。
- 本番利用前の必須変更は[production readiness](docs/operations/production-readiness.md)を
  参照してください。
