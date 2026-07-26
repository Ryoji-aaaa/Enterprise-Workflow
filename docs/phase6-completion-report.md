# Phase 6完了報告書

作成日: 2026-07-26

## Phase 6 完了報告

### 実装内容

- frontendとbackendをproduction buildのruntime imageで実行するよう統一した
- frontend、backendとも非root実行とし、runtime imageから開発依存を除外した
- `.env`、生成物、build成果物をDocker build contextから除外した
- PostgreSQL、Mailpit、Keycloakを先に起動し、Keycloakを内部Admin REST APIで
  冪等設定してからbackend、frontendを起動する順序を実装した
- 全サービスのhealthcheckをComposeの`service_healthy`条件と有限時間の待機処理へ
  統合した
- 待機失敗時に失敗サービス名、状態、直近100行のログを表示するようにした
- `make setup`、`make init`、`make up`、`make down`、`make restart`、
  `make verify`、`make reset`を完成させた
- Makefile内の複雑なシェル処理を`scripts`配下へ分離した
- `make verify`へCompose定義と実コンテナの両方を対象とする次の検証を追加した
  - PostgreSQL readiness、DB/role分離、業務初期ユーザー
  - Mailpit health
  - Keycloak `/health/ready`、Realm discovery、Realm/Client/User Profile
  - backend `/actuator/health`と未認証`/api/me`
  - frontend `/login`とfrontendからbackendへの内部疎通
  - backendとPostgreSQLのホストポート非公開
  - frontendへDB接続環境変数がないこと
  - frontendからPostgreSQLを名前解決できないこと
  - 各コンテナの実network接続と内部network属性
- 外部指定の`COMPOSE_PROJECT_NAME`を初期化・検証スクリプトでも維持し、
  reset検証を通常開発環境から隔離できるようにした
- READMEへ起動、停止、再起動、検証、完全再構築、URL、テストアカウント、
  起動待ち、ポート競合、volume、Cookie、issuerの内部・外部URL差異を記載した

### 作成・変更ファイル

- `.dockerignore`
- `backend/.dockerignore`
- `backend/Dockerfile`
- `frontend/.dockerignore`
- `frontend/Dockerfile`
- `docker-compose.yml`
- `Makefile`
- `scripts/setup.sh`
- `scripts/init.sh`
- `scripts/up.sh`
- `scripts/wait-for-services.sh`
- `scripts/reset.sh`
- `scripts/clean.sh`
- `scripts/test-backend.sh`
- `scripts/test-frontend.sh`
- `scripts/install-host-dependencies.sh`
- `scripts/verify.sh`
- `scripts/phase5-check.sh`
- `keycloak/scripts/initialize-keycloak.sh`
- `README.md`
- `docs/README.md`
- `docs/phase6-completion-report.md`

### 実行コマンド

```bash
docker compose --profile init config --quiet
bash -n keycloak/scripts/*.sh scripts/*.sh
git diff --check
make setup
make build
make init
make verify
make init
make up
make verify
make phase3-check
make phase4-check
make phase5-check
make restart
make verify
make test-frontend
```

完全再構築は通常の`workflow_postgres-data`を保持したまま、専用projectで実行した。

```bash
make down
COMPOSE_PROJECT_NAME=workflow-phase6-reset make reset
COMPOSE_PROJECT_NAME=workflow-phase6-reset make verify
COMPOSE_PROJECT_NAME=workflow-phase6-reset make phase3-check
COMPOSE_PROJECT_NAME=workflow-phase6-reset make phase4-check
COMPOSE_PROJECT_NAME=workflow-phase6-reset make phase5-check
COMPOSE_PROJECT_NAME=workflow-phase6-reset docker compose down --volumes --remove-orphans
make up
make verify
```

### テスト結果

- `make setup`: 成功。既存`.env`を維持し、サンプルsecretを警告
- `make build`: 成功。frontend/backendともproduction runtime imageを生成
- `make init`: 成功
- 既存状態での`make init`再実行: 成功
- `make up`: 成功。全サービスhealthyまで有限時間で待機
- `make down`: 成功。通常volumeを保持
- `make restart`: 成功
- `make verify`: 成功
- 専用projectでの`make reset`: 成功。空volumeから完全再構築
- クリーン再構築後のPhase 3〜5検証: すべて成功
- Spring Bootテスト: 13件成功、失敗0件
- frontendテスト: 7件成功、lint、typecheck、production build成功
- 一般ユーザーのKeycloakログイン: 成功
- BFF経由の`/api/me`: HTTP 200、業務ユーザーと`USER`ロールを確認
- 一般ユーザーのログアウト: 成功。Cookie失効後の`/top`非表示を確認
- 管理者ログインと`ADMIN`ロール: 成功
- 業務DB未登録ユーザー: HTTP 403、未登録画面と管理者への通知済み表示を確認
- backendとPostgreSQLのホスト公開ポート: なし
- frontendからPostgreSQLへのnetwork経路: なし
- frontend runtime user: `node`
- backend runtime user: `10001`
- `.env`、Keycloak生成設定、Docker volume: Git管理対象外
- 最終状態: 通常`workflow` projectの5サービスすべてhealthy

### 依存監査

- frontend production依存: `npm audit --omit=dev`で脆弱性0件
- frontend runtime image: `eslint`と`typescript`を含まないことを確認
- 開発依存を含む監査: high 9件、critical 0件
- 対象はESLint系build/lint toolchainの
  `eslint`、`eslint-config-next`、`@eslint/config-array`、
  `@eslint/eslintrc`、`eslint-plugin-import`、`eslint-plugin-jsx-a11y`、
  `eslint-plugin-react`、`minimatch`、`brace-expansion`
- 内容は`brace-expansion`の無制限展開によるDoSが`minimatch`経由で伝播するもの
- 影響範囲は開発時のlint/buildコンテナであり、production runtime imageには
  該当する開発依存を含めていない
- npmが提示する自動修正はESLint 10または互換性のない
  `eslint-config-next`のメジャー変更を伴うため、独断で適用していない

### 採用バージョン

- Node.js 24.18.0
- Next.js 16.2.11
- Better Auth 1.6.25
- React / React DOM 19.2.4
- Java 21
- Maven 3.9.16
- Spring Boot 4.1.0
- PostgreSQL 18.4
- Keycloak 26.7.0
- Mailpit v1.30.5
- Keycloak初期化用curl image 8.17.0
- Playwright 1.62.0（Phase 7で使用予定）

### 起動方法

```bash
cd ~/projects/workflow
cp .env.example .env
make setup
make init
```

通常起動は`make up`、停止は`make down`、状態確認は`make verify`、完全再構築は
`make reset`を使用する。

### アクセスURL

- Application: http://localhost:3000
- Keycloak: http://localhost:8180
- Mailpit: http://localhost:8025

### テストアカウント

- 管理者: `example.admin1@sdcj.co.jp` / `password`
- 一般ユーザー: `example.user1@sdcj.co.jp` / `password`
- 業務DB未登録: `example.pending1@sdcj.co.jp` / `password`

### Better AuthステートレスOIDC構成の確認結果

- Better Auth用DB接続はない
- Generic OAuthのAuthorization Code FlowでKeycloakと接続する
- access tokenはサーバー側で使用し、ブラウザレスポンスへ露出しない
- session/account情報は暗号化されたHTTP-only Cookieに保存する
- `/api/me`はNext.js BFF経由でのみSpring Bootへ到達する
- logout時はBetter Auth Cookieを失効させ、Keycloak logoutへ遷移する

### 残課題

- PlaywrightのE2Eシナリオと`make test-e2e`の完成はPlanどおりPhase 7で行う
- 開発依存9件は、Next.jsとESLintの互換性が確認できるメジャー更新時に再評価する
- `.env.example`のサンプルsecretとテストアカウントpasswordは本番利用できない

### 次のPhase

Phase 7のPlaywright実装へ進む。Phase 6完了報告後にのみ開始する。
