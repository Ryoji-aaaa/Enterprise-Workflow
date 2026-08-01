# Azure Terraform

このディレクトリは、共有ACRとTerraform state、GitHub OIDC identityを作る
`bootstrap/`、および独立した`staging`、`production` stateを持つ環境定義からなる。
秘密値そのものはTerraform resourceにせず、Container AppsはUser Assigned Managed
IdentityでKey Vaultのversionless secret URIを参照する。

## 構成

```text
bootstrap/                 state Storage、共有ACR、環境RG、GitHub identity/OIDC
environments/staging/      stagingのVNet、Key Vault、PostgreSQL、Container Apps
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
   Environment、Managed Identity、Key Vaultを作成する。
2. 人間がKey Vaultへ秘密値を登録する。
3. SHA tagの3イメージをACRへpushする。
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

`terraform.tfvars`、plan、stateはGitへ追加しない。検証は資格情報なしでも実行できる。

```bash
terraform fmt -check -recursive infra
terraform -chdir=infra/bootstrap init -backend=false
terraform -chdir=infra/bootstrap validate
terraform -chdir=infra/environments/staging init -backend=false
terraform -chdir=infra/environments/staging validate
terraform -chdir=infra/environments/production init -backend=false
terraform -chdir=infra/environments/production validate
```
