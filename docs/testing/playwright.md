# Playwright E2E仕様

## 実行方法

```bash
make test SUITES=e2e
```

ホストへNode.jsやブラウザを導入せず、Node.js 24.18.0、
Playwright 1.62.0、Chromiumを含む専用imageでheadless実行する。
`docker-compose.test.yml`だけに`e2e`サービスを定義し、通常の`make up`では起動しない。
E2E serviceは`LANG`と`LC_ALL`を`C.UTF-8`に固定し、日本語のdownload推奨ファイル名を
Chromiumが実利用環境と同じUnicode文字列として扱える状態で検証する。

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
19. `/top`のdesktopでは常設サイドメニューを表示してハンバーガーを非表示にし、`/top`のmobileと
    `/top`以外では権限に応じた左Drawerナビゲーションを表示する。旧横型モバイルナビゲーションは
    表示せず、Escapeとリンク選択でDrawerが閉じ、active linkの`aria-current="page"`を維持する
20. 一般ユーザー、所属長、最上位所属長の経費申請経路を汎用workflow APIとBFF経由で完了する
21. 経費申請を理由付きで差し戻し、新しいInstanceで再申請・承認する
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
31. 一般ユーザーがContent Understandingの画面導線からAUTO_ENTRYをFake Providerで実行し、
    `SUCCEEDED`後のReview、`MISSING`表示、reload後の結果復元を確認する。さらにRecent analysesから
    同じJob、Review、BFF source previewを復元し、Browser `File` がない状態では再分析できないことと、
    mobile viewportでFile、Preview、Resultを切り替えてReviewを表示できることを確認する
32. AUTO_ENTRYではワークスペースナビゲーションからContent-oriented業務画面へ遷移し、左Drawerの
    active状態を確認したうえで、Fake Providerの分析、Review（`OK` / `REVIEW` / `MISSING`）、AI値を
    初期値とする編集、attention filter、請求額照合、正式handoff、Confirmationの
    reload復元、専用PUT保存、申請、現在Candidateの正式な原本証憑閲覧、差戻し後の編集・再申請・新しい
    workflow timelineを確認する。`TaxRatePercent=null`は補完せず`MISSING`として扱い、照合はAI値や経費金額を
    自動変更しないnon-blocking表示とする
33. 通常経費の新規申請でsubmitの503後にGETが`DRAFT`を返した場合、再試行時に新しいDRAFTをPOSTせず、
    最初に保存したApplication IDへのPUTとsubmitを使用することを確認する

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
- Better AuthのOAuthログイン開始では、ボタンの表示やクリック完了だけを遷移開始の根拠にせず、
  hydration完了後に操作可能となったボタンから`POST /api/auth/sign-in/oauth2`が発生したことを同期点として確認する
- worker数は1とし、未登録申請とMailpit通知の共有状態を競合させない

## テストデータ

一般ユーザーと管理者の業務データは変更しない。社長の表示名はテスト内で元へ戻し、
付与した監査ロールも同じテスト内で剥奪する。E2E前処理は次だけを対象にする。

- `.env`の`DEV_PENDING_EMAIL`に一致する`access_requests`行
- 件名が`[Workflow] 未登録ユーザーからアクセスがありました`のMailpitメッセージ
- 前回のPlaywright成果物
- 中断された前回テストが残した社長の`仮 社長 E2E`表示名と有効な`AUDITOR`割当
- 件名が`E2E`で始まる経費申請と、その明細・workflow Instance・Step・Candidate・Action・添付metadata

事後検証では未登録ユーザーの申請が1行、`request_count`が2以上、開発管理者宛の対象通知が
宛先単位で1件であることを確認する。他ユーザーのDBデータや別件名のメールは削除しない。

## staging Test Persona

staging manual seed後のmaster fixtureは、E2E・smoke testで利用するcanonical staging test
fixtureである。テストは無目的に単一のsmoke userを共有せず、目的に合うTest Personaを選ぶ。
Persona catalogの正本は
[`tests/fixtures/staging-test-personas.json`](../../tests/fixtures/staging-test-personas.json)であり、
password、token、Cookie、Keycloak credential、DB生成IDは含めない。

persona名は人名や組織名ではなく、業務上の意味を表す。通常申請は`STANDARD_APPLICANT`、
同一所属の所属長承認は`DEPARTMENT_MANAGER`、親組織の所属長承認は`DIVISION_HEAD`、経理承認は
`ACCOUNTING_APPROVER`、親組織がない最上位所属長の経路確認は`PRESIDENT`を基本にする。
`STANDARD_APPLICANT`をすべてのテストで使うことを標準とはせず、必要な組織階層、役職、
Permissionに合うpersonaを選択する。local E2Eも同じcatalogをread-only mountし、
`STAGING_TEST_PERSONAS_PATH`から実行時に解決する。これはlocal環境をstagingとして扱うものではなく、
development seedが共有するcanonical mappingをselectorとして再利用するものである。passwordは
catalogに入れず、local seed credentialを別途渡す。所属不備や雇用区分のnegative testは現在の
`DEV_PART_TIME_EMAIL`などのlocal boundary fixtureを維持し、組織所属を前提とするcatalogへ追加しない。

課金や外部Azure resourceを呼ぶstaging live smokeでは、分析要求を開始する前にpersonaが
必要な前提条件を満たすことをpreflightする。GENERALとAUTO_ENTRYはテスト意図として
`STANDARD_APPLICANT`を選択し、emailを
[`tests/fixtures/staging-test-personas.json`](../../tests/fixtures/staging-test-personas.json)から
実行時に解決する。specやGitHub Environmentへpersona emailを複製しない。

consumerの移行状態は次のとおりである。

| Consumer | Identity source | Persona | Migration status |
| --- | --- | --- | --- |
| `specs/azure-document-analysis-smoke.spec.ts` | canonical persona manifest | `STANDARD_APPLICANT` | T2完了 |
| `specs/azure-auto-entry-smoke.spec.ts` | canonical persona manifest | `STANDARD_APPLICANT` | T2完了 |
| `.github/workflows/document-analysis-staging-smoke.yml` | repository manifestとKey Vault password | `STANDARD_APPLICANT` | T2完了 |
| `specs/workflow.spec.ts` の経費・社長フロー | canonical persona manifest | `STANDARD_APPLICANT` / `DEPARTMENT_MANAGER` / `DIVISION_HEAD` / `ACCOUNTING_APPROVER` / `PRESIDENT` | T3完了 |
| `specs/auto-entry.spec.ts` の正式経費フロー | canonical persona manifest | `STANDARD_APPLICANT` / `DEPARTMENT_MANAGER` | T3完了 |
| `specs/expense-attachments.spec.ts` | canonical persona manifest | `STANDARD_APPLICANT` / `DEPARTMENT_MANAGER` / `DIVISION_HEAD` | T3完了 |
| generic local login specs | `DEV_USER_EMAIL` / `DEV_ADMIN_EMAIL` | local bootstrap fixture | 維持 |
| 未登録・雇用区分境界 | `DEV_PENDING_EMAIL` / `DEV_PART_TIME_EMAIL` | local negative fixture | 維持 |

## 成果物

失敗時だけtrace、スクリーンショット、videoを保持し、HTML reportも生成する。

```text
test-results/<run-id>/diagnostics/e2e/results/
test-results/<run-id>/diagnostics/e2e/html/
```

成果物はGit管理対象外である。traceはPlaywright Trace Viewerで開ける。
Playwrightが失敗した場合、実行ログにもこの2つの保存先を表示する。

## staging Azure Document Analysis live smoke

`specs/azure-document-analysis-smoke.spec.ts`と`specs/azure-auto-entry-smoke.spec.ts`は通常の`make test SUITES=e2e`では
`AZURE_DOCUMENT_ANALYSIS_LIVE_SMOKE=true`がないためskipする。通常suiteは引き続きFake Providerを使い、
Azure endpoint、Private Endpoint、課金、RBAC propagationへ依存しない。live specはstagingだけで、
`BASE_URL`、`KEYCLOAK_URL`、repository内manifestを指す`STAGING_TEST_PERSONAS_PATH`、process
environment内だけの`STAGING_SEED_USER_PASSWORD`を要求する。不足した状態でlive flagをtrueにすると
明示的に失敗する。manifestとpasswordはlive test bodyで遅延取得するため、通常E2Eのspec collectionと
skipはrepository rootのmanifestやstaging credentialを要求しない。

両live specはログイン後、最初の課金対象`POST /api/backend/document-analyses`より前に、Frontend BFF経由の
`GET /api/backend/me`でpersona emailの完全一致、manifest所定のRole・Permissionを確認し、
`GET /api/backend/organization-chart`で主所属、役職、事業部ancestorを確認する。AUTO_ENTRYはさらに
`DEPARTMENT_MANAGER`が申請者と同じunitの主所属であること、および`ACCOUNTING_APPROVER`の主所属と役職を
確認する。組織図の403、missing parent、循環、fixture不一致はAzure分析前にpreflight failureとして停止する。
AUTO_ENTRYでは自動分析を始めるfile選択より前にこのpreflightを完了する。

GENERAL live specは既存`fixtures/receipt.pdf`だけを使い、Document IntelligenceとContent Understandingを各1件直列実行する。
各Providerは最大10分の有限待機で`SUCCEEDED`を確認するため、test全体timeoutは22分、専用設定のtimeoutは23分である。
`FAILED`または`FAILED_RECOVERY_REQUIRED`を取得した場合は再送や10分待機をせず固定メッセージでfail-fastする。pollingで得た
実際のterminal Job responseを保持し、schema version 1、`prebuilt-layout`、GA API version、non-empty Markdown、
Raw JSONのfake marker不在を確認する。summaryの時刻には`new Date()`を使わず、このterminal responseの
`createdAt`と`completedAt`だけを使う。UIではMarkdownとRaw Result tabを開く。全Browser requestを監視し、
`*.cognitiveservices.azure.com`、`*.services.ai.azure.com`、`*.openai.azure.com`、`*.blob.core.windows.net`へ
直接requestが0件であることをassertする。分析通信は同一originの`/api/backend/document-analyses...`だけを通す。

AUTO_ENTRY live specはcanonicalな`backend/src/test/resources/document-analysis/auto-entry/v2.1/documents/invoice-02.jpg`を
1回だけ選択し、Content Understandingの`AUTO_ENTRY`分析、normalized v2.1 Review、human入力、formal handoff、
Formal Expense原本preview、reload、保存、`PENDING_APPROVAL`への申請を確認する。抽出値の完全一致は要求せず、
`TaxRatePercent`がnullならReviewで`MISSING`のまま保持する。business mutationを含むため、このspecはtest-level retryを
0にして新しいanalysisやExpenseApplicationを自動作成しない。

live smokeは通常E2E設定と別のPlaywright設定を使い、trace、screenshot、videoをすべてoff、`workers: 1`にする。
GENERAL Azure smokeは`retries: 2`の有限retryを許可する一方、AUTO_ENTRY business smokeは前記のとおりretryしない。
failure時にも`test-results`全体をartifactへuploadしない。Provider、stage、persona code、preflight check、status、
API version、実際のJob時刻、Formal HandoffのHTTP statusとtop-level error codeだけのallow-list済み診断JSONを
1日だけ非公開保持できる。Handoff diagnosticは成功statusのassertionより前に更新し、JSON objectの128文字以下の
安全なerror code以外のresponse body、message、detailsは保存しない。成功summaryもemailではなく
`personaCode=STANDARD_APPLICANT`を記録する。passwordはKey Vaultからlive smokeを実行する同じshellで取得して
maskし、Playwright processだけへ渡す。Cookie、Authorization header、入力fixture、Markdown本文、Raw JSON本文、
Azure response bodyはsummary、log、report、artifactへ書き出さない。Raw JSONの検査も本文をmatcher errorへ含めず、
固定エラー文で失敗する。

## network

Keycloak issuer、OAuth redirect、Cookieのhostを実利用と同じ`localhost`へ揃えるため、
E2E runnerだけhost networkを使用する。frontend、backend、PostgreSQLのCompose
networkや公開ポートは変更しない。Spring BootとPostgreSQLは引き続きホスト非公開で、
ブラウザの業務APIアクセスはNext.js BFFを経由する。Azuriteもホストへ公開せず、Playwrightは
FrontendとBackendを通してだけファイルを保存・取得する。
