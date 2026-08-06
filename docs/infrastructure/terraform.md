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

## ローカル静的検証

```bash
make verify-infra
```

このターゲットは`terraform fmt -check`、bootstrap・staging・production各rootの
`terraform init -backend=false`と`validate`に加え、Backend probe、内部Backend URL、
staging限定の手動seed Job名とproduction guard、経費証憑container・identity・RBAC境界を検証する。
各rootへ`.terraform/`を生成するが、
Azureへのlogin、plan、applyは行わない。Terraformにも`-no-color`を渡すため、CIログへANSI
制御文字を出力しない。

旧`make terraform-check`は移行用の警告付きエイリアスである。文書、CI、新しい手順では
`make verify-infra`を使用する。
