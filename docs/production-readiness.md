# 本番移行・Entra ID仕様

現在のCompose構成、サンプルsecret、開発ユーザー、Mailpitはローカル開発専用である。
この文書は本番実装そのものではなく、本番化するときに省略できない変更境界を記録する。

## KeycloakからMicrosoft Entra IDへの移行箇所

認証方式は引き続きOpenID Connect Authorization Code FlowとPKCEを使用する。
業務権限はIdPのroleへ移さず、PostgreSQLで管理する現在の境界を維持する。

### IdPとクライアント登録

- Entra IDへWebアプリを登録し、対象tenantへ限定する
- redirect URIを
  `https://<application-host>/api/auth/oauth2/callback/<provider-id>`として登録する
- post logout redirect URIを`https://<application-host>/login`として登録する
- client secretまたは証明書をsecret managerで管理する
- `openid profile email`に相当する最小scopeだけを許可する

### frontend

変更対象は主に次である。

- `frontend/src/lib/auth.ts`
  - Generic OAuthのprovider ID
  - issuer、authorization、token、userinfo endpoint
  - client IDとcredential
  - 必要に応じたemail・表示名claimの正規化
- `frontend/src/lib/backend-client.ts`
  - `getAccessToken`へ渡すprovider ID
- `frontend/src/app/api/auth/logout/route.ts`
  - Entra IDのlogout endpointとpost logout redirect
- `frontend/src/lib/environment.ts`とCompose/deployment環境変数
  - Keycloak固有名をIdP中立の設定へ移行

Better AuthのDBなしsession、暗号化されたHTTP-only Cookie、BFFでのサーバー側token使用は
維持できる。ブラウザへaccess tokenを渡す構成へ変更しない。

### backend

- Spring Security Resource Serverの`issuer-uri`と`jwk-set-uri`をEntra IDへ変更する
- audienceが業務APIのApplication ID URIまたはclient IDと一致する検証を追加する
- tenant IDを固定し、想定外tenantのtokenを拒否する
- email、subject、表示名claimの差異を正規化する
- `workflow.security.identity-provider`をIdP中立の識別子へ変更する

業務ユーザーとアクセス申請は`issuer + external_subject`を外部IDとしている。
issuerとsubjectがKeycloakから変わるため、切替前に既存ユーザーとの対応表を作り、
監査可能なDB migrationで新しい外部IDへ関連付ける。emailだけの自動突合で権限を
引き継がない。

### 削除・置換するローカル要素

- Composeの`keycloak`、`keycloak-init`とKeycloak用DB bootstrap
- Realm template、User Profile設定、開発ユーザー自動作成
- Keycloak固有のPhase検証
- PlaywrightのKeycloakログイン画面操作

E2EではEntra IDの実tenantへ常時依存させるか、CI専用tenantとテストユーザーを用意する。
認証自体をmockへ置き換えたテストだけで受け入れを完了しない。

## 本番利用前に必須のセキュリティ変更

### secretと資格情報

- `.env.example`の全`replace-with-*`と`password`を十分な強度の値へ変更する
- `.env`配布ではなく、権限管理・rotation・監査が可能なsecret managerを使用する
- IdP、DB、SMTPの資格情報を用途別に分離し、最小権限にする
- build argument、image layer、Git、ログへsecretやtokenを含めない
- 漏えい時にBetter Auth Cookieを全失効できるrotation手順を用意する

### HTTP、Cookie、proxy

- ApplicationとIdPを信頼されたHTTPS originで公開し、HTTPをHTTPSへredirectする
- Secure、HTTP-only、SameSiteを本番hostと認証フローに合わせて検証する
- `BETTER_AUTH_URL`、trusted origin、redirect URI、issuerを本番URLへ固定する
- reverse proxyが渡すforwarded headerの信頼境界を限定する
- HSTS、CSP、frame制御、MIME sniffing防止などのresponse headerを設定する
- 認証開始とAPIへ共有可能なrate limit storeを用意する

### アプリケーションとデータ

- `workflow.seed.enabled=false`とし、開発ユーザー・サンプルpasswordを作成しない
- Mailpitを本番構成から除外し、認証済みSMTP/TLSと実管理者宛先を設定する
- PostgreSQLとbackendを引き続き非公開networkに置く
- database migrationを専用権限で管理し、runtime userへDDL権限を与えない
- backup、restore試験、暗号化、保持期間、個人情報削除手順を定める
- 未登録通知に含める個人情報を最小化し、監査ログの閲覧権限を限定する

### 運用

- production imageとOS packageを継続的に脆弱性scanする
- dependency updateは互換性検証後にlockfile単位で適用する
- health以外のActuator endpointを外部公開しない
- 認証失敗、権限拒否、通知失敗、DB障害をsecretなしで監視・alertする
- Keycloakを継続利用する場合は管理consoleを一般公開せず、管理者MFAと定期更新を行う
- 障害対応、credential rotation、IdP停止、DB restoreのrunbookを用意する
