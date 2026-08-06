# Workflow prototype

Next.jsをBFF、Spring Bootを業務API、KeycloakをOpenID Connect Provider、
PostgreSQLを業務データストアとするワークフローアプリのローカル開発用プロトタイプです。
FrontendのUI基盤にはTailwind CSSとshadcn/uiを使用します。
ブラウザへアクセストークンを公開せず、業務権限はPostgreSQLで管理します。

構成と設計判断の詳細は[技術文書索引](docs/README.md)を参照してください。
UI基盤の構成は[shadcn/ui・Tailwind CSS仕様](docs/frontend/shadcn-tailwind.md)に
記載しています。

経費精算申請PoCは、会食費・交通費・研修費・資格受験費・その他経費の下書き、申請、
領収書・証憑の添付、部門長・経理承認、差戻し、再申請、取下げを提供します。申請者画面は`/expenses`、
承認者画面は`/approvals`です。仕様は
[Backend経費申請](docs/backend/expense-application.md)と
[Frontend経費申請](docs/frontend/expense-application.md)を参照してください。

## アーキテクチャ概要

```text
browser ──> Next.js BFF ──> Spring Boot ──> PostgreSQL
   │                              ├───────> Mailpit
   │                              └───────> Azurite / Azure Blob Storage
   └──────> Keycloak ────────────────────> PostgreSQL
```

Spring Boot、PostgreSQL、Azuriteはホストへポートを公開しません。Next.jsはPostgreSQLや
Blob Storageへ接続せず、業務データと証憑をSpring Boot APIから取得します。

## 前提条件

- UbuntuまたはWSL2
- Docker EngineとDocker Compose plugin
- GNU Make
- Git
- `curl`、`jq`、`openssl`、`envsubst`、`grep`、`timeout`
- Terraform CLI（`make verify-infra`とインフラ作業時のみ）
- 利用ポート: `3000`、`8180`、`8025`

Ubuntu/WSL2では`bash scripts/install-host-dependencies.sh`でホスト依存を導入できます。
Ubuntu標準のDockerパッケージが導入済みの場合は、イメージ、コンテナ、volumeを
保持したまま、実行中のコンテナを停止してBuildxとComposeを含むDocker公式
パッケージへ自動的に置き換えます。
Terraform CLIはこのスクリプトの導入対象外で、インフラ検証・作業時だけ別途必要です。
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

Spring Bootの起動時にFlywayが未適用のDB migrationを実行します。適用履歴は
PostgreSQLの`flyway_schema_history`で管理します。Flyway導入前の業務DBを含むvolumeは
そのまま移行せず、開発データを削除できることを確認して`make reset`で再作成してください。
追加方法と運用ルールは[Flyway仕様](docs/backend/flyway.md)を参照してください。

## 起動・停止・リセット

```bash
make up
make down
make restart
make ps
make logs
```

`make down`はDocker volumeと生成物を保持します。開発データを残したまま生成物だけを
削除する場合は次を実行します。

```bash
make clean
```

`make clean`はサービスを停止してFrontend・Backend・Playwright・Keycloak生成物を削除しますが、
Docker volumeと開発DBは保持します。開発データも削除する破壊的な再初期化は次だけです。

```bash
make reset
```

`make reset`は現在のCompose projectのvolumeを削除する破壊的操作です。PostgreSQL、Keycloak、
Azuriteの開発データが対象になります。

## テスト

```bash
make test
make test SUITES=backend
make test SUITES=frontend
make test SUITES=keycloak
make test SUITES=e2e
```

`make test`はBackend、Frontend、Keycloak、E2Eを固定順で実行し、テスト件数と必須checkを
分けた統一レポートを生成します。正常時は各処理の進捗と集計だけを表示し、生ログは
`test-results/<run-id>/`へ保存します。複数suiteは`SUITES=backend,frontend`のように指定し、
生ログを同時表示する場合は`VERBOSE=1`、失敗後も隔離環境、volume、run固有image、一時envを
残す場合は`KEEP_TEST_ENV=1`を使います。保持はローカル調査専用で、CIでは使用できません。
詳細は[統括テスト実行仕様](docs/testing/test-execution.md)を参照してください。

起動済みのローカル統合環境とアーキテクチャ境界を確認する場合は、テストコードを実行する
`make test`ではなく次を使用します。

```bash
make verify
```

`make verify`はコンテナhealth、PostgreSQL・Keycloak・Azuriteの初期化、Flywayと開発seed、
FrontendからBackendへの接続、DB資格情報の非注入、公開ポート・Docker network・非root実行を
検証します。`./scripts/verify.sh backend frontend`のようにサービスを指定した部分検証も可能です。

Terraformのformat・validateと、Backend probe、内部URL、staging限定seed Jobなどの
インフラ不変条件は次で検証します。Terraformの初期化により各rootへ`.terraform/`が生成されます。

```bash
make verify-infra
```

旧`make terraform-check`は互換エイリアスとして警告付きで残しています。新しい手順とCIでは
`make verify-infra`を使用します。

production npm依存関係の既知の脆弱性はテストと分離して監査します。

```bash
make audit
make audit-frontend
make audit-e2e
```

`make audit`はFrontendとPlaywright E2Eの両方を対象にし、個別ターゲットは対象を限定します。
いずれもDocker内で実行するため、ホストのNode.jsは不要ですが、npm registryへの接続が必要です。

コマンド名は、テストコード・静的解析・build検証を`test`、起動環境・生成結果・設計境界の確認を
`verify`、依存脆弱性検査を`audit`とします。`check`は単一目的の軽量な静的検査用で、現在は
`verify-infra`から呼ぶ内部スクリプトに限定し、公開Makeターゲットを増やしていません。

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

## Azure CI/CD

Azure Container Apps向けTerraformとGitHub Actionsを`infra/`と
`.github/workflows/`に分離しているため、ローカルComposeの起動方法とnetwork境界は
変わりません。stagingはmainのCI成功後に自動deployし、productionはstagingで検証した
commit SHAを手動昇格します。`latest`は使用しません。

初回構築は[Terraform手順](infra/README.md)、全体構成は
[Azure architecture](docs/infrastructure/azure-architecture.md)、OIDCとGitHub設定は
[GitHub Actions手順](docs/infrastructure/github-actions.md)を参照してください。
AzureにはMailpitを配置せず、メールサービスは別途決定します。
