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

Initializerは組織コード、email、役職コードを自然キーとして存在しない行だけを追加する。
既存行の手動変更は上書きせず、所属・ロールは期間重複検査で再起動時の重複を防ぐ。

Keycloak側は`keycloak/development-users.tsv`を読み、組織図用69名と境界確認用2名の
計71アカウントをemailで検索して作成または同期する。Keycloak Roleは業務認可に使わない。

## stagingへの一時投入

stagingには通常Backendとは別のseed専用imageを使い、手動トリガーだけを持つAzure
Container Apps Jobを置く。seed imageはstaging deployだけがbuild・pushし、productionが
promoteする通常3 imageには含めない。
通常Backendは`WORKFLOW_SEED_ENABLED=false`のままであり、deployや再起動では開発データを
投入しない。Jobは次の3種類で、必要な対象だけを実行できる。

| Job名 | 対象 |
| --- | --- |
| `job-enterprise-workflow-staging-seed-db` | 業務DBのみ |
| `job-enterprise-workflow-staging-seed-keycloak` | Keycloak userのみ |
| `job-enterprise-workflow-staging-seed-all` | DB、Keycloakの順に両方 |

Job定義は`WORKFLOW_MANUAL_SEED_ENABLED=true`、
`WORKFLOW_DEPLOYMENT_ENVIRONMENT=staging`、対象別の
`WORKFLOW_MANUAL_SEED_TARGET`を明示する。入口スクリプトと各seed処理が値を再検証し、
値の欠落、不正値、`production`を終了コード非0で拒否する。Terraformはproductionに
これらのJobを作成しない。

各処理はemail、組織コード、役職コードと期間重複を自然キーとして冪等に動作する。
Keycloakの既存ユーザーは毎回passwordを指定値へ同期するため、既存ユーザーは`existing`と
`updated`の両方へ計上される。終了時には次の形式で集計をログ出力し、成功時は終了コード0、
失敗時は非0で終了する。

```text
manual_seed_result target=db created=... existing=... updated=... failed=...
manual_seed_result target=keycloak created=... existing=... updated=... failed=...
```

実行前にstaging Key Vaultへ`development-seed-password`を登録する。実行は対象Jobを明示して
行い、返されたexecution名とログの集計を保存する。

```bash
az containerapp job start \
  --resource-group rg-enterprise-workflow-staging \
  --name job-enterprise-workflow-staging-seed-db

az containerapp job start \
  --resource-group rg-enterprise-workflow-staging \
  --name job-enterprise-workflow-staging-seed-keycloak
```
