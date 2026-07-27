# Azureアーキテクチャ

stagingとproductionはResource Group、VNet、Container Apps Environment、Log
Analytics、PostgreSQL Flexible Server、Key Vault、Managed Identity、Container Apps、
Terraform state keyを分離する。Azure Container Registryだけを共有する。

```text
Internet ──> Next.js Container App ──internal ingress──> Spring Boot
    └──────> Keycloak Container App                         │
                       │                                    │
                       └────────private VNet────────> PostgreSQL
```

Next.jsとKeycloakだけがexternal ingressを持つ。Spring Bootはinternal ingressであり、
PostgreSQLはpublic networkを無効化したdelegated subnet上に置く。Next.jsにはDB設定を
渡さない。MailpitはAzureへ配置しない。Azure上のメール配送サービスは未決定であり、
決定までは通知送信が成功する前提にしない。

全Container Appは共通の環境別User Assigned Managed Identityを持つ。ACRからのpullには
`AcrPull`、Key Vault secret参照には`Key Vault Secrets User`を使い、ACR admin userや
レジストリpasswordは使わない。Key VaultはGitHub-hosted runnerから秘密値を取得する
必要があるためpublic endpointを有効にしているが、Azure RBACでアクセスを制限する。

Container Appsは当初min/max replicaを1とする。Keycloakの複数replica化、cache stack、
zone冗長化、メールサービス、custom domain/WAFは今回の対象外である。
