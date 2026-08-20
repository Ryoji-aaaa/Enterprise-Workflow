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

Document Analysisは環境ごとにAzure AI Document Intelligence resource、
Microsoft Foundry用の`AIServices` resource、専用Storage Account、Private Endpoint、Private DNSを
Terraformで作成する。Document Intelligenceは`FormRecognizer`、Foundryは`AIServices`で、どちらも
SKUは`S0`、custom subdomainを設定し、local authenticationとpublic network accessを無効化する。
Document Intelligenceは`prebuilt-layout`とAPI `2024-11-30`、Content Understandingは
`prebuilt-layout`とAPI `2025-11-01`を使う。自動入力PoC Phase 1Aではstagingだけ、Foundry child resourceに
`auto-entry-gpt-5-2`（`gpt-5.2`、`2025-12-11`）と
`auto-entry-text-embedding-3-large`（`text-embedding-3-large`、`1`）を`GlobalStandard` capacity 150、
`NoAutoUpgrade`で作成する。productionにはこのmodel deploymentを作成しない。Custom AnalyzerのCopy/Ready、
AnalyzerへのGPT/embedding deployment設定、`updateDefaults`は作成しない。

Document Analysis用Storage Accountは経費証憑用Storage Accountと分離し、
`document-analysis-input`と`document-analysis-result`の2つの非公開containerだけを持つ。Shared Key、
Storage connection string、SASは使わず、OAuthを既定認証にする。soft deleteは7日であり、Plan8以降の
application retention cleanupとは別の誤削除復旧windowである。

Backend Container Appには既存のruntime identity、既存の経費証憑Blob専用identityに加え、
Document Analysis AI専用identityとStorage専用identityをattachする。既存の`AZURE_CLIENT_ID`は
経費証憑Blob専用identityのclient IDのまま維持し、Document Analysis AIには
`AZURE_DOCUMENT_ANALYSIS_CLIENT_ID`、Document Analysis Storageには
`DOCUMENT_ANALYSIS_STORAGE_MANAGED_IDENTITY_CLIENT_ID`を使う。AI専用identityにはDocument Intelligence
resource scopeの`Cognitive Services User`とFoundry resource scopeの
`Cognitive Services Content Understanding Reader`だけを付与する。Storage専用identityにはinput/result
各container scopeの`Storage Blob Data Contributor`だけを付与し、Storage Account全体やFrontend、
Keycloak、seed Jobへは付与しない。

Private Endpoint専用subnetはContainer Apps subnet、PostgreSQL subnetと分ける。既定CIDRはstagingが
`10.40.3.0/24`、productionが`10.50.3.0/24`である。Document IntelligenceとFoundryは`account`
subresource、Document Analysis Storageは`blob` subresourceだけをPrivate Endpoint化する。Private DNS
zoneは`privatelink.cognitiveservices.azure.com`、`privatelink.openai.azure.com`、
`privatelink.services.ai.azure.com`、`privatelink.blob.core.windows.net`を環境VNetへlinkする。
BackendからのAzure AI/Blob呼び出しはPrivate Endpoint経由で行い、Browserは引き続きNext.js BFFだけへ
通信する。

Document Analysis runtimeは`WORKFLOW_DOCUMENT_ANALYSIS_ENABLED`、`DOCUMENT_INTELLIGENCE_ENABLED`、
`CONTENT_UNDERSTANDING_ENABLED`で有効化する。これらはFrontend公開用Feature Flagではなく、全体または
Providerを運用上停止するruntime controlである。通常提供時は3つを有効にし、
`WORKFLOW_DOCUMENT_ANALYSIS_EXECUTION_MODE=azure`とする。stagingのDocument Intelligence、Content
Understanding、Private DNS、RBAC、cost、retention確認前には有効化しない。
application retention cleanupはBackendの既定値で1時間ごと、最大50件ずつ実行し、期限切れJobの
input/result Blobだけを削除してPostgreSQL metadataを`EXPIRED`として残す。Azure Storageの7日soft
deleteは誤削除復旧windowであり、application retentionの代替ではない。

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
seed passwordはstaging Key Vaultの`development-seed-password`と`guest-seed-password`を
同じ既存runtime Managed Identityでそれぞれ参照する。staging BackendとKeycloakは会社ドメインに加え、
Guest 4アドレスだけを完全一致で許可する。productionではこれらのJob、
Guest secret参照、Guest allowlistを作成しない。詳細は
[開発・staging用seedデータ](../backend/development-seed-data.md)を参照する。

Container Appsを含むTerraform管理リソースをAzure Portalから直接変更しない。Portalは
revision、traffic、replica、ログの確認に使用し、構成変更はTerraformへ反映してapplyする。
