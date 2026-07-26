# Phase 7完了報告書

作成日: 2026-07-26

## Phase 7 完了報告

### 実装内容

- Node.js 24.18.0、Playwright 1.62.0、Chromiumを固定したE2E専用imageを追加した
- Composeの`test` profileへ`e2e`サービスを追加し、通常起動から分離した
- OAuthの外部URLとCookie hostを実ブラウザと同じ`localhost`へ統一した
- `make test-e2e`を専用スクリプトへ統合し、次を自動実行するようにした
  - 全サービスの有限health待機
  - 内部Admin REST APIによるKeycloakの冪等設定
  - E2E imageのbuild
  - 未登録テストユーザーと対象Mailpit通知だけの事前初期化
  - Playwright headless実行
  - DB、Mailpit、JWT拒否、公開ポート、実network境界の事後検証
- 各テストを独立したBrowserContextで実行し、Cookieとセッションを分離した
- 固定sleepを使わず、画面と通知はPlaywrightのexpectで有限時間待機するようにした
- Better Authの連続ログイン制限で429になった場合だけ、
  `x-retry-after`に従う有限1回リトライを実装した
- 失敗時だけtrace、スクリーンショット、videoを保持し、HTML reportを生成するようにした
- READMEと技術仕様へE2Eの実行方法、成果物、Mailpit、network、
  レート制限のトラブルシュートを記載した

### 実装シナリオ

1. 未認証の`/top`アクセスを`/login`へ遷移し、Top情報を表示しない
2. 一般ユーザーのKeycloakログイン後、氏名、email、所属、一般ロールを表示する
3. 管理者ログイン後、DB由来の管理者ロールを表示する
4. Better AuthとKeycloakからログアウトし、`/top`へ戻れない
5. 業務DB未登録ユーザーを申請画面へ遷移し、Mailpitの管理者通知を確認する
6. 未登録ユーザーの再アクセスで申請行を増やさず、回数だけを更新し、通知を増やさない
7. Spring Bootへホストから直接接続できない
8. 未認証のBFF `/api/backend/me`とJWTなしのSpring Boot `/api/me`が401になる

シナリオ5と6は同じ隔離状態を連続して検証するため、1件のPlaywright testに統合した。
Playwrightの実行件数は7件である。

### 作成・変更ファイル

- `Makefile`
- `docker-compose.yml`
- `scripts/prepare-e2e.sh`
- `scripts/test-e2e.sh`
- `scripts/verify-e2e.sh`
- `tests/e2e/.dockerignore`
- `tests/e2e/Dockerfile`
- `tests/e2e/package.json`
- `tests/e2e/package-lock.json`
- `tests/e2e/playwright.config.ts`
- `tests/e2e/specs/workflow.spec.ts`
- `README.md`
- `docs/README.md`
- `docs/development-tools.md`
- `docs/docker-compose.md`
- `docs/playwright.md`
- `docs/phase7-completion-report.md`

### 実行コマンド

```bash
docker compose --profile init --profile test config --quiet
bash -n keycloak/scripts/*.sh scripts/*.sh
git diff --check
docker compose --profile test run --rm --no-deps e2e npm audit --audit-level=moderate
make test-e2e
make test
make verify
make phase3-check
make phase4-check
make phase5-check
```

### テスト結果

- Compose構文: 成功
- 全シェル構文: 成功
- `git diff --check`: 成功
- `make test-e2e`: 7件成功、失敗0件
- Spring Bootテスト: 13件成功、失敗0件
- frontend: lint、typecheck、単体テスト、production build成功
- `make test`: 成功
- `make verify`: 成功
- Phase 3、4、5 check: すべて成功
- 一般ユーザーの実ブラウザログインとBFF `/api/me`: 成功
- 管理者の実ブラウザログインと`ADMIN`ロール表示: 成功
- ログアウトとログアウト後の`/top`拒否: 成功
- 未登録ユーザーの申請: 1行、`request_count` 2以上
- Mailpitの対象通知: 1件。再アクセスで増加なし
- JWTなしのSpring Boot `/api/me`: HTTP 401
- 未認証のBFF `/api/backend/me`: HTTP 401
- Spring Bootのホスト`127.0.0.1:8080`: 接続不可
- backendとPostgreSQLのホスト公開ポート: なし
- 最終状態: 通常`workflow` projectの5サービスすべてhealthy

### 依存監査

- Playwright依存: `npm audit --audit-level=moderate`で脆弱性0件
- frontend production依存: `npm audit --omit=dev`で脆弱性0件
- frontend開発依存を含む既知警告: high 9件、critical 0件
- 既知警告はPhase 6報告時と同じESLint系build/lint toolchainに限定される
- production runtime imageには対象の開発依存を含めていない
- 自動修正はメジャーバージョン変更を伴うため適用していない

### 成果物とGit管理

失敗時の調査成果物は次へ生成する。

```text
tests/e2e/test-results/results/
tests/e2e/playwright-report/report/
```

Playwright成果物、`.env`、Keycloak生成設定、Docker volumeはGit管理対象外である。
E2E imageへ`.env`やsecretをCOPYせず、認証情報は実行時環境変数で渡す。

### 残課題

- frontend開発依存9件は、Next.jsとESLintの互換性が確認できるメジャー更新時に再評価する
- `.env.example`のサンプルsecretとテストアカウントpasswordは本番利用できない

### 次のPhase

Phase 8へはPhase 7完了報告の確認後に進む。
