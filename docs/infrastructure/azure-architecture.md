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
決定までは通知送信が成功する前提にしない。SMTP未設定または障害は未登録ユーザーの
メール通知だけを利用不能にし、認証済みユーザー向けの通常の業務APIは提供を継続する。

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

全Container Appは共通の環境別User Assigned Managed Identityを持つ。ACRからのpullには
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
