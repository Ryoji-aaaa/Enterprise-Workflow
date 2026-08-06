# 認証フロー

## ログイン

1. BrowserがNext.jsの`/login`からログインを開始する。
2. Next.jsがBetter Auth Generic OAuthを介してBrowserをKeycloakへリダイレクトする。
3. Keycloakが利用者を認証し、Authorization CodeをNext.js callbackへ返す。
4. Next.jsサーバーが内部URLでcodeをtokenへ交換する。
5. Better AuthがOAuth state、session、provider accountを暗号化したHTTP-only Cookieへ保存する。
6. Next.jsがサーバー側でaccess tokenを取得し、Spring Bootの`GET /api/me`へ送る。
7. Spring BootがJWTを検証し、`issuer + subject`から外部ID対応と業務ユーザーを解決する。
8. Spring Bootがアカウント状態・有効期間とDB上の業務権限を確認し、`employmentType`、
   `permissions`、環境名を含まない機能可否`features`を含む業務ユーザー情報を返す。
9. Next.jsが業務情報を`/top`へ表示する。

access token、refresh token、ID tokenはブラウザJavaScript、localStorage、
sessionStorage、画面レスポンスへ公開しません。

## 利用拒否

- JWTがない、またはissuerが不正な場合、Spring BootはHTTP 401を返す。
- email未検証、許可ドメイン外、Client不一致の場合はHTTP 403を返す。
- Keycloakには存在しても業務DBへ未登録の場合、利用申請を冪等記録してHTTP 403を返す。
- 業務ユーザーが`SUSPENDED`、`DISABLED`、`RETIRED`または有効期間外の場合、
  Keycloakで認証済みでもHTTP 403を返す。
- 管理操作に必要なDB権限がない場合、HTTP 403を返して認可拒否を監査する。
- Next.jsは未登録とその他の利用不可を別画面へ変換し、内部情報を表示しない。

## 事前登録ユーザーの初回連携

外部ID対応がなく、検証済みJWTのemailに一致する`PRE_REGISTERED`ユーザーが存在する場合、
Spring Bootは初回だけ`user_external_identities`へissuerとsubjectを登録する。同じ
トランザクションでユーザーを`ACTIVE`へ変更し、状態変更履歴と監査ログを追記する。

連携後はemailではなく`issuer + subject`から業務ユーザーを解決する。Keycloak Roleは
業務認可に使用しない。`GET /api/me`が返す`ORGANIZATION_CHART_READ`などの権限は
PostgreSQLの現在のロール割当から解決する。Frontendは表示制御に利用するが、Spring Bootも
各APIで同じDB認可を必ず実行する。
メール通知履歴の表示には`MAIL_NOTIFICATION_READ`と
`features.mailNotificationHistory=true`の両方を使うが、Backend APIの認可正本は前者である。

## ログアウト

Next.jsはBetter Authのsign-outを実行し、session/account Cookieを明示的に失効させた後、
BrowserをKeycloak logout endpointへリダイレクトします。認証済みページとlogout応答は
cacheされず、ログアウト後に`/top`を再利用できません。

詳細は[Next.js・Better Auth仕様](../frontend/nextjs-better-auth.md)と
[Spring Boot仕様](../backend/spring-boot.md)、
[ユーザー管理](../backend/user-management.md)、
[業務認可](../backend/authorization.md)を参照してください。
