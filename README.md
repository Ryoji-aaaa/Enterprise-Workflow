# Workflow prototype

Keycloakで認証し、Next.jsのBFFを経由してSpring Bootから業務ユーザー情報を
取得するローカル開発用ワークフローアプリです。ブラウザへアクセストークンを
渡さず、Next.jsから業務DBへ直接接続しない構成です。

詳細な実装計画と受け入れ条件は[`init_tasks.md`](./init_tasks.md)、技術別の仕様は
[`docs`](./docs/README.md)を参照してください。

この構成はローカル開発用プロトタイプです。本番投入前に必要なsecret管理、TLS、
開発用seed・Mailpitの除外、監視、バックアップと、KeycloakからEntra IDへ移行する
場合の変更点は[`docs/production-readiness.md`](docs/production-readiness.md)に
まとめています。

## 前提条件

- Windows 11 / WSL2
- Ubuntu 24.04 LTS
- Docker EngineとDocker Compose V2
- GNU Make
- Git、curl、jq、envsubst、ripgrep

ホスト依存コマンドは`./scripts/install-host-dependencies.sh`で導入できます。
Node.js、Java、Mavenはコンテナ内で管理するため、ホストへの導入は不要です。

## 初回起動

```bash
cd ~/projects/workflow
cp .env.example .env
make setup
make init
```

`make setup`は必須コマンドとDocker daemonを確認し、必要なディレクトリと
スクリプト権限を準備します。`.env`がない場合だけ`.env.example`から作成し、
既存の`.env`は上書きしません。

`.env`の`replace-with-`で始まる値と開発ユーザーの`password`はサンプルです。
隔離されたローカル開発以外で使う前に、十分な長さのランダム値へ変更してください。
`.env`と生成済みKeycloak設定はGit管理対象外です。

`make init`はイメージをbuildし、PostgreSQL、Mailpit、Keycloakの順に利用可能に
なるまで待機した後、Realm importを維持したまま内部Admin REST APIでKeycloakを
冪等に設定します。その後backendとfrontendを起動し、業務DB初期ユーザーと全HTTP
エンドポイントを検証します。既存のRealm、ユーザー、DBデータがあっても再実行
できます。

## 日常操作

```bash
# 通常起動（有限時間で全サービスのhealthyを待機）
make up

# 停止（Docker volumeは保持）
make down

# 再起動
make restart

# 状態と通信境界を検証
make verify

# 状態またはログを確認
make ps
make logs
```

起動待機の既定タイムアウトは300秒です。変更する場合は、たとえば
`COMPOSE_WAIT_TIMEOUT=600 make up`を実行します。失敗時はhealthyにならなかった
サービス名、コンテナ状態、直近100行のログを表示します。

## 完全再構築

```bash
make reset
```

`make reset`は通常の`make init`と異なる破壊的操作です。実行時に次の警告と対象の
Compose project名を表示し、そのprojectの開発用volumeを削除して完全再構築します。

```text
この処理は開発用のPostgreSQLおよびKeycloakデータを削除します。
```

PostgreSQLの業務データ、KeycloakのRealm・ユーザーなど、そのvolume内のデータは
復元できません。自動検証では普段の`workflow` projectを使わず、専用の作業コピーと
Compose project名を使用してください。`make down`はvolumeを削除しません。

## URL

```text
Application: http://localhost:3000
Keycloak:    http://localhost:8180
Mailpit:     http://localhost:8025
```

Spring BootとPostgreSQLにはホスト公開ポートがありません。通信境界は次のとおりです。

```text
ブラウザ → Next.js
ブラウザ → Keycloak
Next.js → Spring Boot
Spring Boot → PostgreSQL
Spring Boot → Mailpit
Keycloak → Keycloak用DB
```

Next.jsとSpring Bootは`application-network`、Spring Boot、PostgreSQL、Keycloakは
用途に応じて`database-network`を使用します。内部networkはDockerの`internal`
networkです。`make verify`はCompose定義だけでなく、実コンテナの接続network、
公開ポート、frontendからbackendへの疎通、frontendからPostgreSQLを名前解決
できないことも確認します。

## ローカルテストアカウント

以下はローカル開発専用です。

- 管理者: `example.admin1@sdcj.co.jp` / `password`
- 一般ユーザー: `example.user1@sdcj.co.jp` / `password`
- 業務DB未登録ユーザー: `example.pending1@sdcj.co.jp` / `password`

一般ユーザーと管理者はログイン後にBFF経由の`/api/me`結果を表示します。未登録
ユーザーは403となり、利用申請が業務DBへ記録され、Mailpitへ通知されます。

## 検証とテスト

```bash
make verify
make test-backend
make test-frontend
make test-e2e
make test
make phase3-check
make phase4-check
make phase5-check
```

`make test-e2e`はサービスをhealthyまで起動し、Keycloak設定を冪等更新してから、
Chromiumを含む専用コンテナでPlaywrightを実行します。一般・管理者・業務DB未登録
ユーザーのログイン、BFF、ログアウト、Mailpit通知、申請の重複防止、backendの
ホスト非公開、JWTなしの401を検証します。E2E前処理が削除するのは未登録テスト
ユーザーの利用申請と同じ件名のMailpitメッセージだけです。

失敗時のtrace、スクリーンショット、videoは`tests/e2e/test-results/results`、
HTML reportは`tests/e2e/playwright-report/report`へ保存されます。これらは生成物
としてGit管理しません。詳細は[`docs/playwright.md`](docs/playwright.md)を参照して
ください。

frontendのproduction依存監査は`make test-frontend`で`npm audit --omit=dev`を実行
します。Playwright依存もlockfileで固定し、Phase完了時に監査結果を記録します。
メジャーバージョン更新が必要な自動修正は行いません。

## 認証、Cookie、issuer

Better AuthはDBなしのGeneric OAuth構成です。セッションとアカウント情報は暗号化
されたHTTP-only Cookieに保存されるため、`BETTER_AUTH_SECRET`を変えると既存Cookie
は無効になります。Cookieやリダイレクトが不整合になった場合は、ブラウザの
`localhost:3000` Cookieを削除して再ログインしてください。

Keycloakがトークンへ設定するissuerはブラウザから到達できる外部URL
`http://localhost:8180/realms/workflow`です。一方、コンテナ間のdiscovery/JWKS取得
には`http://keycloak:8080`を使用します。これは同一Realmの内部・外部URLの違いで、
`KEYCLOAK_ISSUER`を内部URLへ変更してはいけません。Realmの初回作成はstartup import、
import後の設定更新・検証は内部Admin REST APIを使い、`kcadm.sh`には依存しません。

## トラブルシューティング

- 起動がタイムアウトした場合は、表示されたサービスのログを確認し、
  `make ps`と`make logs`を実行してください。
- `3000`、`8180`、`8025`が使用中なら、競合するプロセスまたは別のCompose projectを
  停止してから再実行してください。
- 停止後もデータが残るのはDocker volumeを保持するためです。volume名は通常
  `workflow_postgres-data`です。削除が必要な場合だけ`make reset`を使ってください。
- Keycloakのissuer、Better Auth URL、Cookieのホストはすべて`localhost`で揃えて
  ください。`127.0.0.1`との混在はCookieやOAuth stateの不一致原因になります。
- E2EのOAuth開始が429になった場合、テストはBetter Authの`x-retry-after`に従い
  1回だけ有限時間で再試行します。固定sleepや無限再試行は行いません。
- E2E失敗の詳細はHTML reportとtraceを確認してください。再実行時は前回の生成物を
  E2E前処理が削除します。
- Mailpit通知の確認に失敗した場合は`http://localhost:8025`で件名
  `[Workflow] 未登録ユーザーからアクセスがありました`を確認してください。
- WSL再起動後はDocker daemonが利用可能であることを`docker info`で確認してください。

## Keycloakの既知制約

Keycloak 26.7.0では、GETしたUser Profile設定に`unmanagedAttributePolicy`がない
状態から`"DISABLED"`を追加してPUTするとHTTP 400になります。そのため既存値を
変更せず、email必須設定と許可ドメインpatternだけを内部Admin REST APIで冪等に
更新します。
