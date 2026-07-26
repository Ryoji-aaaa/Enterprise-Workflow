# Phase 8最終報告書

作成日: 2026-07-26

## Phase 8 完了報告

### 実装内容

- Plan指定の最終受け入れ条件30項目を既存状態と空volumeの両方で検証した
- 通常開発用volumeを保持し、未使用の専用Compose projectでクリーン再構築した
- クリーン再構築後に`make init`を再実行し、Realm、ユーザー、DB初期データの冪等性を確認した
- backend、frontend、E2E、Phase 3〜5、health、HTTP、実network境界を最終確認した
- runtime imageの非root user、secret非内包、生成物と`.env`のGit除外を確認した
- KeycloakからMicrosoft Entra IDへ移行する場合の変更境界を文書化した
- 本番前に必要なsecret、TLS、Cookie、proxy、DB、メール、監視の変更を文書化した

### 作成・変更ファイル

- `README.md`
- `docs/README.md`
- `docs/production-readiness.md`
- `docs/phase8-final-report.md`

アプリケーション、認証、DB schema、Composeの通信境界には変更を加えていない。

### 実行コマンド

既存状態を次で確認した。

```bash
docker compose --profile init --profile test config --quiet
bash -n keycloak/scripts/*.sh scripts/*.sh
git diff --check
make verify
make phase3-check
make phase4-check
make phase5-check
```

通常の`workflow_postgres-data`を保持したまま通常コンテナを停止し、存在しないことを
確認した専用projectでPlan指定の一連の操作を実行した。

```bash
make clean
make setup
COMPOSE_PROJECT_NAME=workflow-phase8-20260726 make init
COMPOSE_PROJECT_NAME=workflow-phase8-20260726 make test
COMPOSE_PROJECT_NAME=workflow-phase8-20260726 make init
COMPOSE_PROJECT_NAME=workflow-phase8-20260726 make verify
```

検証後は専用projectと専用volumeだけを削除し、通常環境を復帰した。

```bash
COMPOSE_PROJECT_NAME=workflow-phase8-20260726 \
  docker compose down --volumes --remove-orphans
make up
make verify
```

依存、image、Git管理状態も個別に監査した。

```bash
docker run --rm workflow-frontend-test:latest npm audit --json
docker compose --profile test run --rm --no-deps e2e npm audit --audit-level=moderate
docker image inspect workflow-frontend:latest
docker image inspect workflow-backend:latest
git check-ignore .env keycloak/generated/realm.json \
  tests/e2e/test-results/results tests/e2e/playwright-report/report
```

### テスト結果

- `make clean`: 成功。通常volumeを保持
- `make setup`: 成功。既存`.env`を上書きせず、サンプルsecretを警告
- 空volumeからの`make init`: 成功
- クリーン状態での`make test`: 成功
- クリーン状態での`make init`再実行: 成功
- `make verify`: 既存、クリーン、復帰後のすべてで成功
- Phase 3、4、5 check: すべて成功
- Spring Boot: 13件成功、失敗0件
- frontend: lint、typecheck、単体テスト、production build成功
- Playwright: 7件成功、失敗0件
- 一般、管理者、未登録ユーザーの実ログイン: 成功
- BFF経由の`/api/me`: 成功
- ログアウトとログアウト後のTop拒否: 成功
- 未登録申請の重複防止とMailpit通知抑制: 成功
- JWTなしアクセス: backendとBFFでHTTP 401
- backendとPostgreSQLのホスト公開ポート: なし
- frontendからPostgreSQLへの経路: なし
- 最終状態: 通常`workflow` projectの5サービスすべてhealthy

### 受け入れ条件

| # | 条件 | 結果 |
| ---: | --- | --- |
| 1 | `~/projects/workflow`にモノレポが存在 | 成功 |
| 2 | `make init`で初期構築 | 成功 |
| 3 | `make up`で全サービス起動 | 成功 |
| 4 | `http://localhost:3000`にログイン画面 | 成功 |
| 5 | Next.jsにID・password入力欄なし | 成功 |
| 6 | ログインボタンからKeycloakへ遷移 | 成功 |
| 7 | 一般ユーザーでログイン | 成功 |
| 8 | 管理者ユーザーでログイン | 成功 |
| 9 | 認証後にTopへ遷移 | 成功 |
| 10 | TopにSpring Boot `/api/me`結果を表示 | 成功 |
| 11 | Spring BootがKeycloak JWTを検証 | 成功 |
| 12 | Spring BootがPostgreSQLから利用者を取得 | 成功 |
| 13 | 業務ロールをPostgreSQLで管理 | 成功 |
| 14 | Next.jsからPostgreSQLへ接続しない | 成功 |
| 15 | ブラウザからSpring Bootへ直接接続しない | 成功 |
| 16 | Spring Bootをホスト公開しない | 成功 |
| 17 | PostgreSQLをホスト公開しない | 成功 |
| 18 | 未ログインでTopを表示しない | 成功 |
| 19 | ログアウト後にTopを表示しない | 成功 |
| 20 | Keycloak登録・業務DB未登録ユーザーが403 | 成功 |
| 21 | 未登録アクセスをDBへ記録 | 成功 |
| 22 | 管理者通知をMailpitへ送信 | 成功 |
| 23 | 許可domain外をUser Profileで拒否 | 成功 |
| 24 | Keycloak Self Registration無効 | 成功 |
| 25 | `.env`をGit管理しない | 成功 |
| 26 | `.env.example`をGit管理 | 成功 |
| 27 | `make test`で全自動テスト | 成功 |
| 28 | Playwright必須シナリオ | 成功 |
| 29 | READMEの手順だけで再現 | 成功 |
| 30 | クリーンなDocker volumeから再構築 | 成功 |

### 採用バージョン

- Docker Engine 29.6.2
- Docker Compose 5.3.1
- Node.js 24.18.0
- npm 11.16.0
- Next.js 16.2.11
- Better Auth 1.6.25
- React / React DOM 19.2.4
- TypeScript 5.9.3
- Java 21
- Maven 3.9.16
- Spring Boot 4.1.0
- PostgreSQL 18.4
- Keycloak 26.7.0
- Mailpit v1.30.5
- Playwright 1.62.0
- Keycloak初期化用curl image 8.17.0

### 起動方法

```bash
cd ~/projects/workflow
cp .env.example .env
make setup
make init
```

通常起動は`make up`、停止は`make down`、状態確認は`make verify`、
全テストは`make test`、完全再構築は`make reset`を使用する。

### テストアカウント

- 管理者: `example.admin1@sdcj.co.jp` / `password`
- 一般ユーザー: `example.user1@sdcj.co.jp` / `password`
- 業務DB未登録: `example.pending1@sdcj.co.jp` / `password`

すべて隔離されたローカル開発専用であり、本番では作成しない。

### アクセスURL

- Application: http://localhost:3000
- Keycloak: http://localhost:8180
- Mailpit: http://localhost:8025

Spring BootとPostgreSQLにホスト公開URLはない。

### Better AuthステートレスOIDC構成

- database adapterを使用しない
- Generic OAuthのAuthorization Code FlowとPKCEでKeycloakへ接続する
- OAuth state、session、provider accountを署名・暗号化Cookieへ保存する
- CookieはHTTP-only、SameSite=Laxで、productionではSecureにする
- access tokenはNext.jsサーバーで取得し、Spring BootへのBFF通信だけに使用する
- access token、refresh token、ID tokenをブラウザやlocal storageへ渡さない
- Next.jsはPostgreSQLへ接続せず、業務情報をSpring Bootから取得する
- logout時はBetter Auth Cookieを失効し、Keycloak logoutへ遷移する

### KeycloakからEntra IDへの移行

詳細は[`production-readiness.md`](production-readiness.md)に記載した。主な変更箇所は
Generic OAuth provider設定、endpointとcredential、logout、Spring Resource Serverの
issuer・JWKS・audience・tenant検証、claim正規化、E2Eログイン操作である。

業務ロールはPostgreSQLに残す。`issuer + external_subject`が変わるため、emailだけで
権限を自動移行せず、監査可能な対応表とDB migrationを用意する。

### 本番利用前に変更が必要な設定

- 全サンプルsecretとpasswordをsecret manager管理の値へ置換する
- HTTPS、HSTS、CSPと本番origin・redirect URIを設定する
- reverse proxy headerの信頼境界と共有rate limitを設定する
- 開発seedを無効にし、Mailpitを認証済みSMTP/TLSへ置換する
- DB runtime userからDDL権限を外し、backup・restore・暗号化を運用する
- image・依存の継続scan、監視、alert、credential rotation手順を用意する
- Keycloak継続利用時は管理consoleを限定し、管理者MFAを有効にする

### 依存監査

- frontend production依存: 脆弱性0件
- Playwright依存: 脆弱性0件
- frontend runtime image: ESLint、TypeScriptなどの開発依存を含まない
- frontend開発依存: high 9件、critical 0件
- 既知警告はESLint系build/lint toolchainに限定され、production runtimeへ含まれない
- 自動修正は互換性のないメジャーバージョン変更を伴うため適用していない

### 既知の制約・残課題

- Better AuthはDBなしのため、アプリ側から個別sessionを中央失効できない
- ローカルHTTP、Mailpit、サンプルユーザー、サンプルsecretは本番利用できない
- Keycloak 26.7.0のUser Profile更新では、存在しない
  `unmanagedAttributePolicy`を追加しない回避策を維持する
- frontend開発依存9件は互換性を確認できるメジャー更新時に再評価する
- プロトタイプ範囲のため、実ワークフロー機能と本番運用基盤は含まない

### 次のPhase

PlanのPhase 1〜8は完了した。追加作業は新しい要件として扱う。
