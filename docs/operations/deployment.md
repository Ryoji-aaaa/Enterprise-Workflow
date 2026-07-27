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
