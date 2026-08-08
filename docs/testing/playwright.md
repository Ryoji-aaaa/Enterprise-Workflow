# Playwright E2E仕様

## 実行方法

```bash
make test SUITES=e2e
```

ホストへNode.jsやブラウザを導入せず、Node.js 24.18.0、
Playwright 1.62.0、Chromiumを含む専用imageでheadless実行する。
`docker-compose.test.yml`だけに`e2e`サービスを定義し、通常の`make up`では起動しない。

実行処理は次の順序で行う。

1. ローカル設定とAzuriteを含む全サービスのhealthを確認する
2. 内部Admin REST APIでKeycloak設定を冪等更新する
3. E2E imageをbuildする
4. 未登録テストユーザーの申請、`E2E`接頭辞の経費申請、対象Mailpit通知だけを初期化する
5. Playwrightを1 workerで実行する
6. DB、Mailpit、JWT拒否、Composeと実コンテナのnetwork境界を事後検証する

ログは環境準備、Keycloak設定、imageとデータ準備、Playwright実行、事後検証のsectionに分ける。
対話TTYでは実行中の経過秒数を同じ行へ1秒ごとに表示し、非TTYと`VERBOSE=1`では5秒ごとに
進捗行を追加する。完了行には所要時間を表示せず、phase JSONへミリ秒で保存する。

## ブラウザシナリオ

各テストは独立したBrowserContextを使用し、Cookieとセッションを共有しない。
次の一覧は固定のテスト件数ではなく、現在維持する受け入れ対象を示す。テストの統合や分割で
件数が変わっても、対象となる境界と利用者フローを維持する。

1. 未認証で`/top`へアクセスすると`/login`へ遷移する
2. 一般ユーザーがKeycloakでログインし、氏名、email、所属、一般ロールを表示する
3. 管理者がログインし、DB由来の管理者ロールを表示する
4. ログアウトし、Keycloak確認後に`/top`へ戻れないことを確認する
5. BFFの401で`/top`から期限切れログインへ一方向に遷移し、再ログインできることを確認する
6. 同じ401処理を`/expenses`でも適用することを確認する
7. 無効なBetter Auth sessionへのBFF 401で全認証Cookieを削除することを確認する
8. 業務DB未登録ユーザーを申請画面へ遷移し、Mailpitの管理者通知を確認する
9. 同じ未登録ユーザーの再アクセスで行を増やさず、回数だけを更新し、通知を増やさない
10. ホストの`127.0.0.1:8080`からSpring Bootへ直接接続できないことを確認する
11. 未認証のBFF `/api/backend/me`が401を返すことを確認する
12. 社長が組織図とDB由来の組織階層を表示する
13. 社長がユーザー編集画面で表示名を変更し、ロールを付与・剥奪して監査ログを確認する
14. 一般正社員が組織図を表示でき、ユーザー管理リンクを持たないことを確認する
15. パートが組織図リンクを持たず、直接アクセスでも403になることを確認する
16. BFF allowlist外のBackend APIが404で拒否されることを確認する
17. 社長の組織図で社長・責任者・一般ユーザーに編集操作が表示され、対象編集画面へ遷移する
18. 一般ユーザーには編集操作を表示せず、編集URLへの直接アクセスもBackendが403で拒否する
19. `md`未満で権限に応じた組織図・ユーザー管理のモバイルナビゲーションを表示する
20. 一般ユーザー、課長、事業部長の経費申請経路をBFF経由で完了する
21. 経費申請を理由付きで差し戻し、新しいRunで再申請・承認する
22. 候補者外ユーザーの経費承認を403で拒否し、Mailpit通知を確認する
23. 下書きへPDF・PNGを添付し、一覧、preview、download、削除を確認する
24. 申請後は添付変更UIを非表示にし、現在Candidateだけが閲覧できることを確認する
25. 差戻し後に証憑を削除・再登録し、再申請後は再び変更できないことを確認する
26. ローカル管理者が送付済メール一覧と本文詳細を表示できることを確認する
27. 一般ユーザーにメール履歴メニューを表示せず、直接APIとURLを403で拒否する
28. 一般ユーザーがDocument IntelligenceとContent UnderstandingをFake Providerで実行し、PDF、JPEG、
    PNGの受付、local/server preview、URL query復元、Recent analyses再選択、Markdown/Paragraphs/Tables、
    Raw Result lazy loadingと同一analysis内cacheを確認する
29. Document Analysisの10MiB超とunsupported fileはFrontendで拒否し、Backend POSTを送らないことを確認する
30. Document Analysis操作中にBrowserからAzure AI、Foundry、Azure Blob Storageへ直接requestしないことを
    確認する

雇用区分のBackend境界は正社員・準社員を許可し、パート・嘱託を権限保持時も拒否するAPI
統合テストで確認する。Frontend単体テストは`PART_TIME`、`CONTRACT_EMPLOYEE`、`SYSTEM`を
fail closedにする。Playwrightは代表としてパートのメニュー非表示と直接アクセス403を確認する。

Playwright後のコンテナ内検証では、Spring Bootの`/api/me`へJWTなしで接続した場合も
401になることを確認する。

JUnit XMLを件数と成否の正本とし、Playwright JSONは失敗したtest caseのspec file、line、retry、
trace、screenshot、videoを関連付けるために使用する。JSONはPlaywright phaseの必須成果物で、
欠落または不正な場合はSuite Errorとなる。失敗時は最終summaryと直後の案内から
`diagnostics/e2e/html/`と`diagnostics/e2e/results/`を参照する。

## 安定性と有限待機

- selectorはrole、label、表示テキストを優先する
- 画面遷移とMailpit通知はPlaywrightのexpectで有限時間待機する
- 固定sleepと無限再試行は使用しない
- Better Authが短時間の連続ログインへ429を返した場合だけ、
  応答の`x-retry-after`秒に従って1回だけ再試行する
- worker数は1とし、未登録申請とMailpit通知の共有状態を競合させない

## テストデータ

一般ユーザーと管理者の業務データは変更しない。社長の表示名はテスト内で元へ戻し、
付与した監査ロールも同じテスト内で剥奪する。E2E前処理は次だけを対象にする。

- `.env`の`DEV_PENDING_EMAIL`に一致する`access_requests`行
- 件名が`[Workflow] 未登録ユーザーからアクセスがありました`のMailpitメッセージ
- 前回のPlaywright成果物
- 中断された前回テストが残した社長の`仮 社長 E2E`表示名と有効な`AUDITOR`割当
- 件名が`E2E`で始まる経費申請と、その明細・承認Run・Step・Candidate・添付metadata

事後検証では未登録ユーザーの申請が1行、`request_count`が2以上、開発管理者宛の対象通知が
宛先単位で1件であることを確認する。他ユーザーのDBデータや別件名のメールは削除しない。

## 成果物

失敗時だけtrace、スクリーンショット、videoを保持し、HTML reportも生成する。

```text
test-results/<run-id>/diagnostics/e2e/results/
test-results/<run-id>/diagnostics/e2e/html/
```

成果物はGit管理対象外である。traceはPlaywright Trace Viewerで開ける。
Playwrightが失敗した場合、実行ログにもこの2つの保存先を表示する。

## network

Keycloak issuer、OAuth redirect、Cookieのhostを実利用と同じ`localhost`へ揃えるため、
E2E runnerだけhost networkを使用する。frontend、backend、PostgreSQLのCompose
networkや公開ポートは変更しない。Spring BootとPostgreSQLは引き続きホスト非公開で、
ブラウザの業務APIアクセスはNext.js BFFを経由する。Azuriteもホストへ公開せず、Playwrightは
FrontendとBackendを通してだけファイルを保存・取得する。
