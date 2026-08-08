# Terraform運用

実装の正本は[`infra/README.md`](../../infra/README.md)とする。AzureRM providerはlock
fileで固定し、PRではfmt、validate、環境別planだけを行う。PRからapplyしない。

stateはAzure Blobへ保存し、`staging.tfstate`と`production.tfstate`を分ける。
Storage Account access keyは無効化し、OIDCでログインしたIdentityへ
`Storage Blob Data Contributor`を付与する。

環境作成は`provision_workloads=false`のfoundation applyと、秘密値登録後の
`provision_workloads=true`のworkload applyに分ける。Key Vault Secret resourceは
Terraformで作らないため、秘密値はstateへ複製されない。ただしFlexible Server初回作成の
管理者passwordだけはAzureRM APIの必須入力でありstateへ入る。stateへのRBAC、versioning、
soft deleteを必須とする。

既存Portal resourceを管理へ取り込む場合は、同名resourceのapplyより前に
`terraform import`する。importせずTerraform管理外のAzure app resourceを増やさない。

`environment-stack`はstaging workload構築時だけ、DB、Keycloak、両方を対象にした3個の
手動Container Apps Jobを作る。Jobにschedule/event triggerはなく、通常deployからも開始しない。
productionでは`for_each`が空になり、seed Jobを作成しない。

Document Analysis Azure mode用のresourceはworkloadではなくfoundationとして作成する。
`environment-stack`は環境ごとに次を作成する。

- `FormRecognizer`のDocument Intelligence resource
- `AIServices`のMicrosoft Foundry resource
- Document Analysis専用Storage Accountと`document-analysis-input`、
  `document-analysis-result` container
- Backend Document Analysis AI専用User Assigned Managed Identity
- Backend Document Analysis Storage専用User Assigned Managed Identity
- AI resourceとcontainer scopeのRBAC
- Private Endpoint専用subnet、Private Endpoint、Private DNS zone、VNet link

resource名はGitHub Environment variableまたは`terraform.tfvars`から渡す。
staging/productionでresourceを共有しない。例は次のとおりで、実値はglobal uniqueな名前にする。

```hcl
document_intelligence_account_name     = "di-ewf-stg-jpe-unique"
content_understanding_account_name     = "aif-ewf-stg-jpe-unique"
document_analysis_storage_account_name = "stewfdocstgjpeunique"
private_endpoint_subnet_prefixes       = ["10.40.3.0/24"]
document_analysis_enabled              = false
document_intelligence_enabled          = false
content_understanding_enabled          = false
```

productionの`private_endpoint_subnet_prefixes`既定値は`["10.50.3.0/24"]`である。
`document_analysis_enabled`、`document_intelligence_enabled`、
`content_understanding_enabled`の既定値はfalseであり、resource作成の有無ではなくBackend runtimeの
有効化だけを制御する。Backendにはendpoint、container名、専用identity client IDだけを渡し、API Key、
client secret、Storage key、connection string、SASはTerraform、Key Vault、Container App環境変数へ
登録しない。
Document Analysisのretention cleanup intervalとbatch sizeはapplication既定値を使うため、Terraform
variableとして追加しない。

環境rootは確認用にDocument Intelligence、Foundry、Document Analysis Storage、専用identityの名前、
endpoint、client IDをoutputする。key、connection string、token、SASはoutputしない。

## ローカル静的検証

```bash
make verify-infra
```

このターゲットは`terraform fmt -check`、bootstrap・staging・production各rootの
`terraform init -backend=false`と`validate`に加え、Backend probe、内部Backend URL、
staging限定の手動seed Job名とproduction guard、経費証憑container・identity・RBAC境界、
Document Analysis Azure resource・UAMI・RBAC・Private Endpoint・Private DNS・Backend環境変数境界を
検証する。
各rootへ`.terraform/`を生成するが、
Azureへのlogin、plan、applyは行わない。Terraformにも`-no-color`を渡すため、CIログへANSI
制御文字を出力しない。

旧`make terraform-check`は移行用の警告付きエイリアスである。文書、CI、新しい手順では
`make verify-infra`を使用する。
