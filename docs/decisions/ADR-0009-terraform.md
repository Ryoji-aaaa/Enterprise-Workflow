# ADR-0009: Azure infrastructureをTerraformで管理する

- Status: Accepted

## 決定

bootstrap、staging、productionを別stateとし、Azure Blob backendをOIDC/RBACで利用する。
secret値はTerraform resourceにせずKey Vaultへ人間が投入する。Portalで作った既存resourceは
importしてから変更する。

## 理由と帰結

環境差分と変更履歴をreviewでき、手作業driftを抑制できる。PostgreSQL管理passwordは
providerの初回入力としてstateに含まれるため、state Storageの権限を厳格化する。
