# ADR-0010: AzureでもKeycloakを継続する

- Status: Accepted

## 決定

Keycloak 26.7.0のoptimized production imageをContainer Appsで稼働し、環境別PostgreSQLを
使う。realm/clientはdeploy時にAdmin REST APIで冪等設定し、secretはKey Vaultに置く。

## 理由と帰結

既存issuer/clientとローカル認証モデルを保てる。一方、運用、version更新、admin endpoint
保護、backup、将来の複数replica cacheはチームの責任として残る。
