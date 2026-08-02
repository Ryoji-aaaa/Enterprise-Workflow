# Next.js・Better Auth仕様

## 採用技術

- Node.js 24.18.0
- Next.js 16.2.11（App Router）
- React 19.2.4
- Better Auth 1.6.25
- Tailwind CSS 4.3.3
- shadcn/ui CLI 4.15.0
- TypeScript 5.9.3

依存バージョンは`frontend/package.json`と`package-lock.json`で固定する。
Node.jsやnpmをホストへ導入せず、依存解決、静的検査、production buildをDocker内で行う。
UIテーマ、コンポーネント追加方針、関連設定は
[shadcn/ui・Tailwind CSS仕様](shadcn-tailwind.md)を参照する。

Next.js 16.2.11が直接固定する古いPostCSSとoptional dependencyのsharpには
公開済みsecurity advisoryがあるため、`overrides`でPostCSS 8.5.23とsharp 0.35.3を使用する。
Next.jsとBetter Authの指定バージョンは変更しない。production dependencyは
`make audit-frontend`がDocker内で`npm audit --omit=dev`を実行し、既知の脆弱性が0件で
あることを確認する。

## データベースなしのセッション

Better Authにはdatabase adapterを設定しない。OAuth state、Next.jsセッション、
Keycloakのaccess token・refresh token・ID tokenは、`BETTER_AUTH_SECRET`を用いて
署名・暗号化したCookieへ保存する。

- session cookie cacheはJWE方式
- OAuth stateはCookie方式
- provider account情報は暗号化されたaccount cookieへ保存
- CookieはHTTP Only、SameSite=Lax
- productionではSecure属性を有効化
- sessionの有無はServer ComponentまたはRoute Handlerで検証

Cookieの存在だけで認証済みとは扱わない。DBを使わないため、サーバー側からの個別session
失効はできない。Keycloak側のsession失効、tokenの有効期限、アプリのCookie削除を組み合わせる。

## Generic OAuthとKeycloak

Better AuthのGeneric OAuth provider IDは`keycloak`とする。要求scopeは
`openid profile email`で、Authorization Code FlowとPKCE S256を使用する。

callback URIは次のとおり。

```text
http://localhost:3000/api/auth/oauth2/callback/keycloak
```

Docker構成では、ブラウザとNext.jsサーバーで到達可能なURLが異なる。

- authorization endpoint: `KEYCLOAK_ISSUER`を基準とする外部URL
- token/userinfo endpoint: `KEYCLOAK_INTERNAL_URL`と`KEYCLOAK_REALM`を基準とする内部URL

この分離により、ブラウザは`localhost:8180`へ遷移し、Next.jsコンテナは
`keycloak:8080`でcode交換とuser info取得を行う。Client Secretはサーバー専用環境変数であり、
ブラウザへ渡さない。

Keycloakの既存Realmは削除・再importしない。`configure-keycloak.sh`がAdmin REST APIで
既存Clientのredirect URIとweb originを冪等に更新する。開発ユーザーにはfirst nameと
last nameを設定し、初回ログイン時のプロフィール補完画面を発生させない。

## 認証画面

`/login`にはKeycloakログインを開始するボタンだけを配置し、メールアドレスやパスワードの
入力欄を置かない。認証後は`/top`へ遷移する。

`/top`はServer ComponentでBetter Auth sessionを検証する。sessionがない場合は
`/login`へ戻す。利用者情報はBetter Authのprofileを直接表示せず、BFFが取得した
Spring Bootの`GET /api/me`結果を表示する。レスポンスは`no-store`であり、logout後の
ブラウザ戻る操作でも認証済み画面をcacheから再利用しない。

未登録403では`/unregistered`、その他の利用不可403では`/unavailable`へ遷移する。
どちらの画面にもJWT、token、内部URL、例外、stack traceを表示しない。

## ログアウト

`POST /api/auth/logout`は、Better Authのsign-outをサーバー側で実行し、受信した
Better Auth session/account Cookieとchunk Cookieを`Max-Age=0`で明示的に失効させる。
その後、ブラウザをKeycloakのlogout endpointへリダイレクトする。

KeycloakにはClient IDと登録済みpost logout redirect URIだけを渡し、access token、
refresh token、ID tokenをURLへ含めない。Keycloak logout後は`/login`へ戻る。
logoutレスポンスは`no-store`かつ`Clear-Site-Data: "cache"`とする。

## BFF

`src/lib/backend-client.ts`と`GET /api/backend/me`がBFF境界を構成する。

1. Route HandlerでBetter Auth sessionを検証
2. Better Authの`getAccessToken`をサーバー側で実行
3. Docker内部の`BACKEND_INTERNAL_URL`へアクセス
4. `Authorization: Bearer`ヘッダーをSpring Bootへだけ付与
5. 5秒でtimeout
6. 401、未登録403、その他403、5xx、接続失敗、timeoutを画面用結果へ変換
7. tokenや内部例外をレスポンス・ログへ出力しない

token更新時にBetter Authが返すSet-CookieはRoute Handlerからブラウザへ引き継ぐ。
access token、refresh token、ID tokenをClient Component、localStorage、
sessionStorageへ渡さない。

## 検証

`make test-frontend`、`make test-e2e`、`make verify`、`make audit-frontend`は
Dockerボリュームを削除せず、
次を確認する。

- frontendのlint、TypeScript、production build
- 一般ユーザーと管理者の表示名、email、所属、業務権限
- 未登録ユーザーの403と専用画面
- 利用不可画面に内部情報が含まれないこと
- Keycloak Authorization Code Flowによる3ユーザーのログイン
- callback後の`/top`遷移とKeycloak logout
- logout時のCookie失効と、logout後の`/top`から`/login`へのredirect
- 認証済みページとlogoutレスポンスが`no-store`であること
- 暗号化されたsession/account Cookie
- BFFでのサーバー側access token取得
- Spring Boot `/api/me`のHTTP 200と業務ユーザー情報
- 401、403、5xx、接続失敗、timeoutの安全な変換
- TopページとBFFレスポンスにtoken materialが含まれないこと
- frontendがdatabase networkやDB環境変数を持たないこと
- backendがホストポートを持たないこと

検証用curlは一時Cookie jarを使用し、パスワード、OAuth code、tokenをログへ表示しない。

## 公式資料

- [Better Auth Next.js Integration](https://better-auth.com/docs/integrations/next)
- [Better Auth Generic OAuth](https://better-auth.com/docs/plugins/generic-oauth)
- [Better Auth Database](https://better-auth.com/docs/concepts/database)
- [Better Auth Session Management](https://better-auth.com/docs/concepts/session-management)
- [Better Auth Cookies](https://better-auth.com/docs/concepts/cookies)
