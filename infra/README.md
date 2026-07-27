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

`bootstrap`は最初だけlocal stateでapplyし、そのstateファイルを厳重に保管する。
既にPortalで作成済みのResource Groupを使う場合、apply前に必ずimportする。既存resourceを
同名で作成しようとしてはいけない。

```bash
cd infra/bootstrap
cp terraform.tfvars.example terraform.tfvars
terraform init
terraform import azurerm_resource_group.tfstate \
  /subscriptions/<subscription-id>/resourceGroups/<tfstate-rg>
terraform apply
```

該当する場合は`azurerm_resource_group.acr`および
`azurerm_resource_group.environment[\"staging\"]`等も同様にimportする。
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

PostgreSQL Flexible Serverの初回作成ではproviderの制約上、管理者パスワードが
Terraformへ入力され、暗号化されたremote stateにも格納される。このためstate Storageは
アクセスキーを無効化し、OIDC identityだけへBlob Data Contributorを付与する。作成後の
password変更は`ignore_changes`対象であり、Key Vault側とサーバー側を同時にrotationする。

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
