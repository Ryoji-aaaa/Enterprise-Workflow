# GitHub ActionsとAzure OIDC

ワークフローは次の責務に分離する。

- `ci.yml`: backend、frontend、Playwright、境界検証、3イメージbuild
- `dependency-review.yml`: PR dependency review
- `terraform-plan.yml`: fmt、validate、`staging-plan`/`production-plan`を使った
  staging/production plan
- `deploy-staging.yml`: main CI成功後のSHA image build/pushと自動apply
- `deploy-production.yml`: 指定済みSHA imageを再buildせず手動昇格
- `document-analysis-staging-smoke.yml`: 明示起動したstagingの2 Provider live smoke

PRでは`ci.yml`の`make test`、`make verify`とimage build、`dependency-review.yml`の
dependency reviewと`make audit`を実行する。Terraform、インフラ検証スクリプト、Makefile、
またはplan workflowの変更時は`terraform-plan.yml`が`make verify-infra`でfmt、独自の
インフラ不変条件、3つのrootのvalidateを確認してからstaging/production planを実行する。
mainへのpushでCIが成功すると
`workflow_run`から`deploy-staging.yml`を開始する。staging deployは同じ40文字commit SHAで
Frontend、Backend、Keycloak、staging専用seedの4 imageをbuildしてACRへpushし、Terraform
apply、Keycloak realm/client設定、public smoke testまでを行う。

各deploy jobは`contents: read`と`id-token: write`だけを宣言し、
`azure/login`でGitHub OIDC tokenをAzure短期tokenへ交換する。長期client secretは保存しない。
外部fork PRではAzure plan jobを実行しない。`pull_request_target`は使用しない。
production deploy jobはGitHub Environmentの承認規則を通す。

GitHub Environmentは実デプロイ用の`staging`、`production`と、PR plan専用の
`staging-plan`、`production-plan`を分離する。plan workflowのmatrixではTerraform rootを
選ぶ`target_environment`と、variablesおよびOIDC subjectを選ぶ`github_environment`を
別フィールドとして扱う。

| target_environment | github_environment | 用途 |
| --- | --- | --- |
| `staging` | `staging-plan` | PRでのstaging plan |
| `production` | `production-plan` | PRでのproduction plan |

4 Environmentそれぞれに次の30 variablesを登録する。

```text
AZURE_CLIENT_ID
AZURE_OIDC_CONFIGURED
AZURE_TENANT_ID
AZURE_SUBSCRIPTION_ID
AZURE_LOCATION
AZURE_RESOURCE_GROUP
AZURE_CONTAINER_REGISTRY_NAME
AZURE_CONTAINER_REGISTRY_RESOURCE_GROUP
AZURE_CONTAINER_APPS_ENVIRONMENT_NAME
AZURE_FRONTEND_CONTAINER_APP_NAME
AZURE_BACKEND_CONTAINER_APP_NAME
AZURE_KEYCLOAK_CONTAINER_APP_NAME
AZURE_GITHUB_IDENTITY_PRINCIPAL_ID
AZURE_KEY_VAULT_NAME
AZURE_POSTGRES_SERVER_NAME
AZURE_ATTACHMENT_STORAGE_ACCOUNT_NAME
AZURE_DOCUMENT_INTELLIGENCE_ACCOUNT_NAME
AZURE_CONTENT_UNDERSTANDING_ACCOUNT_NAME
AZURE_DOCUMENT_ANALYSIS_STORAGE_ACCOUNT_NAME
ALLOWED_EMAIL_DOMAIN
MAIL_FROM
PROVISION_WORKLOADS
CONTRACT_LEGACY_USER_COLUMNS
WORKFLOW_DOCUMENT_ANALYSIS_ENABLED
DOCUMENT_INTELLIGENCE_ENABLED
CONTENT_UNDERSTANDING_ENABLED
TF_STATE_RESOURCE_GROUP
TF_STATE_STORAGE_ACCOUNT
TF_STATE_CONTAINER
TF_STATE_KEY
```

`CONTRACT_LEGACY_USER_COLUMNS`はGitHub repositoryの
`Settings > Environments > <environment> > Environment variables`で設定する。
`false`ではTerraformが通常Backendへ`SPRING_FLYWAY_TARGET=006`を渡し、`true`ではtargetを
渡さずV007以降のlatestまで進める。一度V007を適用したstagingまたはproductionでは
`true`を維持する。stagingはV008適用済みのため`true`が現在値である。

Azure識別子はsecretではなくEnvironment variableとする。DB、Keycloak、Better Authの
秘密値をGitHub Secretsへ複製しない。`production-plan`は常に
`PROVISION_WORKLOADS=false`とし、workflowでも異なる値を拒否する。`staging-plan`は
必要な差分に応じて値を設定する。

plan用Environmentは同一repository内のPR branchからjobを開始できるようbranch
restrictionとrequired reviewerを設定しない。workflow側の条件で外部fork PRを除外する。
実デプロイ用`staging`、`production`のbranch policyや承認規則はplan用Environmentから
独立して維持する。`production`にはrequired reviewerとprevent self-reviewを設定し、
利用プランで使えない場合はworkflow実行権限を限定する。

各User Assigned Managed Identityには、実デプロイ用とplan用のFederated Credentialを
Terraformで作成する。subjectはすべてimmutable IDを含む次の形式を使う。

```text
repo:<owner>@<owner-id>/<repository>@<repository-id>:environment:<environment>
```

staging identityには`staging`と`staging-plan`、production identityには`production`と
`production-plan`のcredentialを設定する。既存の実デプロイ用credentialは変更・削除しない。

staging/productionの初回foundation構築より前に、各GitHub Actions用Managed Identityへ
対応する環境Resource Groupスコープの`Storage Blob Data Contributor`をAzure Portalから
事前付与する。環境rootのAzureRM providerは`storage_use_azuread = true`でこの権限を使用し、
Shared Keyが無効なStorage Accountのdata planeへ接続する。このRole Assignmentはbootstrapに
追加せず、Portalで管理する環境構築の前提条件とする。state Storageに対する同名roleとは
scopeが異なるため、両方を維持する。Shared Keyは再有効化しない。具体的な確認手順は
[`azure-portal-setup.md`](../operations/azure-portal-setup.md)を参照する。

`AZURE_OIDC_CONFIGURED`はFederated Credentialと全必須variableの登録完了後だけ`true`に
する。未設定時もPRのfmt/validateは実行し、Azure login、plan、deployは明示的にskipする。
`AZURE_ATTACHMENT_STORAGE_ACCOUNT_NAME`は環境ごとにglobal uniqueな値を登録し、
`staging-plan`と`production-plan`では対応する環境の値を個別に設定する。workflow内へ固定値や
別環境のfallbackを持たせない。

Document Analysis Azure mode用に`AZURE_DOCUMENT_INTELLIGENCE_ACCOUNT_NAME`、
`AZURE_CONTENT_UNDERSTANDING_ACCOUNT_NAME`、
`AZURE_DOCUMENT_ANALYSIS_STORAGE_ACCOUNT_NAME`も4 Environmentすべてへ登録する。これらはresource名であり
secretではない。`WORKFLOW_DOCUMENT_ANALYSIS_ENABLED`、`DOCUMENT_INTELLIGENCE_ENABLED`、
`CONTENT_UNDERSTANDING_ENABLED`はFrontend公開用Feature Flagではなくruntime controlであり、未設定の場合は
workflowで`false`として扱う。stagingはまず3つともfalseのままfoundationをapplyし、Private Endpoint、
Private DNS、RBACを確認した後だけtrueへ変更して同じ検証済みimage SHAを再deployする。正式提供する
productionでも同じ検証と承認後にtrueとするが、安全側の未設定時falseは変更しない。
通常CIとPRのE2EはFake Providerだけを使い、Azure AI、Foundry、Storage private endpointへlive requestを
送らない。Azure live validationはstaging resource作成後の運用確認として分離する。

`document-analysis-staging-smoke.yml`は`workflow_dispatch`だけで起動し、`staging` Environmentと
`contents: read`、`id-token: write`だけを使用する。起動refはrepository default branchである`main`に限定する。
Azure loginより前に、`fetch-depth: 0`、`persist-credentials: false`で信頼済みの`main`をcheckoutし、必須の
`image_sha`が40文字の小文字hexの実commitであり`origin/main`のancestorであることを検証する。検証後だけ
対象SHAをcheckoutするため、branch名、tag、未マージPR commit、同一repositoryの未レビューbranch commitを
実行対象にできない。`latest`は受け付けない。

Node依存関係とChromiumはAzure loginより前に導入する。Terraform stateとAzure CLIのread-only検査では、
activeなFrontend/Backend revisionが同じimage tagであること、3つのruntime control、BackendのAzure
execution mode、2つのDocument Analysis専用client ID、endpoint、containerが一致することを確認してから
Azureへ分析要求を送る。container検査は`az storage container-rm show`によるMicrosoft.Storage control plane
readだけを使い、Private Endpoint限定でpublic networkを無効にしたStorageに対してもdata plane、Shared Key、
SAS、connection stringへfallbackしない。Azure CLI readの失敗は検査成功として扱わない。

staging Key Vaultの`development-seed-password`は、setup-node、`npm ci`、Chromium導入後、live smokeを起動する
同じshell stepで取得する。`add-mask`後に`DOCUMENT_ANALYSIS_SMOKE_USER_PASSWORD`としてPlaywright processだけへ
渡し、`GITHUB_ENV`、step output、summary、artifactへ保存しない。`DOCUMENT_ANALYSIS_SMOKE_USER_EMAIL`はstaging
Environment variableとして登録し、seed userが未投入の場合は[開発・staging用seedデータ](../backend/development-seed-data.md)
の手順を実施してから再実行する。workflow自体はseed Jobを起動しない。通常Fake CI、deploy後の匿名public smoke、
課金対象のstaging live smokeはそれぞれ別の責務であり、live smokeを`deploy-staging.yml`の自動stepへ追加しない。
live smokeは`/api/backend/me`でsmoke userが`APPLICATION_USER`を持ち、廃止済み
`DOCUMENT_ANALYSIS_USER`を持たず、Document Analysisの3 Permissionを持つことも検証する。

live smokeは専用Playwright設定でtrace、screenshot、videoをすべて無効化し、`workers: 1`にする。GENERALの
Document Intelligence / Content Understanding smokeは`retries: 2`の有限retryを許可する。AUTO_ENTRY
Content Understanding business smokeはanalysis、handoff、save、submitを含むためtest-level retryを0にし、
失敗後に別の業務データを自動作成しない。成功summaryは同一image SHA、GENERAL Providerのstatus/API version/
実際の`createdAt`/`completedAt`、AUTO_ENTRYのprovider/profile/analyzer/API version/schema version/handoff status/
Expense final statusだけに限定する。
失敗時にartifactへ残せるのはProvider、stage、status、API version、時刻だけのallow-list済み専用診断ファイルであり、
`test-results`全体はuploadしない。入力文書、Markdown、Raw JSON、Cookie、Authorization header、password、
operation token、Azure response bodyはsummary、log、report、artifactに記録しない。

初回は`PROVISION_WORKLOADS=false`でfoundationだけをapplyする。Key Vaultへのsecret登録後、
stagingではこれを`true`へ変更する。production workflowは`foundation`と`workloads`の
phaseを手動で選択する。

`terraform-plan.yml`はfmt、validate、`terraform plan`だけを実行し、`terraform apply`を
追加しない。applyは実デプロイ用workflowからのみ実行する。

GitHub dependency reviewはrepositoryのDependency graphが利用できる場合だけ有効である。
未対応の場合はworkflowを失敗させず、`make audit`によるfrontend/E2Eの`npm audit`とDependabotによる
GitHub Actions、npm、Maven、Docker dependency更新を代替とする。

Docker Hubからbase imageを取得するbuildで`i/o timeout`など一時的な通信エラーだけが
発生した場合は、コードやTerraformを変更せずworkflowを再実行できる。Environment variableを
変更した後は、変更前のrunの再実行ではなく新しい`workflow_dispatch`を開始し、現在の変数で
新しいdeploy runを作る。失敗原因がテスト、Terraform plan、migration、Keycloak設定、smoke
testの場合は一時障害として扱わず、該当stepのログを確認してから再実行する。
