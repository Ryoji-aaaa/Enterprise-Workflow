# Azure Portal初回セットアップ

この手順では秘密値を画面共有、Issue、チャットへ貼らない。既存Resource Groupは削除・
再作成せず、後でTerraform stateへimportする。

## Phase A: 今すぐ行うPortal作業

### 1. Subscription情報を記録

`Azure Portal > Subscriptions > 対象subscription > Overview`を開き、次を手元の安全な
メモへ記録する。

```text
Subscription ID
Subscription name
Tenant ID
Location（全resourceで原則統一。例: Japan East）
```

### 2. Resource Providerを登録

同じsubscriptionの`Settings > Resource providers`で次を検索し、Statusが
`Registered`でないものだけ`Register`する。完了まで数分待ち、画面を再読み込みする。

```text
Microsoft.App
Microsoft.ContainerRegistry
Microsoft.DBforPostgreSQL
Microsoft.KeyVault
Microsoft.ManagedIdentity
Microsoft.OperationalInsights
Microsoft.Storage
Microsoft.Insights
Microsoft.Network
```

### 3. Resource Groupの対応を確定

`Resource groups`で既存Groupを確認し、次の4用途へ割り当てる。stagingとproductionを
同じGroupにしない。足りない場合はこの時点では勝手に用途を混在させず、不足Groupを作る。

```text
Terraform state
共有ACR
staging
production
```

命名例は`rg-enterprise-workflow-tfstate`、`-shared`、`-staging`、`-production`。
既存Groupはbootstrap apply前に`terraform import`する。

### 4. state用Storageを作る

`Storage accounts > Create`でstate用Resource Groupを選び、Azure全体で一意の小文字名を
指定する。

```text
Performance             Standard
Redundancy              LRS
Require secure transfer Enabled
Allow Blob anonymous    Disabled
Minimum TLS             1.2以上
Public network access   Enabled（GitHub-hosted runner利用のため）
Access keys             Disabled（作成後Configurationで確認）
```

作成後、`Data management > Data protection`でBlob soft delete、container soft delete、
versioningを有効にし、保持14日とする。`Data storage > Containers > + Container`で
`tfstate`をPrivateとして作成する。staging/productionはcontainerを分けず、
`staging.tfstate`と`production.tfstate`の別keyを使う。

このStorageをPortalで先に作った場合、bootstrapでは以下3resourceをimportする。

```text
azurerm_resource_group.tfstate
azurerm_storage_account.tfstate
azurerm_storage_container.tfstate
```

## Phase B: bootstrap後に行う作業

bootstrap applyで作成されたstaging/production用User Assigned Managed Identityを開き、
`Settings > Federated credentials`で次を確認する。

```text
Issuer    https://token.actions.githubusercontent.com
Audience  api://AzureADTokenExchange
Subject   repo:Ryoji-aaaa/Enterprise-Workflow:environment:<environment>
```

`Access control (IAM) > Role assignments`では、各identityが自環境Resource Groupだけに
`Contributor`と`User Access Administrator`、state Storageに
`Storage Blob Data Contributor`、共有ACRに`AcrPush`と
`User Access Administrator`を持つことを確認する。subscription全体へ付けない。

GitHubの`Settings > Environments`で`staging`と`production`を作り、
[`github-actions.md`](../infrastructure/github-actions.md)のvariablesを環境別に登録する。
productionでは`Required reviewers`と`Prevent self-review`を有効化する。
Federated Credentialとすべてのvariableを確認するまでは
`AZURE_OIDC_CONFIGURED=false`、foundation構築中は`PROVISION_WORKLOADS=false`とする。

## Phase C: foundation apply後の秘密値登録

環境を`provision_workloads=false`でapplyするとKey Vaultが作成される。各Vaultの
`Objects > Secrets > Generate/Import`で、環境ごとに異なる十分長い値を登録する。

```text
postgres-admin-password
workflow-db-password
keycloak-db-password
keycloak-bootstrap-admin-password
keycloak-client-secret
better-auth-secret
```

値をGitHub Secretsやtfvarsへ複製しない。登録後、GitHub Environmentの
`PROVISION_WORKLOADS=true`としてworkload deployへ進む。
