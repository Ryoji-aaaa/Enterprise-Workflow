# OAuth 2.0・OpenID Connect認証と業務認可フロー

## 目的と用語

このアプリケーションでは、Keycloakによる本人認証と、PostgreSQLによる業務認可を分離する。
OAuth 2.0はAPIへ提示するaccess tokenを扱う認可フレームワークであり、OpenID Connect（OIDC）は
OAuth 2.0へ利用者の本人確認を追加する認証レイヤーである。本システムはOIDC Authorization Code
FlowとPKCE S256を使って利用者を認証し、OAuth 2.0 Resource ServerであるSpring Bootへ
access tokenを提示する。

この文書では、次の呼称を使用する。

| 構成要素 | OAuth 2.0・OIDC上の役割 | 本システムでの責務 |
| --- | --- | --- |
| Browser | User Agent | ログイン画面と業務画面を表示し、Next.jsへCookieと業務リクエストを送る |
| Next.js / Better Auth | confidential OIDC Client、Relying Party、BFF | Authorization Code Flowを開始し、codeをtokenへ交換し、tokenをブラウザJavaScriptから隠してBackendへ中継する |
| Keycloak | OpenID Provider、Authorization Server | 利用者のcredentialを検証し、authorization codeとtokenを発行する |
| Spring Boot | OAuth 2.0 Resource Server | access tokenを検証し、外部IDを業務ユーザーへ解決し、各APIを認可する |
| Keycloak DB | Keycloakのidentity store | Keycloakユーザー、credential、Realm、Clientを保持する |
| Workflow DB | 業務ユーザー・認可の正本 | `app_users`、外部ID対応、所属、ロール、Permission、監査データを保持する |

ローカル環境ではKeycloak DBとWorkflow DBは同じPostgreSQLコンテナ内にあるが、`keycloak`と
`workflow`という別データベースであり、接続ユーザーも異なる。KeycloakだけがKeycloak DBへ、
Spring BootだけがWorkflow DBへ接続する。Next.jsとBrowserはどちらのDBにも接続しない。

## 認証と認可の分担

Keycloakでのログイン成功は、ワークフローアプリの利用許可を意味しない。

```text
Keycloakで本人認証
  「このtokenのsubjectは誰か」
            ↓
Spring Bootで外部IDを業務ユーザーへ対応付け
  「このsubjectはどのapp_users.idか」
            ↓
Workflow DBで業務認可
  「このapp_users.idは現在この操作を許可されているか」
```

KeycloakのRealm RoleとClient Roleは業務認可に使用しない。Spring BootはJWT内のRole名を
PostgreSQLの業務Roleとして採用せず、Workflow DBの現在のロール割当とPermissionを毎回評価する。
Frontendのメニュー非表示も認可の正本ではなく、直接URLまたはBFF APIを呼ばれた場合も
Spring Bootが同じ認可を実行する。

## 識別子と対応関係

`system-solution-project-1.user@sdcj.co.jp`を例にすると、同じ人物に複数の識別子がある。
UUIDの実値は環境作成時に変わるため、次では記号で表す。

| データ | 例 | 発行・管理元 | 用途 |
| --- | --- | --- | --- |
| OIDC Client ID | `workflow-web` | Keycloak | tokenの発行先Clientを識別する |
| issuer | `http://localhost:8180/realms/workflow` | Keycloak Realm | tokenを発行したIdP境界を識別する |
| Keycloak User ID | `<keycloak-user-uuid>` | Keycloak | Keycloak内の利用者を識別する |
| JWT `sub` | `<keycloak-user-uuid>` | Keycloak | tokenが表す外部主体を識別する |
| `app_users.id` | `<app-user-uuid>` | Workflow DB | 業務データ、所属、Role割当、監査主体を識別する |
| email | `system-solution-project-1.user@sdcj.co.jp` | KeycloakとWorkflow DB | 初回外部ID連携の候補検索と画面表示に使う |

Keycloak User IDと`app_users.id`は別物である。Workflow DBの`user_external_identities`が
次の対応を保持する。

```text
user_external_identities
  identity_provider = keycloak
  issuer            = http://localhost:8180/realms/workflow
  external_subject  = <keycloak-user-uuid>  # JWT sub
  user_id           = <app-user-uuid>       # app_users.id
  external_email    = system-solution-project-1.user@sdcj.co.jp
```

外部ID連携後はemailではなく`issuer + external_subject`を正本として業務ユーザーを解決する。
同じ文字列のemailを持つ別の外部主体へ業務権限を自動で移さないためである。

## 全体シーケンス

```text
Browser          Next.js / Better Auth       Keycloak       Spring Boot      Workflow DB
  |                       |                      |                |                |
  | POST sign-in          |                      |                |                |
  |---------------------->|                      |                |                |
  | 302 authorization URL |                      |                |                |
  |<----------------------|                      |                |                |
  | authorization request                        |                |                |
  |--------------------------------------------->|                |                |
  | username / password                          |                |                |
  |--------------------------------------------->|                |                |
  | callback?code=...&state=...                  |                |                |
  |<---------------------------------------------|                |                |
  | callback              |                      |                |                |
  |---------------------->| token request        |                |                |
  |                       |--------------------->|                |                |
  |                       | token response       |                |                |
  |                       |<---------------------|                |                |
  | encrypted HTTP-only Cookie                  |                |                |
  |<----------------------|                      |                |                |
  | GET /top              |                      |                |                |
  |---------------------->| session確認          |                |                |
  | GET /api/backend/me   |                      |                |                |
  |---------------------->| access token取得     |                |                |
  |                       | GET /api/me + Bearer |                |                |
  |                       |-------------------------------------->|                |
  |                       |                      |  JWK取得       |                |
  |                       |                      |<---------------|                |
  |                       |                      |                | issuer+sub検索  |
  |                       |                      |                |--------------->|
  |                       |                      |                | Role/Permission|
  |                       |                      |                |<---------------|
  |                       | business user JSON   |                |                |
  |                       |<--------------------------------------|                |
  | business user JSON    |                      |                |                |
  |<----------------------|                      |                |                |
```

KeycloakがSpring Bootへtokenを直接送るのではない。Next.jsがBrowserから受け取った暗号化Cookieを
サーバー側で検証し、取り出したaccess tokenを業務APIリクエストごとにSpring Bootへ送る。

## 1. ログイン開始

Browserが`/login`の通常ログインを選択すると、Client ComponentはBetter Authへ次を渡す。

```text
providerId = keycloak
callbackURL = /top
errorCallbackURL = /login?error=oauth
```

Better AuthはOAuth stateとPKCE用`code_verifier`を生成・保護し、BrowserをKeycloakの
authorization endpointへ遷移させる。主要なAuthorization Request parameterは次のとおりである。

```text
client_id=workflow-web
response_type=code
redirect_uri=http://localhost:3000/api/auth/oauth2/callback/keycloak
scope=openid profile email
state=<推測困難な値>
code_challenge=<code_verifierのS256 hash>
code_challenge_method=S256
```

`state`は開始したログイン要求とcallbackを対応付ける。PKCEはcodeを取得した主体が、ログイン開始時の
`code_verifier`を保持する正当なClientであることをtoken endpointで確認する。

Guest Loginも同じClient、scope、Authorization Code Flow、PKCEを使用する。違いはBetter Authが
生成したauthorization URLへ`login_hint=guest00@example.com`を追加することだけである。
`login_hint`はKeycloakの入力補助であり、本人認証、password入力、業務認可を省略しない。

## 2. Keycloakでの本人認証

BrowserはKeycloakのログイン画面へemailとpasswordを送る。credentialを受け取り検証するのは
Keycloakであり、Next.js、Spring Boot、Workflow DBへpasswordは渡らない。

KeycloakはRealm `workflow`のKeycloak DBで、利用者の有効状態、credential、email、
`email_verified`などを確認する。成功すると一度だけ使用できる短時間のauthorization codeを発行し、
BrowserをNext.js callbackへ戻す。

```text
GET /api/auth/oauth2/callback/keycloak?code=<authorization-code>&state=<state>
```

この時点でBrowserがURL上で運ぶのはauthorization codeとstateであり、access tokenではない。
Keycloakでの本人認証が成功しても、Workflow DBへの登録状態やPermissionはまだ確認していない。

## 3. Authorization Codeとtokenの交換

Next.jsサーバーのBetter Authはcallbackのstateを開始時のstateと照合する。続いて、Docker内部URLの
Keycloak token endpointへ概ね次を送る。

```text
grant_type=authorization_code
code=<authorization-code>
redirect_uri=http://localhost:3000/api/auth/oauth2/callback/keycloak
client_id=workflow-web
client_secret=<Next.jsサーバー専用secret>
code_verifier=<ログイン開始時に生成した値>
```

Keycloakはcodeの有効性と未使用状態、Client、redirect URI、Client認証、PKCEを確認し、
access token、ID token、refresh tokenを返す。Next.jsは設定済みの内部userinfo endpointも使って
OIDC profileを取得する。

| token | 主な用途 | Spring Bootへ送るか | Browser JavaScriptへ公開するか |
| --- | --- | --- | --- |
| access token | Spring Boot APIへのBearer credential | 送る | 公開しない |
| ID token | OIDCログイン結果とprovider accountの情報 | 送らない | 公開しない |
| refresh token | access tokenのサーバー側更新 | 送らない | 公開しない |

Better Authはdatabase adapterを持たず、OAuth state、session、provider accountとtokenを
`BETTER_AUTH_SECRET`で署名・暗号化したHTTP-only Cookieへ保存する。CookieはBrowserがNext.jsへ
送信するが、Client Component、localStorage、sessionStorage、業務画面レスポンスにはtokenを出さない。

## 4. `/top`と`GET /api/me`

callback成功後、Browserは`/top`へ遷移する。ここでは二段階の確認を行う。

1. `(workspace)/layout.tsx`がBetter Auth sessionをサーバー側で確認する。
2. `WorkspaceGate`がBrowserからBFFの`GET /api/backend/me`を呼ぶ。

第一段階で分かるのは、Better Authのログインsessionが存在することだけである。sessionがなければ
`/login`へ戻す。第二段階で初めてSpring Bootが外部ID、業務ユーザー状態、所属、Role、Permissionを
確認する。

BFFは`GET /api/backend/me`を受けると、次を実行する。

1. 受信CookieからBetter Auth sessionを検証する。
2. provider ID `keycloak`のaccess tokenをサーバー側で取得し、必要ならrefresh tokenで更新する。
3. Docker内部のSpring Bootへ次のリクエストを送る。

```http
GET /api/me HTTP/1.1
Host: backend:8080
Authorization: Bearer <access-token>
Accept: application/json
```

Spring Bootへはaccess tokenだけを送り、password、authorization code、Better Auth Cookie、
Keycloak SSO Cookie、ID token、refresh tokenは送らない。

## 5. Spring Bootによるaccess token検証

Spring BootはstatelessなOAuth 2.0 Resource Serverであり、`/api/**`にBearer JWTを要求する。
Spring SecurityはKeycloakの内部JWK Set endpointから署名公開鍵を取得・cacheし、JWTの署名、
有効期限、issuerなどを検証する。自己完結型JWTを検証するため、各APIリクエストでKeycloakへ
token introspectionを行わない。

Resource Serverの検証後、業務層は次のclaimを再確認する。

| claim | 確認内容 | 用途 |
| --- | --- | --- |
| `iss` | 設定済みのKeycloak issuerと一致 | 発行元Realmの固定 |
| `sub` | 空でない | Keycloak外部主体の識別 |
| `email` | 空でない | 初回外部ID連携候補と利用者属性 |
| `email_verified` | `true` | 未検証emailの拒否 |
| `email` domain | 会社domainまたは外部email完全一致allowlist | 利用可能なidentity境界 |
| `aud`または`azp` | `workflow-web`を含む、または一致 | 別Client向けtokenの拒否 |
| `name` / `preferred_username` | あれば表示名候補として取得 | 外部identityの表示属性 |

JWT内のKeycloak Roleは業務Permissionへ変換しない。

## 6. 外部IDと業務ユーザーの解決

JWT検証後、Spring Bootは`iss`と`sub`を次の値へ正規化する。

```text
AuthenticatedIdentity
  issuer      = JWT iss
  subject     = JWT sub
  email       = lower-case JWT email
  displayName = JWT name、preferred_username、emailの順でfallback
```

最初に`user_external_identities`を`issuer + external_subject`で検索し、有効な対応があれば
`user_id`から`app_users`を取得する。次に`account_status=ACTIVE`かつ現在が利用可能期間内であることを
確認する。

外部ID対応がない初回アクセスだけ、検証済みJWTのemailで`PRE_REGISTERED`ユーザーを検索する。
外部ID未連携の`ACTIVE`ユーザーも後方互換のため候補に含む。候補が一意かつ
利用可能期間内なら、同じトランザクションで次を行う。

1. `user_external_identities`へ`issuer + subject -> app_users.id`を登録する。
2. `PRE_REGISTERED`なら`ACTIVE`へ変更する。
3. 状態変更履歴と監査ログを追記する。

以後はemailではなく外部ID対応を使う。対応がなくemail候補もない場合は`access_requests`を
`issuer + external_subject`単位で冪等記録し、HTTP 403 `APPLICATION_USER_NOT_REGISTERED`を返す。

## 7. 業務Role、Permission、所属の解決

`GET /api/me`は解決済み`app_users.id`を起点として、概念上次を参照する。

```text
app_users
  ├─ user_organization_assignments
  │    ├─ organization_units
  │    └─ positions
  └─ user_role_assignments
       └─ roles
            └─ role_permissions
                 └─ permissions
```

`GET /api/me`は業務ユーザー、現在の主所属、Role、Permission、Frontendが利用する機能可否を返す。
具体的なseedユーザーとRole・Permissionの対応は、migration、seed定義、テストを正本とし、この文書へ
複製しない。responseの概形は次のとおりである。

```json
{
  "id": "<app-user-uuid>",
  "externalSubject": "<keycloak-user-uuid>",
  "email": "<email>",
  "displayName": "<display-name>",
  "employmentType": "<employment-type>",
  "department": {
    "name": "<organization-unit-name>"
  },
  "roles": [
    "<role-code>"
  ],
  "permissions": [
    "<permission-code>"
  ],
  "features": {
    "mailNotificationHistory": true
  }
}
```

`features.mailNotificationHistory`はローカルのdelivery modeが`local-mailpit`の場合に`true`となり、
環境名自体は返さない。メール履歴メニューの表示にはさらに`MAIL_NOTIFICATION_READ`が必要である。

Next.jsは成功レスポンスを`CurrentUserContext`へ保持し、Role、Permission、雇用区分、機能可否から
共通ナビゲーションを描画する。

## ログイン後の画面遷移とAPI認可例

### 組織図画面

`ORGANIZATION_CHART_READ`を持つ利用者が`/organization-chart`へ遷移すると、Browserは次を呼ぶ。

```http
GET /api/backend/organization-chart
```

BFFは再度sessionとaccess tokenを確認し、Spring Bootへ次を送る。

```http
GET /api/organization-chart
Authorization: Bearer <access-token>
```

Spring BootはAPIリクエストごとにJWTを検証して`app_users.id`を解決し、Controllerの
`@PreAuthorize`から`ORGANIZATION_CHART_READ`をWorkflow DBで判定する。

```text
app_users.id
  -> 有効なuser_role_assignments
  -> enabledなroles
  -> role_permissions
  -> ORGANIZATION_CHART_READ
```

Permissionがあっても、組織図Serviceは雇用区分が`REGULAR_EMPLOYEE`または
`ASSOCIATE_EMPLOYEE`で、利用者が現在有効であることを追加確認する。すべて成功すると組織・所属を
Workflow DBから取得し、Spring Boot JSON、BFF、Browserの順で返す。

Spring BootはHTTP sessionを持たない。同じServlet request内では解決済み業務ユーザーを再利用するが、
次の業務APIリクエストではJWT検証、業務ユーザー解決、Backend認可を再実行する。

### 管理画面への直接アクセス

`USER_READ`や`USER_UPDATE`などの管理Permissionを持たない利用者には、Frontendは管理メニューを
表示しない。その利用者がURLやBFF APIを直接呼んでもSpring BootのPermission判定がHTTP 403を返し、
認可拒否を既存の監査方針に従って記録する。Keycloakでログイン済みであることや
`APPLICATION_USER` Roleは、管理Permissionを代替しない。

## 利用拒否とHTTP status

| 状況 | 主な判定箇所 | 結果 |
| --- | --- | --- |
| Keycloak passwordが不正 | Keycloak | Keycloakログイン失敗 |
| state、code、redirect URI、Client認証、PKCEが不正 | Better Auth / Keycloak | OAuth callbackまたはtoken交換失敗 |
| Bearer tokenがない、署名不正、期限切れ | Spring Security Resource Server | HTTP 401 |
| issuerが設定値と不一致 | Spring Boot claim検証 | HTTP 401 |
| `sub`、email、email verified、許可domain、Clientが不正 | Spring Boot claim検証 | HTTP 403 |
| KeycloakにはいるがWorkflow DB未登録 | 外部ID・業務ユーザー解決 | HTTP 403 `APPLICATION_USER_NOT_REGISTERED` |
| 業務ユーザーが停止、退職、無効または期間外 | 業務ユーザー解決 | HTTP 403 |
| 必要なDB Permissionがない | `PermissionAuthorizer`とWorkflow DB | HTTP 403、認可拒否監査 |
| Permissionはあるがowner、Candidate、雇用区分などの業務条件を満たさない | 各業務Service | HTTP 403 |

Next.jsは未登録403を`/unregistered`、その他の利用不可403を`/unavailable`へ変換し、
JWT、token、内部URL、例外、stack traceを画面へ出さない。

## セッション・認証期限切れ

Better Auth session、Keycloak access token、refresh token、Keycloak SSO sessionは有効期限と
失効タイミングが一致するとは限らない。Better Auth sessionが残っていてもaccess tokenを取得・更新
できない場合、またはSpring BootがHTTP 401を返した場合、BFFは認証状態全体を失効済みとして扱う。

BFFはHTTP 401を返すと同時に、受信したBetter Authのsession、account、chunk Cookieを削除する。
Browserの共通BFF clientは失敗したリクエストを再送せず、
`/login?reason=session-expired`へ`location.replace`で遷移する。利用者が明示的に再ログインした後は
`/top`へ戻る。期限切れ処理ではKeycloak logoutを呼ばず、残っているKeycloak SSO sessionは
次のログイン開始時にKeycloakが判定する。

HTTP 403は業務上の利用拒否、HTTP 5xxと接続失敗は一時的な利用不可として扱う。これらでは
認証Cookieを削除せず、自動再ログインや更新系リクエストの自動再送も行わない。

## ログアウト

Browserがログアウトすると、Next.jsはBetter Authのsign-outを実行し、session、provider account、
chunk Cookieを明示的に失効させる。その後、BrowserをKeycloak logout endpointへリダイレクトする。

Keycloak logout URLへ渡すのはClient IDと登録済みpost logout redirect URIだけであり、access token、
refresh token、ID tokenをURLへ含めない。Keycloak logout後は`/login`へ戻る。認証済みページと
logout応答をcacheしないため、Browserの戻る操作で`/top`を再利用できない。

## 関連仕様

- [Next.js・Better Auth仕様](../frontend/nextjs-better-auth.md)
- [Spring Boot仕様](../backend/spring-boot.md)
- [Keycloak / OpenID Connect仕様](../infrastructure/keycloak.md)
- [ユーザー管理](../backend/user-management.md)
- [業務認可](../backend/authorization.md)
- [監査ログ](../backend/audit-logging.md)
