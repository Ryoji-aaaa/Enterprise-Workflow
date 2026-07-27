# ADR-0008: Azure Container Appsを実行基盤とする

- Status: Accepted

## 決定

staging/productionを独立したAzure Container Apps Environmentへ配置する。Next.jsと
Keycloakだけをexternal、Spring Bootをinternal ingressとし、PostgreSQLをprivate VNetへ
置く。MailpitはAzureへ配置しない。

## 理由と帰結

既存コンテナとBFF境界を保ちながらmanaged revision、probe、Managed Identityを利用できる。
初期は各アプリ1 replicaとし、Keycloak HAとcustom domain/WAFは後続作業とする。
