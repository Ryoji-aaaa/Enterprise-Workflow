# 認証フロー

## ログイン

1. BrowserがNext.jsの`/login`からログインを開始する。
2. Next.jsがBetter Auth Generic OAuthを介してBrowserをKeycloakへリダイレクトする。
3. Keycloakが利用者を認証し、Authorization CodeをNext.js callbackへ返す。
4. Next.jsサーバーが内部URLでcodeをtokenへ交換する。
5. Better AuthがOAuth state、session、provider accountを暗号化したHTTP-only Cookieへ保存する。
6. Next.jsがサーバー側でaccess tokenを取得し、Spring Bootの`GET /api/me`へ送る。
7. Spring BootがJWTを検証し、PostgreSQLの業務ユーザー情報を返す。
8. Next.jsが業務情報を`/top`へ表示する。

access token、refresh token、ID tokenはブラウザJavaScript、localStorage、
sessionStorage、画面レスポンスへ公開しません。

## 利用拒否

- JWTがない、またはissuerが不正な場合、Spring BootはHTTP 401を返す。
- email未検証、許可ドメイン外、Client不一致の場合はHTTP 403を返す。
- Keycloakには存在しても業務DBへ未登録の場合、利用申請を冪等記録してHTTP 403を返す。
- Next.jsは未登録とその他の利用不可を別画面へ変換し、内部情報を表示しない。

## ログアウト

Next.jsはBetter Authのsign-outを実行し、session/account Cookieを明示的に失効させた後、
BrowserをKeycloak logout endpointへリダイレクトします。認証済みページとlogout応答は
cacheされず、ログアウト後に`/top`を再利用できません。

詳細は[Next.js・Better Auth仕様](../frontend/nextjs-better-auth.md)と
[Spring Boot仕様](../backend/spring-boot.md)を参照してください。
