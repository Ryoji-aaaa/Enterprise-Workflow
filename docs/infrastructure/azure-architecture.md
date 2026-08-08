# Azureアーキテクチャ

stagingとproductionはResource Group、VNet、Container Apps Environment、Log
Analytics、PostgreSQL Flexible Server、Key Vault、Managed Identity、Container Apps、
経費証憑用Storage Account、Terraform state keyを分離する。Azure Container Registryだけを共有する。

```text
Internet ──> Next.js Container App ──internal ingress──> Spring Boot
    └──────> Keycloak Container App                         │
                       │                                    │
                       └────────private VNet────────> PostgreSQL
                                                            │
                                                            └──> private Blob container
```

Next.jsとKeycloakだけがexternal ingressを持つ。Spring Bootはinternal ingressであり、
PostgreSQLはpublic networkを無効化したdelegated subnet上に置く。Next.jsにはDB設定を
渡さない。MailpitはAzureへ配置しない。stagingとproductionは通知delivery modeを常に
`disabled`とし、SMTP、メール配送、通知Outbox行、管理者向けメール履歴API・画面を提供しない。
Azure上のメール配送サービスは未決定であり、将来導入時は別設計として認可・監査・監視を見直す。

Backend Container Appはstartupとreadinessで`/actuator/health/readiness`、livenessで
`/actuator/health/liveness`を使う。livenessはアプリケーションの生存状態だけ、
readinessはアプリケーションの受付状態と業務DBを確認し、SMTPはどちらにも含めない。
ローカルComposeではMailpitと総合`/actuator/health`によるhealthcheckを維持する。
Azureへメールサービスを導入する際は、通知配送の監視とアラートをprobeとは別に追加する。

Frontendの`BACKEND_INTERNAL_URL`には、TerraformのBackend moduleがAzureRMから取得した
internal ingress FQDNを`https://`付きで渡す。internal ingressのFQDNは
`<backend-name>.internal.<environment-default-domain>`となり、同じContainer Apps
Environment内のFrontendからだけ到達できる。環境のdefault domainへBackend名を直接
連結するとexternal ingress形式になり、internal Backendへrouteされないため使用しない。
Backendをexternalへ変更せず、Browserからの業務APIアクセスは引き続きFrontend BFFを
経由する。

経費証憑は環境別のStorageV2 accountにある非公開`expense-evidence` containerへ保存する。
Storage AccountはHTTPS/TLS 1.2以上、public container無効、shared key無効、OAuth既定とし、
Blob versioningとlifecycle自動削除は使わない。Blobおよびcontainerのsoft deleteは30日である。
BackendにはBlob専用User Assigned Managed Identityを追加し、container scopeの
`Storage Blob Data Contributor`だけを割り当てる。Frontend、Keycloak、共通runtime identityには
Blob RBACを付与しない。BackendにはBlob endpoint、container名、専用identityのclient IDだけを
渡し、connection string、Storage key、SASは使用しない。

Document AnalysisのBackend codeはAzure AI Document Intelligence AdapterとAzure AI Content
Understanding Adapterに対応しているが、Document Intelligence resource、Microsoft Foundry
resource、専用Managed Identity、RBAC、Private Endpoint、Private DNSはまだTerraformで作成しない。
`execution-mode=azure`はコード上の実行modeとして存在するものの、staging/productionでは後続工程まで
有効化しない。両Adapterの認証はAPI Keyやclient secretではなくMicrosoft Entra IDの
`DefaultAzureCredential`を使用し、将来有効化する場合はBackendへUser Assigned Managed Identity
client IDだけを環境変数で渡す。Content Understandingは`prebuilt-layout`とAPI `2025-11-01`に
固定し、Plan7までFoundry model deployment、RBAC、Private Endpoint、Private DNSを追加しない。

全Container Appは共通の環境別User Assigned Managed Identityを持ち、Backendだけが前述のBlob専用
identityも持つ。ACRからのpullには
`AcrPull`、Key Vault secret参照には`Key Vault Secrets User`を使い、ACR admin userや
レジストリpasswordは使わない。Key VaultはGitHub-hosted runnerから秘密値を取得する
必要があるためpublic endpointを有効にしているが、Azure RBACでアクセスを制限する。

Container Appsは当初min/max replicaを1とする。Keycloakの複数replica化、cache stack、
zone冗長化、メールサービス、custom domain/WAFは今回の対象外である。

stagingでは通常のFrontend、Backend、Keycloak Container Appsに加え、開発用データを手動で
投入する`job-ewf-stg-seed-db`、`job-ewf-stg-seed-kc`、`job-ewf-stg-seed-all`を同じ
Container Apps Environmentに置く。Jobはscheduleを持たず、通常deployから自動開始しない。
seed passwordはstaging Key Vaultの`development-seed-password`をManaged Identityで参照する。
productionではこれらのJobとsecretを作成しない。詳細は
[開発・staging用seedデータ](../backend/development-seed-data.md)を参照する。

Container Appsを含むTerraform管理リソースをAzure Portalから直接変更しない。Portalは
revision、traffic、replica、ログの確認に使用し、構成変更はTerraformへ反映してapplyする。
