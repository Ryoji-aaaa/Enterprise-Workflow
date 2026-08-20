# Azure上のKeycloak

Azure用[`keycloak/azure.Dockerfile`](../../keycloak/azure.Dockerfile)はローカルと同じ
Keycloak 26.7.0を固定し、`kc.sh build`済みimageを`start --optimized`相当で起動する。
PostgreSQL、固定HTTPS hostname、health、metricsを有効にする。`start-dev`とrealm
template内の開発ユーザーはAzureで使わない。

health endpointはmanagement port 9000の次を使う。

```text
startup    /health/started
liveness   /health/live
readiness  /health/ready
```

deploy後、`scripts/configure-keycloak-azure.sh`がAdmin REST APIを使って`workflow` realmと
confidential clientを冪等設定する。callbackは実装どおり
`<frontend>/api/auth/oauth2/callback/keycloak`、web originはfrontend origin、
post logout URIは`<frontend>/login`である。client secretとbootstrap admin passwordは
Key Vaultから一時取得し、ログへ出さない。

stagingのGuest userは通常deployでは作成せず、`job-ewf-stg-seed-kc`または
`job-ewf-stg-seed-all`を明示実行して同期する。Jobは管理者認証後、User Profileの
email patternを会社ドメインまたはGuest 4アドレスの完全一致へ先に更新・検証し、
通常development user、Guestの順に同期する。Guest passwordは既存staging Key Vault secret
`guest-seed-password`を既存runtime identityで参照し、TerraformやGitHub Actionsへ値を複製しない。
productionにはGuest allowlist、secret参照、manual seed Jobを作成しない。

管理APIは現時点でKeycloakのexternal endpointと同じ入口にある。初期検証後はcustom
domain、WAF/IP制限、管理用の別経路を設計する。高可用性の複数replica化はcache構成を
含む別作業とする。
