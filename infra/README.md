# Azure Terraform

このディレクトリは、共有ACRとTerraform state、GitHub OIDC identityを作る
`bootstrap/`、および独立した`staging`、`production` stateを持つ環境定義からなる。
秘密値そのものはTerraform resourceにせず、Container AppsはUser Assigned Managed
IdentityでKey Vaultのversionless secret URIを参照する。

## 構成

```text
bootstrap/                 state Storage、共有ACR、環境RG、GitHub identity/OIDC
environments/staging/      stagingのVNet、Key Vault、PostgreSQL、Blob Storage、Container Apps
environments/production/   productionの完全に独立した同等構成
modules/                   再利用するAzure resource群
```

`bootstrap`もAzure Blob backendを使用する。state用Storage Accountとcontainerは
Portalで先に作り、`bootstrap.tfstate`へ既存Resource Group、Storage Account、
containerをimportする。既存resourceを同名で作成しようとしてはいけない。

```bash
cd infra/bootstrap
cp terraform.tfvars.example terraform.tfvars
gh api repos/<owner>/<repository> \
  --jq '{github_organization_id: .owner.id, github_repository_id: .id}'
terraform init \
  -backend-config="resource_group_name=<tfstate-rg>" \
  -backend-config="storage_account_name=<storage-account>" \
  -backend-config="container_name=tfstate" \
  -backend-config="key=bootstrap.tfstate"
terraform import azurerm_resource_group.tfstate \
  /subscriptions/<subscription-id>/resourceGroups/<tfstate-rg>
terraform apply
```

GitHub APIで取得したowner IDとrepository IDを`terraform.tfvars`へ設定する。
2026-07-15以降に作成されたGitHub repositoryのOIDC subjectには、rename後も変わらない
これらのIDがowner名とrepository名に付加される。

該当する場合は`azurerm_resource_group.acr`および
`azurerm_resource_group.environment[\"staging\"]`等も同様にimportする。
backendとStorage操作にはShared KeyではなくMicrosoft Entra IDを使用する。
bootstrap完了後、環境のbackend設定を初期化する。

環境のfoundation構築前に、staging/productionのGitHub Actions用Managed Identityへ、
それぞれの環境Resource Groupスコープで`Storage Blob Data Contributor`をAzure Portalから
事前付与する。環境rootのAzureRM providerは`storage_use_azuread = true`によりこの権限を使って
Storage data planeへ接続する。このRole AssignmentはbootstrapのTerraform管理対象に含めず、
Shared Keyも再有効化しない。Portalでの設定手順は
[`docs/operations/azure-portal-setup.md`](../docs/operations/azure-portal-setup.md)を参照する。

```bash
cd ../environments/staging
terraform init \
  -backend-config="resource_group_name=<tfstate-rg>" \
  -backend-config="storage_account_name=<storage-account>" \
  -backend-config="container_name=tfstate" \
  -backend-config="key=staging.tfstate"
```

初回環境構築は二段階で行う。

1. `provision_workloads=false`でapplyし、VNet、Log Analytics、Container Apps
   Environment、Managed Identity、Key Vault、経費証憑用Storage Accountと非公開containerを作成する。
2. 人間がKey Vaultへ秘密値を登録する。
3. SHA tagの通常3イメージをACRへpushし、stagingでは同じtagのseed専用イメージもpushする。
4. `provision_workloads=true`、`image_tag=<40文字SHA>`でapplyする。

GitHub Environmentの`PROVISION_WORKLOADS`は最初`false`にする。foundation applyと
Key Vault secret登録が完了した後だけ`true`へ変更する。ACR名はコードへ固定せず、
tfvars作成前に確認する。

```bash
az acr check-name --name <globally-unique-acr-name> \
  --query '{available:nameAvailable,reason:reason}' --output json
```

PostgreSQL Flexible Serverの初回作成ではproviderの制約上、管理者パスワードが
Terraformへ入力され、暗号化されたremote stateにも格納される。このためstate Storageは
アクセスキーを無効化し、OIDC identityだけへBlob Data Contributorを付与する。作成後の
password変更は`ignore_changes`対象であり、Key Vault側とサーバー側を同時にrotationする。

PostgreSQL moduleは期間重複排他制約に必要な`BTREE_GIST`を`azure.extensions`へ
allowlistする。backendのdatabase bootstrapはアプリ起動前に管理者としてこの拡張だけを
作成し、その後は`workflow`ロールへ`public` schemaの権限を付与してFlywayを実行する。

ユーザー基盤の初回切替ではGitHub Environmentの`CONTRACT_LEGACY_USER_COLUMNS`を
`false`にしてFlywayをV006へ固定する。新backend revisionの認証・認可と移行件数を確認し、
旧revisionが停止した後、同じ検証済みimageに対する別deployでこの値を`true`へ変更して
V007の旧列削除を行う。以後は`true`を維持する。`true`側ではFlyway targetを固定せず、
後続migrationもlatestまで適用する。詳細は
[`docs/backend/flyway.md`](../docs/backend/flyway.md)を参照する。

経費証憑用Storage Accountは環境ごとの`attachment_storage_account_name`で作成する。shared keyを
無効化し、Backend Blob専用User Assigned Managed Identityだけへcontainer scopeの
`Storage Blob Data Contributor`を付与する。Backendにはendpoint、container名、専用identityの
client IDだけを設定し、FrontendとKeycloakにはidentity・RBAC・接続情報を追加しない。詳細は
[`docs/infrastructure/expense-attachment-storage.md`](../docs/infrastructure/expense-attachment-storage.md)を
参照する。

Document Analysis Azure mode用resourceはfoundationとして作成する。環境ごとに
Document Intelligence `FormRecognizer`、Foundry `AIServices`、Document Analysis専用Storage Account、
`document-analysis-input`/`document-analysis-result` container、AI専用UAMI、Storage専用UAMI、Private
Endpoint subnet、Private Endpoint、Private DNS zone、VNet link、最小RBACをTerraformで管理する。
resource名は`document_intelligence_account_name`、`content_understanding_account_name`、
`document_analysis_storage_account_name`で渡し、コードへ固定しない。

Backend Container Appには既存runtime identity、既存Backend Blob identity、Document Analysis AI
identity、Document Analysis Storage identityをattachする。既存の`AZURE_CLIENT_ID`は経費証憑Blob用の
client IDのまま維持し、Document Analysis AIには`AZURE_DOCUMENT_ANALYSIS_CLIENT_ID`、Storageには
`DOCUMENT_ANALYSIS_STORAGE_MANAGED_IDENTITY_CLIENT_ID`を渡す。API Key、client secret、Storage key、
connection string、SASは作成しない。`document_analysis_enabled`、
`document_intelligence_enabled`、`content_understanding_enabled`の既定値はfalseであり、resource作成や
Frontend公開ではなくBackend runtimeとoperational kill switchだけを制御する。正式提供時の
staging/productionでは検証と承認後に有効化するが、安全側のTerraform既定値は変更しない。

GitHub Actionsの環境別planでは、GitHub Environment variable
`AZURE_ATTACHMENT_STORAGE_ACCOUNT_NAME`を`TF_VAR_attachment_storage_account_name`へ渡す。
`staging-plan`と`production-plan`には各環境のglobal uniqueなStorage Account名を個別に登録し、
未設定または空の場合はAzure loginとplanをskipする。workflow内に環境別の固定値を置かない。
Document Analysis用に`AZURE_DOCUMENT_INTELLIGENCE_ACCOUNT_NAME`、
`AZURE_CONTENT_UNDERSTANDING_ACCOUNT_NAME`、`AZURE_DOCUMENT_ANALYSIS_STORAGE_ACCOUNT_NAME`も同様に
環境ごとに登録し、`WORKFLOW_DOCUMENT_ANALYSIS_ENABLED`、`DOCUMENT_INTELLIGENCE_ENABLED`、
`CONTENT_UNDERSTANDING_ENABLED`は未設定時falseとして扱う。

`terraform.tfvars`、plan、stateはGitへ追加しない。リポジトリrootからの静的検証は資格情報なしで
実行でき、Terraform以外のインフラ不変条件も同時に確認する。

```bash
make verify-infra
```
