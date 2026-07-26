# Playwright E2E仕様

## 実行方法

```bash
make test-e2e
```

ホストへNode.jsやブラウザを導入せず、Node.js 24.18.0、
Playwright 1.62.0、Chromiumを含む専用imageでheadless実行する。
Composeの`test` profileだけに`e2e`サービスを定義し、通常の`make up`では起動しない。

実行処理は次の順序で行う。

1. ローカル設定と全サービスのhealthを確認する
2. 内部Admin REST APIでKeycloak設定を冪等更新する
3. E2E imageをbuildする
4. 未登録テストユーザーの申請と対象Mailpit通知だけを初期化する
5. Playwrightを1 workerで実行する
6. DB、Mailpit、JWT拒否、Composeと実コンテナのnetwork境界を事後検証する

## ブラウザシナリオ

各テストは独立したBrowserContextを使用し、Cookieとセッションを共有しない。

1. 未認証で`/top`へアクセスすると`/login`へ遷移する
2. 一般ユーザーがKeycloakでログインし、氏名、email、所属、一般ロールを表示する
3. 管理者がログインし、DB由来の管理者ロールを表示する
4. ログアウトし、Keycloak確認後に`/top`へ戻れないことを確認する
5. 業務DB未登録ユーザーを申請画面へ遷移し、Mailpitの管理者通知を確認する
6. 同じ未登録ユーザーの再アクセスで行を増やさず、回数だけを更新し、通知を増やさない
7. ホストの`127.0.0.1:8080`からSpring Bootへ直接接続できないことを確認する
8. 未認証のBFF `/api/backend/me`が401を返すことを確認する

Playwright後のコンテナ内検証では、Spring Bootの`/api/me`へJWTなしで接続した場合も
401になることを確認する。

## 安定性と有限待機

- selectorはrole、label、表示テキストを優先する
- 画面遷移とMailpit通知はPlaywrightのexpectで有限時間待機する
- 固定sleepと無限再試行は使用しない
- Better Authが短時間の連続ログインへ429を返した場合だけ、
  応答の`x-retry-after`秒に従って1回だけ再試行する
- worker数は1とし、未登録申請とMailpit通知の共有状態を競合させない

## テストデータ

一般ユーザーと管理者の業務データは変更しない。E2E前処理は次だけを対象にする。

- `.env`の`DEV_PENDING_EMAIL`に一致する`access_requests`行
- 件名が`[Workflow] 未登録ユーザーからアクセスがありました`のMailpitメッセージ
- 前回のPlaywright成果物

事後検証では未登録ユーザーの申請が1行、`request_count`が2以上、対象通知が1件
であることを確認する。他ユーザーのDBデータや別件名のメールは削除しない。

## 成果物

失敗時だけtrace、スクリーンショット、videoを保持し、HTML reportも生成する。

```text
tests/e2e/test-results/results/
tests/e2e/playwright-report/report/
```

成果物はGit管理対象外である。traceはPlaywright Trace Viewerで開ける。

## network

Keycloak issuer、OAuth redirect、Cookieのhostを実利用と同じ`localhost`へ揃えるため、
E2E runnerだけhost networkを使用する。frontend、backend、PostgreSQLのCompose
networkや公開ポートは変更しない。Spring BootとPostgreSQLは引き続きホスト非公開で、
ブラウザの業務APIアクセスはNext.js BFFを経由する。
