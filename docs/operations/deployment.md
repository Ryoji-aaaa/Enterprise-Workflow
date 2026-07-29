# Azureデプロイ

初回は次の順番で行う。

1. bootstrap resourceとOIDC/RBACを作る。
2. 環境を`provision_workloads=false`でapplyする。
3. Key Vaultへ6個の秘密値を登録する。
4. GitHub Environmentの`PROVISION_WORKLOADS`を`true`にする。
5. staging workflowを手動実行するか、実装をmainへmergeする。
6. SHA image push、Terraform apply、Keycloak設定、smoke testの成功を確認する。
7. production workflowを`foundation` phaseで実行する。
8. production Key Vaultへ別の秘密値を登録する。
9. `workloads` phaseを選び、stagingで検証した同じ40文字SHAを入力する。

通常のstaging deployはmainのCI成功を起点に自動実行する。productionは手動だけであり、
ACRに3 imageが存在することを確認してから同じtagを適用する。`latest`は作成も参照もしない。

smoke testはfrontend、OIDC discovery、login入口を匿名で確認する。Backend Actuatorは
external URLを持たないため、Container Apps revisionのprobeとLog Analyticsで確認する。
本番でテストユーザーを使う完全E2Eは行わない。

Azureには現時点でメールサービスを配置しない。SMTP未設定または障害があってもBackendの
liveness/readinessはmailを評価せず、通常の業務APIを提供できる状態をReadyとする。
未登録ユーザーのアクセス要求はDBへ保存されるが、管理者へのメール通知は送信されない。
メールサービス導入後は配送成否をprobeから独立した監視として追加する。

デプロイ後はPortalで最新Backend revisionがActiveかつRunning、trafficが100%、
replicaが1以上であることを確認する。再ログイン後に`/api/backend/me`が
`BACKEND_UNAVAILABLE`にならないことを確認する。業務DB未登録ユーザーは
`APPLICATION_USER_NOT_REGISTERED`から未登録ユーザー画面へ進めば正常であり、登録済み
ユーザーはTopページが表示されることを確認する。Portalからprobeや環境変数を変更せず、
差異があればTerraformを修正する。

Frontend revisionの`BACKEND_INTERNAL_URL`はTerraformがBackend ingressから取得した
`https://<backend-name>.internal.<environment-default-domain>`形式であることを確認する。
Backend ingressはinternalのままとし、外部URLやBrowserから直接疎通確認しない。
`BACKEND_UNAVAILABLE`が続く場合はFrontendログで接続先とproxy errorを確認し、Backend
ログで`/api/me`到達を確認する。環境変数をPortalから修正せず、Terraformの生成値を直して
Frontend revisionを更新する。
