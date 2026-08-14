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
Better Auth sessionが有効でもKeycloak tokenを更新できない状態は発生し得るため、BFFの401時に
ローカルのBetter Auth Cookieを明示的に削除して認証状態を収束させる。

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
`/login?reason=session-expired`では「セッションの有効期限が切れました。再度ログインしてください。」
と表示する。このURLでは残存sessionがあっても`/top`へ自動redirectせず、期限切れ時の
redirect loopを防ぐ。

ログイン後の業務画面は`app/(workspace)/layout.tsx`でBetter Auth sessionを共通検証する。
sessionがない場合は`/login`へ戻す。Route Groupを使用するためURLは従来どおり
`/top`、`/expenses`、`/approvals`、`/organization-chart`、`/admin/...`のままである。
`/top`は固有のモックダッシュボード本文だけを返し、認証確認、ヘッダー、サイドメニューは
ワークスペース共通処理へ分離する。

ワークスペース共通処理はpathnameの完全一致だけでlayout modeを決める。`/top`は
Navigation-orientedとし、`md`以上では15remの常設サイドメニュー、`md`未満ではヘッダー左端の
ハンバーガーから開くshadcn/ui Sheetを使用する。`/top`以外はすべてContent-orientedとし、viewport幅に
かかわらず常設サイドメニューの列を予約せず、同じSheetを現在内容の上へ重ねる。Headerの旧横型
モバイルナビゲーションは表示しない。常設サイドメニューとSheetは、権限・機能フラグ・雇用区分・
active routeを判定する同じnavigation描画を共有する。Sheetはリンク選択とroute変更で閉じ、開閉状態を
Cookie、Web Storage、URL、Backendへ保存しない。

利用者情報はBetter Authのprofileを直接表示せず、`WorkspaceGate`がBFFの
`/api/backend/me`からSpring Bootの`GET /api/me`結果を一度取得し、`CurrentUserContext`へ
保持する。正常時は`CurrentUserProvider`、共通アプリケーションシェル、各ページ本文の順に表示する。
認証済み画面間のClient-side navigationでは共通シェルが維持され、各ページが利用者権限だけを
目的として`/api/backend/me`を重複取得しない。

共通ヘッダーの利用者領域はshadcn/uiの`Dropdown Menu`であり、アバター、表示名、email、所属部署を
表示する。メニューは`CurrentUserContext`の既取得情報だけを使い、開閉時にBFFへ追加リクエストを
送らない。所属部署がない場合は`所属未設定`と表示する。

`(workspace)`配下のproductionレスポンスは`no-store`であり、logout後のブラウザ戻る操作でも
認証済み画面をcacheから再利用しない。E2Eで使うNext.js開発サーバーは`no-cache, must-revalidate`を
返すが、再利用前にサーバーでの再検証を強制するため同じく認証済み画面を再利用しない。

`WorkspaceGate`での未登録403では`/unregistered`、その他の利用不可403では`/unavailable`へ
遷移する。`/login`、`/unregistered`、`/unavailable`、`/api/**`、`/`はワークスペース外であり、
共通ヘッダー、Drawerナビゲーション、サイドメニューを表示しない。どちらの画面にもJWT、token、
内部URL、例外、stack traceを表示しない。

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

session不在、provider account不在、token取得・更新失敗、またはSpring Bootの401はすべて
認証必須の401として扱う。BFFはBetter Authが返した`Set-Cookie`を引き継いだ後、受信した
通常名、`__Secure-`名、chunkを含むBetter Auth Cookieの削除を最後に付与する。401応答は
`no-store`とする。

Client ComponentからBFFへの通信は共通clientを使用する。共通clientは401の場合だけ
`/login?reason=session-expired`へ一度だけ`location.replace`し、呼び出し元の処理を中断する。
`WorkspaceGate`の利用者情報取得も同じclientを使用するため、session期限切れ時の既存処理を
変更しない。更新・送信操作を再ログイン後に自動再送しない。403、404、409、5xx、接続失敗は
各画面の既存処理へ渡す。

token更新時にBetter Authが返すSet-CookieはRoute Handlerからブラウザへ引き継ぐ。
access token、refresh token、ID tokenをClient Component、localStorage、
sessionStorageへ渡さない。

汎用proxyのallowlistはメール通知履歴のcollectionとUUID詳細にGETだけを許可する。
`/api/me.features.mailNotificationHistory`は環境名を公開せずローカル機能の有効性だけを返し、
`MAIL_NOTIFICATION_READ`との両方を満たす場合だけメニューを表示する。

## 検証

`make test SUITES=frontend`、`make test SUITES=e2e`、`make verify`、`make audit-frontend`は
Dockerボリュームを削除せず、
次を確認する。

- frontendのlint、TypeScript、production build
- 一般ユーザーと管理者の表示名、email、所属、業務権限
- 一般ユーザーがヘッダーのユーザー情報メニューを開き、表示名、email、所属部署を確認できること
- 未登録ユーザーの403と専用画面
- 利用不可画面に内部情報が含まれないこと
- Keycloak Authorization Code Flowによる3ユーザーのログイン
- callback後の`/top`遷移とKeycloak logout
- logout時のCookie失効と、logout後の`/top`から`/login`へのredirect
- 認証済みページとlogoutレスポンスが`no-store`であること
- 暗号化されたsession/account Cookie
- BFFでのサーバー側access token取得
- token更新不能時のBetter Auth Cookie削除と期限切れログイン画面への一方向遷移
- Top以外のログイン後画面でもBFFの401を同じ期限切れ動作へ変換すること
- 401以外では認証Cookieを削除せず、自動再ログインやリクエスト再送を行わないこと
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
