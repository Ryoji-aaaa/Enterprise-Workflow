# 開発用組織・ユーザーデータ

開発用データはFlywayへ入れず、`development` profileかつ`workflow.seed.enabled=true`の
場合だけInitializerで作成する。Docker ComposeのBackendだけがこのprofileを有効にし、
staging・productionでは実行しない。

`DevelopmentSeedData`にSDCJ配下37組織単位の固定コードと階層を定義する。統治組織3件を
除く34組織に責任者・一般ユーザーを1名ずつ作成し、会社直下に社長を1名作成するため、
組織図用DBユーザーは69名である。既存の開発管理者・一般ユーザーを含めた業務ユーザーには
必要なDBロールを付与する。さらに雇用区分の境界確認専用として、所属を持たないパート・嘱託
ユーザーを各1名作成する。この2名は`ORGANIZATION_CHART_VIEWER`を持つがBackendで閲覧を
拒否される。

各有人組織の責任者と経理課の一般ユーザーには`WORKFLOW_APPROVER`を付与する。経理課の有効な
所属ユーザーは全員が経費承認Candidateになるため、一般ユーザーにも承認Permissionを明示する。
個別申請の処理にはこのPermissionに加えて、申請時に保存したCandidateとの一致が必要である。

Initializerは組織コード、email、役職コードを自然キーとして存在しない行だけを追加する。
既存行の手動変更は上書きせず、所属・ロールは期間重複検査で再起動時の重複を防ぐ。

Keycloak側は最初に`DEV_ADMIN_EMAIL`と`DEV_USER_EMAIL`を作成または同期し、その後
`keycloak/development-users.tsv`を読み、組織図用69名と境界確認用2名をemailで検索して
作成または同期する。同じemailが複数経路にあっても既存ユーザーを同期するだけで重複作成しない。
これにより、DB seedの一般ユーザー（既定`example.user1@sdcj.co.jp`）とKeycloak seedのログイン
ユーザーが一致する。Keycloak Roleは業務認可に使わない。

## stagingへの一時投入

stagingには通常Backendとは別のseed専用imageを使い、手動トリガーだけを持つAzure
Container Apps Jobを置く。seed imageはstaging deployだけがbuild・pushし、productionが
promoteする通常3 imageには含めない。
通常Backendは`WORKFLOW_SEED_ENABLED=false`のままであり、deployや再起動では開発データを
投入しない。Jobは次の3種類で、必要な対象だけを実行できる。

| Job名 | 対象 |
| --- | --- |
| `job-ewf-stg-seed-db` | 業務DBのみ |
| `job-ewf-stg-seed-kc` | Keycloak userのみ |
| `job-ewf-stg-seed-all` | DB、Keycloakの順に両方 |

Job定義は`WORKFLOW_MANUAL_SEED_ENABLED=true`、
`WORKFLOW_DEPLOYMENT_ENVIRONMENT=staging`、対象別の
`WORKFLOW_MANUAL_SEED_TARGET`を明示する。入口スクリプトと各seed処理が値を再検証し、
値の欠落、不正値、`production`を終了コード非0で拒否する。Terraformはproductionに
これらのJobを作成しない。

各処理はemail、組織コード、役職コードと期間重複を自然キーとして冪等に動作する。
Keycloakの既存ユーザーは毎回passwordを指定値へ同期するため、既存ユーザーは`existing`と
`updated`の両方へ計上される。終了時には次の形式で集計をログ出力し、成功時は終了コード0、
失敗時は非0で終了する。

`all` JobのDB seedとKeycloak seedは単一transactionではない。DB seedが成功した後に
Keycloak seedが失敗した場合、Job自体は非0終了するが、DBへの投入結果はcommit済みの
部分成功として残る。失敗原因を解消して同じJobを再実行し、冪等なDB seedを再確認してから
Keycloak seedを完了させる。

```text
manual_seed_result target=db created=... existing=... updated=... failed=...
manual_seed_result target=keycloak created=... existing=... updated=... failed=...
```

実行前にstaging Key Vaultへ`development-seed-password`を登録する。実行は対象Jobを明示して
行い、返されたexecution名とログの集計を保存する。

DB seedはFlyway migrationを実行しない。seed imageは起動時に
`--spring.flyway.enabled=false`を指定するため、通常Backend revisionでV017までの適用が
完了していることが必須である。`employment_type does not exist`が発生した状態でseedを
再試行せず、先に通常Backendのmigration設定と履歴を直す。

```bash
az containerapp job start \
  --resource-group rg-enterprise-workflow-staging \
  --name job-ewf-stg-seed-db

az containerapp job start \
  --resource-group rg-enterprise-workflow-staging \
  --name job-ewf-stg-seed-kc
```

`all` Jobも利用できるが、初回確認と障害復旧では部分成功の境界を明確にするため、DB、
Keycloakを個別に実行する。stagingで確認済みの運用順は次のとおり。

1. staging Key Vaultに`development-seed-password`の有効なversionがあり、JobのManaged
   Identityに参照権限があることを確認する。secret値は画面共有やログへ表示しない。
2. `Deploy staging`を実行し、対象SHAのBackend、Frontend、Keycloak、seed imageと3つのJobを
   Terraformで反映する。
3. 通常BackendのConsole logと`flyway_schema_history`でV017の成功を確認する。
4. `job-ewf-stg-seed-db`を開始し、`manual_seed_result target=db ... failed=0`を確認する。
5. `job-ewf-stg-seed-kc`を開始し、
   `manual_seed_result target=keycloak ... failed=0`を確認する。
6. `president@sdcj.co.jp`でログインし、組織図とユーザー管理を表示できることを確認する。
7. 一般ユーザーの編集不可と、パート・嘱託の組織図閲覧不可を確認する。

Portalでは対象Jobの`Execution history`からexecutionを選び、`Console`で
`manual_seed_result`とSpring例外を確認する。`System`はimage pull、replica作成、Managed
Identity、secret参照など基盤側の調査に使う。アプリケーション例外の正本はConsole logであり、
System logだけを見て原因を判断しない。長期検索はContainer Apps Environmentに接続された
Log Analytics workspaceで対象Job名、execution名、時刻を絞り込む。

同じJobは冪等に再実行できる。実行履歴、execution名、対象image SHA、2種類の
`manual_seed_result`を運用記録へ残す。productionにはJobも
`development-seed-password`も作成せず、staging用imageをproductionで実行しない。
