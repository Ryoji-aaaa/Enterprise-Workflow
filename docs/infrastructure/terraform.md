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

Content Understandingの自動入力PoC Phase 1Aでは、`environment == "staging"`のときだけ
Foundry (`AIServices`) のchild resourceとして次のmodel deploymentを作成する。runtime controlの
`document_analysis_enabled`、`document_intelligence_enabled`、`content_understanding_enabled`は、これらの
resourceの作成有無を制御しない。

| deployment name | model | version | SKU | capacity | version upgrade |
| --- | --- | --- | --- | --- | --- |
| `auto-entry-gpt-5-2` | `gpt-5.2` | `2025-12-11` | `GlobalStandard` | 150 | `NoAutoUpgrade` |
| `auto-entry-text-embedding-3-large` | `text-embedding-3-large` | `1` | `GlobalStandard` | 150 | `NoAutoUpgrade` |

productionではPhase 1Aのmodel deploymentを作成しない。Custom AnalyzerのCopy/Ready確認と
Analyzerへのmodel deployment設定もTerraformでは行わない。stagingのBackendにはTerraform resource参照から
Analyzer IDと2つのdeployment名だけを渡し、model名・version・capacityをBrowserや環境変数へ渡さない。

stagingで既存のContent Understanding Foundry accountを管理する場合、既存値に合わせて
`project_management_enabled=true`とSystem Assigned Managed IdentityをTerraformに明示する。public networkと
local authenticationは引き続き無効とする。これによりaccount、Private Endpoint、Content Understanding Reader
RBACの置換を避ける。productionにPhase 1A固有の設定は追加しない。

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
variableとして追加しない。`CONTENT_UNDERSTANDING_AUTO_ENTRY_*`はsecretではなく、stagingのみBackendへ
渡す設定値であり、GitHub Environment variableやKey Vault secretとして追加しない。

環境rootは確認用にDocument Intelligence、Foundry、Document Analysis Storage、専用identityの名前、
endpoint、client IDをoutputする。key、connection string、token、SASはoutputしない。

## ローカル静的検証

```bash
make verify-infra
```

このターゲットは`terraform fmt -check`、bootstrap・staging・production各rootの
`terraform init -backend=false`と`validate`に加え、Backend probe、内部Backend URL、
staging限定の手動seed Job名とproduction guard、経費証憑container・identity・RBAC境界、
Document Analysis Azure resource・UAMI・RBAC・Private Endpoint・Private DNS・Backend環境変数境界と、
staging限定model deploymentの固定model/version/SKU/capacityを検証する。
各rootへ`.terraform/`を生成するが、
Azureへのlogin、plan、applyは行わない。Terraformにも`-no-color`を渡すため、CIログへANSI
制御文字を出力しない。

## PR/deploy planの安全ゲート

環境別`terraform plan -out=tfplan`の後、`terraform-plan.yml`と`deploy-staging.yml`は
`terraform show -json tfplan`を`scripts/check-terraform-plan-safety.sh`へ渡す。deleteまたはreplaceを含むplanは失敗とし、Cognitive Account、
Storage Account/container、Container Apps Environment、VNet/subnet、Private Endpoint、Private DNS zone、
PostgreSQL、Key Vaultを少なくとも保護対象として表示する。今回の一時Attachment Blob Diagnostic Settingの削除だけは
完全修飾resource addressによる一時allowlistを使える。型全体の例外は使わず、削除完了後のplanに対象deleteがなければ
allowlistは渡さない。`deploy-staging.yml`は安全ゲート成功後だけ同じ`tfplan`をapplyする。通常のmodel deployment作成と
Container Appのin-place image/environment更新は許可される。

## staging-planとstagingのruntime control整合

merge前に担当者がGitHub Environmentの`staging-plan`で次の3値を`true`へ変更し、実`staging`と一致させる。

```text
WORKFLOW_DOCUMENT_ANALYSIS_ENABLED=true
DOCUMENT_INTELLIGENCE_ENABLED=true
CONTENT_UNDERSTANDING_ENABLED=true
```

Environment variable変更後はPR Terraform planを再実行し、Backend Container Appの3値に`true`から`false`への
差分がないことをacceptance conditionとする。Environment variableはTerraformやworkflowから変更しない。

旧`make terraform-check`は移行用の警告付きエイリアスである。文書、CI、新しい手順では
`make verify-infra`を使用する。
