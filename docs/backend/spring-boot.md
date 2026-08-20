# Spring Boot仕様

## 採用技術

- Java 21
- Maven 3.9.16
- Spring Boot 4.1.0
- Spring Security 7.1.0
- Spring MVC
- Spring Security OAuth2 Resource Server
- Spring Data JPA
- PostgreSQL Driver
- Flyway
- Spring Boot Mail
- Spring Boot Actuator
- JUnit 6、MockMvc、Spring Security Test、H2

依存関係とテストはDocker内で実行し、JavaとMavenをホストへ導入しない。
Spring Bootコンテナの8080番ポートはホストへ公開しない。

## Resource Server

`/api/**`はBearer JWTを必須とする。署名鍵はDocker内部のKeycloak JWK Set endpoint
から取得し、tokenのissuerはブラウザが受け取る外部issuer
`http://localhost:8180/realms/workflow`と照合する。

Resource Serverの署名・有効期限検証後、業務層で次を再検証する。

- `iss`が設定値と一致する
- `sub`が存在する
- `email`が存在する
- `email_verified`が`true`
- emailが`ALLOWED_EMAIL_DOMAIN`の会社ドメインに属する、または`ALLOWED_EXTERNAL_EMAILS`に
  正規化後の完全一致で含まれる
- `aud`または`azp`が`workflow-web`と一致する

JWT、Client Secret、パスワードはログへ出力しない。業務ユーザーは
`user_external_identities`の`issuer + external_subject`から解決し、業務権限はJWTや
Keycloak RoleではなくPostgreSQLのロール・権限テーブルから取得する。

## API

### `GET /api/me`

登録済みかつ有効な業務ユーザーに、次の情報を返す。

```json
{
  "id": "UUID",
  "externalSubject": "OIDC subject",
  "email": "example.user1@sdcj.co.jp",
  "displayName": "開発一般ユーザー",
  "department": {
    "name": "開発部"
  },
  "roles": ["APPLICATION_USER"]
}
```

- JWTなし・署名不正・issuer不正: HTTP 401
- emailクレーム不正、許可ドメイン外、Client不一致: HTTP 403
- DB未登録: HTTP 403、`APPLICATION_USER_NOT_REGISTERED`
- DB上で利用不可または有効期間外: HTTP 403

エラーは内部例外やJWTを含めず、`code`と利用者向け`message`だけを返す。

### `GET /actuator/health`

認証なしで利用できる。公開するActuator endpointはhealthだけとし、詳細情報は返さない。

## 業務データ

PostgreSQLのschemaは`db/migration`内のFlyway Versioned Migrationで管理する。
backend起動時に未適用のmigrationを順番に適用し、履歴とchecksumを
`flyway_schema_history`へ記録する。HibernateのDDL生成とSpring Boot SQL Initializationは
通常実行時に使用しない。運用方法は[Flyway仕様](flyway.md)を参照する。

### ユーザー・組織・権限・監査

`app_users`はIdPに依存しない業務ユーザーとアカウント状態・有効期間を管理する。
外部IDは`user_external_identities`、部署・役職は組織テーブル、操作権限はロール・権限
テーブルへ分離する。詳細は次を参照する。

- [ユーザー管理](user-management.md)
- [組織・所属・役職管理](organization-management.md)
- [業務認可](authorization.md)
- [監査ログ](audit-logging.md)

### `access_requests`

Keycloakには存在するが`app_users`に存在しない社内ユーザーを記録する。
`issuer + external_subject`で一意とし、再アクセス時は同じ行の最終日時と回数を更新する。
APIが403を返して外側のトランザクションが終了しても記録が残るよう、独立した
トランザクションで保存する。

## Mailpit通知

ローカル開発環境だけでTransactional OutboxからMailpitへ配送する。未登録利用申請、経費承認依頼、
最終承認、差戻しを対象とし、同一業務transactionで通知要求を保存する。利用申請はqueue時刻から
既定15分を抑制し、SMTP失敗は有限回再試行する。詳細は
[ローカルメール通知Outbox](notification-outbox.md)を参照する。

既定とAzureは`disabled`で、SMTP、Dispatcher、Outbox行、通知履歴APIを登録しない。

## テスト

`make test SUITES=backend`は開発ユーザー定義の整合性を確認し、Dockerの`test-runtime`
image内でJUnitを実行した後、一時PostgreSQLでmigrationを検証する。
内部ネットワークを外部接続可能へ変更せず、Maven依存の取得はbuildネットワーク内に限定する。
JUnitのAPI結合テストではH2とHibernate `create-drop`を使用し、Flywayは無効化する。
PostgreSQL固有のmigrationは`make test SUITES=backend`、起動済みローカルDBの適用状態は
`make verify`で検証する。

結合テストでは、JWTなし、不正issuer、email不備、許可ドメイン外、Client不一致、
登録済み、無効、未登録、冪等更新、通知抑制、メール失敗を検証する。

## 公式資料

- [Spring Security 7.1 Resource Server JWT](https://docs.spring.io/spring-security/reference/7.1/servlet/oauth2/resource-server/jwt.html)

実際に解決されている7.1.0の公式仕様を基準とする。
