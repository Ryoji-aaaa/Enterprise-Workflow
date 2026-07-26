この文書は、ワークフローアプリの初期プロトタイプ構築時に使用した作業Planの原本です。
現在の仕様や操作方法はREADMEおよびdocs配下の現行文書を参照してください。
Phase表記は初期構築履歴として本書内にのみ保存しています。

# ワークフローアプリ・プロトタイプ実装Plan

## 1. 目的

WSL上の次のディレクトリに、ワークフローアプリのプロトタイプをモノレポで構築する。

```text
~/projects/workflow
```

以下の一連の動作をローカルDocker環境で確認できる状態を完成条件とする。

1. Docker開発環境を構築する
2. Next.jsのログイン開始画面を表示する
3. ログインボタンからKeycloakへ遷移する
4. Keycloakで認証する
5. Next.jsへ戻り、認証済みセッションを確立する
6. Next.jsのサーバー処理からSpring BootへJWT付きでアクセスする
7. Spring BootがJWTを検証する
8. PostgreSQLの事前登録ユーザーを取得する
9. Topページにユーザー情報を表示する
10. ログアウトできる
11. 未登録の社内ユーザーからアクセスされた場合、管理者へ通知する
12. Playwrightによる自動テストを実行する

プロトタイプのため、将来のワークフロー機能や高度なDB設計は実装しない。

---

# 2. 作業上の基本ルール

## 2.1 作業ディレクトリ

```bash
mkdir -p ~/projects/workflow
cd ~/projects/workflow
```

既存ファイルがある場合は、削除や上書きを行う前に内容を確認すること。

## 2.2 実装方針

* モノレポで構成する
* ローカル実行はDocker Composeに統一する
* 起動、停止、初期化、ビルド、テストはMakefileから操作できるようにする
* 開発者がKeycloak管理画面やPostgreSQLへ手作業で初期データを登録しなくても再現できるようにする
* シークレットをGitへコミットしない
* `.env.example`のみGit管理対象とする
* 実装途中でも各フェーズ終了時にビルドとテストを実行する
* 不要な機能や独自フレームワークを追加しない
* 認証・認可の省略やモックへの置き換えは行わない

---

# 3. 技術構成

## 3.1 フロントエンド

* Next.js
* TypeScript
* App Router
* React Server Components
* Better Auth
* Better Auth Generic OAuthプラグイン
* KeycloakとのOpenID Connect連携
* Tailwind CSS
* npm
* Node.js LTS

## 3.2 バックエンド

* Java 21 LTS
* Spring Boot
* Maven
* Spring Web
* Spring Security
* Spring Security OAuth2 Resource Server
* Spring Data JPA
* PostgreSQL Driver
* Spring Boot Actuator
* Spring Boot Mail
* Bean Validation

Spring Bootは、Java 21で利用可能な最新の安定版を選択すること。

Spring Boot 4系を採用する場合は、利用するライブラリの互換性を確認する。互換性に問題がある場合は、Java 21をサポートする安定したSpring Boot 3系を選択してよい。

## 3.3 認証基盤

* Keycloak
* OpenID Connect
* Authorization Code Flow
* PKCE
* Realm設定の自動インポート
* 自己登録無効
* Keycloak上のロールを業務権限には使用しない

## 3.4 データベース

* PostgreSQL
* 業務ユーザー情報のみを管理
* Spring Bootからのみ接続可能
* Next.jsからPostgreSQLへの接続は禁止

## 3.5 メール確認環境

* Mailpit
* 未登録ユーザー通知メールのローカル確認に使用

## 3.6 テスト

* Playwright
* Spring BootのJUnitテスト
* 必要最小限のNext.js単体テスト

---

# 4. バージョン選定ルール

実装開始時点で、次の優先順位に従ってバージョンを決定し、固定する。

1. LTSバージョン
2. 安定版
3. 公式にサポートされている組み合わせ
4. Docker公式イメージが提供されているバージョン
5. RC、Beta、Canary、Snapshotは使用しない

最低限、次をファイル上で固定する。

* Javaバージョン
* Mavenバージョン
* Node.jsバージョン
* Next.jsバージョン
* Better Authバージョン
* Spring Bootバージョン
* PostgreSQLバージョン
* Keycloakバージョン
* Playwrightバージョン

`latest`タグは原則として使用しない。

---

# 5. モノレポ構成

次の構成を基本とする。

```text
workflow/
├── frontend/
│   ├── src/
│   │   ├── app/
│   │   │   ├── api/
│   │   │   │   ├── auth/
│   │   │   │   │   └── [...all]/
│   │   │   │   │       └── route.ts
│   │   │   │   └── backend/
│   │   │   │       └── me/
│   │   │   │           └── route.ts
│   │   │   ├── login/
│   │   │   │   └── page.tsx
│   │   │   ├── top/
│   │   │   │   └── page.tsx
│   │   │   ├── unauthorized/
│   │   │   │   └── page.tsx
│   │   │   ├── layout.tsx
│   │   │   └── page.tsx
│   │   ├── components/
│   │   └── lib/
│   │       ├── auth.ts
│   │       ├── auth-client.ts
│   │       └── backend-client.ts
│   ├── Dockerfile
│   └── package.json
├── backend/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   └── resources/
│   │   └── test/
│   ├── Dockerfile
│   └── pom.xml
├── keycloak/
│   ├── realm-template.json
│   └── scripts/
│       └── initialize-keycloak.sh
├── scripts/
│   ├── setup.sh
│   ├── wait-for-service.sh
│   ├── initialize.sh
│   └── verify.sh
├── tests/
│   └── e2e/
│       ├── playwright.config.ts
│       └── specs/
├── docker-compose.yml
├── Makefile
├── .env.example
├── .gitignore
├── README.md
└── PLAN.md
```

構成は実装上必要な範囲で調整してよいが、責務の境界は変更しないこと。

---

# 6. システム境界

## 6.1 許可する通信

```text
ブラウザ
  ↓
Next.js
  ├── Better Auth
  ├── Server Component
  └── Route Handler
        ↓ JWT
Spring Boot
  ├── JWT検証
  ├── 業務ユーザー・権限判定
  ├── 未登録ユーザー通知
  └── PostgreSQLアクセス
        ↓
PostgreSQL
```

認証時のみ、ブラウザはNext.jsからKeycloakへリダイレクトされる。

```text
ブラウザ
  ↓
Next.jsログイン画面
  ↓
Keycloak
  ↓
Next.jsコールバック
```

## 6.2 禁止する通信

以下は禁止する。

```text
ブラウザ → Spring Bootへの直接APIアクセス
ブラウザ → PostgreSQL
Next.js → PostgreSQL
Keycloak → 業務ユーザーテーブル
```

Spring Bootのホストポートは原則として公開しない。

Next.jsコンテナからDocker内部ネットワーク経由でのみ接続させる。

例：

```text
http://backend:8080
```

PostgreSQLもホストポートを原則公開せず、Spring Bootからのみ接続可能にする。

開発時にDB調査が必要な場合も、通常構成にはホスト公開を追加しない。

---

# 7. Better Authの設計

## 7.1 基本方針

Better Authは、KeycloakとのOIDC連携とNext.js側のセッション管理に使用する。

Better Auth独自のメール・パスワード認証は実装しない。

```text
emailAndPassword.enabled = false
```

KeycloakはBetter AuthのGeneric OAuthプロバイダーとして設定する。

## 7.2 ステートレス構成

「PostgreSQLへアクセスできるのはSpring Bootだけ」という条件を守るため、Better Authから業務DBへ接続しない。

Better Authはデータベースアダプターを指定しないステートレス構成とする。

セッション情報は署名・暗号化されたHTTP Only Cookieで管理する。

次を満たすこと。

* `BETTER_AUTH_SECRET`を環境変数から読み込む
* CookieをHTTP Onlyにする
* 本番モードではSecure属性を有効にする
* SameSite属性を適切に設定する
* 保護ページではサーバー側でセッションを検証する
* MiddlewareでCookieの存在だけを確認して認可完了としない
* アクセストークンをlocalStorageまたはsessionStorageへ保存しない
* アクセストークンをClient Componentへ渡さない

Generic OAuthと完全ステートレス構成の組み合わせについて、採用バージョンの公式仕様と実動作を確認すること。

採用バージョンでこの組み合わせが成立しない場合、独断でNext.jsからPostgreSQLへ接続してはならない。原因と代替案を記録すること。

## 7.3 Keycloakプロバイダー設定

環境変数から以下を取得する。

```dotenv
KEYCLOAK_ISSUER=
KEYCLOAK_CLIENT_ID=
KEYCLOAK_CLIENT_SECRET=
BETTER_AUTH_URL=
BETTER_AUTH_SECRET=
```

要求スコープ：

```text
openid profile email
```

ログイン開始はNext.jsのログインボタンから実行する。

コールバック後は次へ遷移する。

```text
/top
```

## 7.4 Spring Bootへのトークン転送

Next.jsのServer ComponentまたはRoute HandlerでBetter Authセッションを検証する。

Keycloakのアクセストークンをサーバー側で取得し、Spring Bootへ次のヘッダーで転送する。

```http
Authorization: Bearer <access-token>
```

Client Componentまたはブラウザ側JavaScriptにアクセストークンを返却しない。

トークンの更新が必要になった場合は、Next.jsサーバー側だけで処理する。

---

# 8. Keycloak設計

## 8.1 RealmとClient

次を基本値とする。

```text
Realm: workflow
Client ID: workflow-web
Client type: Confidential
Protocol: OpenID Connect
Standard flow: Enabled
Direct access grants: Disabled
Implicit flow: Disabled
Self registration: Disabled
```

ローカルURLの例：

```text
Keycloak:
http://localhost:8180

Next.js:
http://localhost:3000
```

Redirect URIは、Better Authが実際に使用するコールバックURLに合わせて設定する。

Web Originsは必要な範囲だけを許可し、ワイルドカードは使用しない。

## 8.2 会社メールの判定

会社メール判定を、単なる文字列の末尾比較だけに依存させない。

プロトタイプでは次の多層構成とする。

### 第1層：Keycloakへのアカウント登録制限

* Self Registrationを無効化する
* Keycloak管理者または初期化スクリプトだけがユーザーを作成する
* メールアドレスを必須属性とする
* メールアドレスの重複を禁止する
* Usernameとしてメールアドレスを使用する
* 初期ユーザーの`emailVerified`をtrueにする

これにより、任意の外部ユーザーが自分でアカウントを作ることを防止する。

### 第2層：許可ドメイン検証

KeycloakのUser Profile設定で、email属性に許可ドメインの正規表現バリデーションを設定する。

開発環境の例：

```regex
^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@sdcj\.co\.jp$
```

許可ドメインは環境変数で定義する。

```dotenv
ALLOWED_EMAIL_DOMAIN=sdcj.co.jp
```

Realm JSONをテンプレート化し、初期化スクリプトで環境変数を反映してからKeycloakへ投入する。

### 第3層：Spring Bootでの再検証

Spring BootでもJWTのemailクレームを検証する。

次をすべて満たさない場合は、業務APIへのアクセスを拒否する。

* `email`クレームが存在する
* `email_verified`がtrue
* メールアドレスが許可ドメインと一致する
* `sub`が存在する
* issuerが想定するKeycloak Realmと一致する
* token audienceまたはauthorized partyが想定するClientと一致する

ドメイン不一致の場合は403を返すが、管理者通知は作成しない。

## 8.3 Entra IDへの移行を考慮した境界

アプリケーション内部では、Keycloak固有のロールや内部IDを業務権限に使用しない。

Spring Bootが参照する主要クレームを以下に限定する。

```text
iss
sub
email
email_verified
name
preferred_username
aud / azp
```

ユーザー識別には次の組み合わせを使用する。

```text
issuer + subject
```

これにより、将来KeycloakからEntra IDへ切り替える場合も、認証プロバイダー固有実装を局所化できる。

---

# 9. 初期ユーザー

サンプルのスペルは`exmaple`ではなく`example`に統一する。

少なくとも次の2アカウントを作成する。

## 9.1 管理者ユーザー

```text
Username: example.admin1@sdcj.co.jp
Email: example.admin1@sdcj.co.jp
Password: password
Display name: 開発管理者
Business role: ADMIN
Enabled: true
Email verified: true
```

## 9.2 一般ユーザー

```text
Username: example.user1@sdcj.co.jp
Email: example.user1@sdcj.co.jp
Password: password
Display name: 開発一般ユーザー
Business role: USER
Enabled: true
Email verified: true
```

Keycloak側には両方のユーザーを作成する。

PostgreSQLにも両方のユーザーを事前登録する。

業務ロールが追加された場合は、Playwrightによる権限確認に必要な範囲でロールごとのテストユーザーを追加する。

サンプルパスワードはローカル開発環境専用と明記する。

---

# 10. ロール管理

## 10.1 Keycloakの責務

Keycloakでは以下だけを管理する。

* ユーザーが認証可能か
* ユーザーが有効か
* メールアドレス
* 表示名
* 認証セッション

KeycloakのRealm RoleやClient Roleを、業務権限判定には使用しない。

## 10.2 PostgreSQLの責務

PostgreSQLでは以下を管理する。

* アプリケーション利用許可
* USER、ADMINなどの業務ロール
* 所属名
* アカウント有効・無効
* 未登録ユーザーからのアクセス要求

---

# 11. PostgreSQLの最小スキーマ

FlywayやLiquibaseは導入しない。

プロトタイプ用の初期化SQLをDockerまたは初期化スクリプトから実行する。

HibernateのDDL自動生成を使用する場合は、開発環境限定で以下のいずれかに固定する。

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create-drop
```

または：

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create
```

再起動のたびにデータが消える挙動をREADMEへ明記する。

## 11.1 app_users

最低限、次の情報を持つ。

```text
id
identity_provider
issuer
external_subject
email
display_name
department_name
role
enabled
created_at
updated_at
```

制約：

```text
UNIQUE (issuer, external_subject)
UNIQUE (email)
```

ロールはプロトタイプではEnumまたは文字列でよい。

```text
USER
ADMIN
```

## 11.2 access_requests

未登録の社内ユーザーからアクセスされた記録を保持する。

```text
id
issuer
external_subject
email
display_name
status
first_requested_at
last_requested_at
notification_sent_at
request_count
```

status：

```text
PENDING
APPROVED
REJECTED
```

プロトタイプでは承認画面を実装しなくてよい。`PENDING`の記録と管理者通知までを実装する。

同一ユーザーが何度アクセスしてもレコードを無制限に増やさず、`issuer + external_subject`で既存レコードを更新する。

---

# 12. Spring Boot API

## 12.1 GET /api/me

```http
GET /api/me
Authorization: Bearer <JWT>
```

### 成功時

```http
200 OK
```

例：

```json
{
  "id": "UUID",
  "externalSubject": "keycloak-sub",
  "email": "example.user1@sdcj.co.jp",
  "displayName": "開発一般ユーザー",
  "department": {
    "name": "開発部"
  },
  "roles": [
    "USER"
  ]
}
```

### JWTなし・無効

```http
401 Unauthorized
```

### 許可ドメイン外

```http
403 Forbidden
```

エラーコード例：

```json
{
  "code": "EMAIL_DOMAIN_NOT_ALLOWED",
  "message": "このアカウントでは利用できません。"
}
```

### Keycloakには存在するが業務DBに未登録

```http
403 Forbidden
```

エラーコード例：

```json
{
  "code": "APPLICATION_USER_NOT_REGISTERED",
  "message": "利用申請を管理者へ通知しました。"
}
```

この場合、Spring Bootは次を実行する。

1. `access_requests`へ冪等に記録する
2. 管理者ユーザーをDBから検索する
3. 管理者宛てに通知メールを送信する
4. 同じ利用者について短時間に通知を重複送信しない
5. 403レスポンスを返す

## 12.2 ヘルスチェック

Actuatorを導入する。

```http
GET /actuator/health
```

外部へ公開する情報は最小限とする。

---

# 13. 未登録ユーザー通知

## 13.1 通知対象

以下をすべて満たす場合だけ通知する。

* JWTが正常
* issuerが正常
* email_verifiedがtrue
* 許可ドメインと一致
* Keycloakで認証済み
* PostgreSQLの`app_users`に該当ユーザーが存在しない

ドメイン外ユーザーや不正JWTについては通知しない。

## 13.2 通知先

DB上で次を満たすユーザーを通知対象とする。

```text
role = ADMIN
enabled = true
```

## 13.3 開発環境

Spring BootからSMTPでMailpitへ送信する。

Mailpit UI：

```text
http://localhost:8025
```

メール件名例：

```text
[Workflow] 未登録ユーザーからアクセスがありました
```

本文には次を含める。

```text
表示名
メールアドレス
external subject
issuer
初回アクセス日時
最終アクセス日時
アクセス回数
```

メール送信失敗によって`/api/me`が500にならないようにする。

記録は保存し、警告ログを出力したうえで403を返す。

---

# 14. Next.js画面

## 14.1 ルートページ

```text
/
```

未認証の場合：

```text
/login
```

認証済みの場合：

```text
/top
```

へ遷移する。

## 14.2 ログイン画面

表示内容：

```text
ワークフローシステム

社内アカウントでログインしてください。

[ログイン]
```

Next.js上にメールアドレスやパスワードの入力欄は作成しない。

ログインボタンを押すとKeycloakへリダイレクトする。

## 14.3 Topページ

認証済みユーザーだけが表示できる。

表示内容：

```text
ワークフローシステム

ようこそ、開発一般ユーザーさん

メールアドレス: example.user1@sdcj.co.jp
所属: 開発部
権限: 一般ユーザー

[ログアウト]
```

ユーザー情報はSpring Bootの`GET /api/me`から取得する。

Better Authセッション内のプロフィールだけをTopページへ直接表示して完了としてはならない。

## 14.4 未登録ユーザー画面

Spring Bootが`APPLICATION_USER_NOT_REGISTERED`を返した場合、次を表示する。

```text
このアカウントはワークフローアプリに登録されていません。

管理者へ利用申請を通知しました。
登録完了後に再度ログインしてください。

[ログアウト]
```

## 14.5 ドメイン外・利用不可画面

```text
このアカウントではワークフローアプリを利用できません。

[ログアウト]
```

具体的な内部エラーやJWT情報を画面へ表示しない。

---

# 15. BFF実装

Next.jsにバックエンド呼び出し用の共通処理を作成する。

例：

```text
src/lib/backend-client.ts
```

責務：

1. Better Authセッションをサーバー側で検証する
2. Keycloakアクセストークンを取得する
3. Docker内部URLでSpring Bootへアクセスする
4. Authorizationヘッダーを付与する
5. タイムアウトを設定する
6. 401、403、5xxを画面用の結果へ変換する
7. アクセストークンや秘密情報をログ出力しない

ブラウザから利用する必要がある場合は、Next.js Route Handlerを作成する。

```text
/api/backend/me
```

ただし、TopページがServer Componentだけで実装できる場合は、Server Componentから共通BFF関数を直接呼び出してよい。

Spring BootのURLは、`NEXT_PUBLIC_`を付けないサーバー専用環境変数で管理する。

```dotenv
BACKEND_INTERNAL_URL=http://backend:8080
```

---

# 16. Docker Compose

最低限、次のサービスを定義する。

```text
frontend
backend
postgres
keycloak
mailpit
e2e
```

必要に応じてKeycloak初期化用の一時サービスを追加してよい。

## 16.1 ポート

```text
Next.js:    3000
Keycloak:   8180
Mailpit UI: 8025
Mailpit SMTP: Docker内部のみ
Spring Boot: ホストへ公開しない
PostgreSQL: ホストへ公開しない
```

## 16.2 Dockerネットワーク

少なくとも責務別にネットワークを分ける。

例：

```text
public-network
application-network
database-network
```

接続例：

```text
frontend:
  public-network
  application-network

backend:
  application-network
  database-network

postgres:
  database-network

keycloak:
  public-network
  database-networkまたは専用ネットワーク

mailpit:
  application-network
  public-network
```

Keycloakが業務DBと同じPostgreSQLインスタンスを使う場合も、DB名・ユーザー・権限を分離する。

推奨：

```text
workflow database:
Spring Boot専用

keycloak database:
Keycloak専用
```

Next.jsにはどちらのDB認証情報も渡さない。

---

# 17. 環境変数

`.env.example`を作成する。

最低限、以下を含める。

```dotenv
# General
COMPOSE_PROJECT_NAME=workflow

# Frontend
NEXT_PUBLIC_APP_NAME=ワークフローシステム
BETTER_AUTH_URL=http://localhost:3000
BETTER_AUTH_SECRET=replace-with-long-random-secret
BACKEND_INTERNAL_URL=http://backend:8080

# Keycloak OIDC
KEYCLOAK_EXTERNAL_URL=http://localhost:8180
KEYCLOAK_INTERNAL_URL=http://keycloak:8080
KEYCLOAK_REALM=workflow
KEYCLOAK_CLIENT_ID=workflow-web
KEYCLOAK_CLIENT_SECRET=replace-with-client-secret
KEYCLOAK_ISSUER=http://localhost:8180/realms/workflow
ALLOWED_EMAIL_DOMAIN=sdcj.co.jp

# Keycloak administrator
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=replace-with-admin-password

# Workflow database
WORKFLOW_DB_NAME=workflow
WORKFLOW_DB_USER=workflow
WORKFLOW_DB_PASSWORD=replace-with-db-password

# Keycloak database
KEYCLOAK_DB_NAME=keycloak
KEYCLOAK_DB_USER=keycloak
KEYCLOAK_DB_PASSWORD=replace-with-db-password

# Development users
DEV_ADMIN_EMAIL=example.admin1@sdcj.co.jp
DEV_ADMIN_PASSWORD=password
DEV_USER_EMAIL=example.user1@sdcj.co.jp
DEV_USER_PASSWORD=password

# Mail
MAIL_HOST=mailpit
MAIL_PORT=1025
MAIL_FROM=no-reply@workflow.local
MAILPIT_UI_PORT=8025
```

実際の`.env`をGit管理対象外にする。

`.gitignore`には最低限次を含める。

```gitignore
.env
.env.*
!.env.example
node_modules/
.next/
target/
playwright-report/
test-results/
```

---

# 18. Makefile

最低限、以下のターゲットを実装する。

```make
help
setup
init
build
up
down
restart
logs
ps
clean
reset
test
test-backend
test-frontend
test-e2e
verify
```

## 18.1 コマンドの役割

### make setup

* 必須コマンドの存在確認
* `.env`がなければ`.env.example`から作成
* 必要なディレクトリを作成
* 実行権限を付与
* シークレット未変更の場合は警告する

### make init

* Dockerイメージをビルド
* PostgreSQLを起動
* Keycloakを起動
* Realm、Client、ユーザーを作成
* Spring Bootを起動
* Next.jsを起動
* 初期ユーザーを業務DBへ投入
* ヘルスチェックを行う

冪等に実行できること。

### make up

```bash
docker compose up -d
```

だけでなく、サービス準備完了まで確認する。

### make reset

* コンテナ停止
* 開発用ボリューム削除
* DB再作成
* Realmおよびテストデータ再作成
* 全サービス再起動

確認プロンプトなしで破壊的処理を行う場合は、プロトタイプ用であることを明示する。

### make test

最低限、次を順番に実行する。

```text
backend test
frontend lint/typecheck
Playwright E2E
```

### make verify

次を確認する。

* コンテナ状態
* PostgreSQL readiness
* Keycloak readiness
* Spring Boot health
* Next.js HTTP応答
* Mailpit HTTP応答

---

# 19. 起動順序とヘルスチェック

## 19.1 PostgreSQL

```bash
pg_isready
```

## 19.2 Keycloak

Realm discovery endpointまたはhealth endpointを確認する。

例：

```text
/realms/workflow/.well-known/openid-configuration
```

## 19.3 Spring Boot

```text
/actuator/health
```

## 19.4 Next.js

```text
/login
```

のHTTP応答を確認する。

Docker Composeの`depends_on`だけに依存せず、healthcheckまたはwaitスクリプトを使用する。

無限待機を避け、タイムアウト時は原因が分かるログを表示する。

---

# 20. Playwright E2Eテスト

PlaywrightテストはDockerから実行可能にする。

```bash
make test-e2e
```

## 20.1 必須シナリオ

### シナリオ1：未ログイン

1. `/top`へ直接アクセス
2. `/login`へ遷移する
3. Topページの内容が表示されない

### シナリオ2：一般ユーザーのログイン

1. `/login`を開く
2. ログインボタンを押す
3. Keycloakログイン画面へ遷移する
4. 一般ユーザーでログインする
5. `/top`へ遷移する
6. 表示名、メール、所属、一般ユーザーロールを確認する

### シナリオ3：管理者ユーザーのログイン

1. 管理者ユーザーでログインする
2. `/top`へ遷移する
3. 管理者ロールが表示される

### シナリオ4：ログアウト

1. 認証済み状態からログアウトする
2. ログイン画面へ戻る
3. `/top`を再度開いても表示できない

### シナリオ5：Keycloakには存在するがDB未登録

テスト専用の未登録社内ユーザーをKeycloakに作成する。

例：

```text
example.pending1@sdcj.co.jp
```

業務DBには登録しない。

確認内容：

1. Keycloak認証は成功する
2. Topページは表示されない
3. 未登録ユーザー画面が表示される
4. `access_requests`に1件作成される
5. Mailpitに管理者宛て通知が届く

### シナリオ6：通知の重複抑止

同じ未登録ユーザーで短時間に複数回アクセスする。

確認内容：

* `access_requests`が重複作成されない
* request_countが更新される
* 通知メールが無制限に増えない

### シナリオ7：Spring Boot直接アクセス不可

ホスト側からSpring Bootのポートへ直接接続できないことを確認する。

### シナリオ8：JWTなし

Docker内部またはSpring Boot結合テストで、JWTなしの`/api/me`が401になることを確認する。

## 20.2 テストの安定性

* テスト間でセッションを分離する
* 固定のsleepを多用しない
* Playwrightのlocatorとexpectによる待機を使用する
* テスト失敗時にスクリーンショット、trace、動画を保存する
* CIでも実行可能なheadless構成にする

---

# 21. Spring Bootテスト

最低限、次を実装する。

* JWTなしで`/api/me`を呼ぶと401
* issuer不正で401
* emailクレームなしで403
* email_verified=falseで403
* 許可ドメイン外で403
* DB登録済みユーザーで200
* DB未登録ユーザーで403
* 無効ユーザーで403
* 未登録ユーザーの`access_requests`作成
* 同一ユーザーの要求が冪等に更新される
* 管理者通知処理が呼ばれる
* メール失敗でも403レスポンスを維持する

JWTテストにはSpring Securityのテスト支援機能を使用する。

---

# 22. セキュリティ要件

最低限、次を満たすこと。

* Resource Owner Password Credentials Flowを使用しない
* Direct Access Grantsを無効にする
* Implicit Flowを無効にする
* Keycloak Client Secretをブラウザへ渡さない
* Keycloakアクセストークンをブラウザへ返さない
* アクセストークンをlocalStorageへ保存しない
* Spring Boot APIをホスト公開しない
* PostgreSQLをホスト公開しない
* JWT署名を検証する
* issuerを検証する
* 有効期限を検証する
* audienceまたはazpを検証する
* email_verifiedを検証する
* ログへJWT、Client Secret、パスワードを出力しない
* エラー画面へ内部例外を表示しない
* CORSの`*`を使用しない
* Next.jsの保護ページでサーバー側セッション検証を行う
* 管理者ロールはJWTではなくDBから取得する

---

# 23. README

READMEには最低限、以下を記載する。

## 23.1 前提条件

```text
Windows 11
WSL2
Docker DesktopまたはWSL上のDocker Engine
Docker Compose V2
GNU Make
Git
VS Code
Codex VS Code拡張
```

## 23.2 初回起動

```bash
cd ~/projects/workflow
cp .env.example .env
make setup
make init
```

## 23.3 通常起動

```bash
make up
```

## 23.4 停止

```bash
make down
```

## 23.5 初期化

```bash
make reset
```

## 23.6 テスト

```bash
make test
```

## 23.7 URL

```text
Application:
http://localhost:3000

Keycloak:
http://localhost:8180

Mailpit:
http://localhost:8025
```

## 23.8 テストアカウント

```text
管理者:
example.admin1@sdcj.co.jp / password

一般ユーザー:
example.user1@sdcj.co.jp / password
```

ローカル開発専用アカウントであることを明記する。

## 23.9 トラブルシューティング

最低限、以下を記載する。

* ポート競合
* Keycloak起動待ち
* issuerの内部URL・外部URL差異
* Cookieが保存されない
* Better Authコールバックエラー
* JWT audience不一致
* PostgreSQL接続エラー
* Dockerボリューム再作成
* Playwrightブラウザ起動エラー
* Mailpitにメールが届かない場合

---

# 24. 実装フェーズ

## Phase 1：リポジトリ初期化

* モノレポのディレクトリ作成
* `.gitignore`
* `.env.example`
* Makefileの骨組み
* Docker Composeの骨組み
* READMEの骨組み

完了確認：

```bash
make help
docker compose config
```

## Phase 2：PostgreSQL・Mailpit

* PostgreSQL構築
* Spring Boot用DB作成
* Keycloak用DB作成
* Mailpit構築
* healthcheck追加

完了確認：

```bash
make verify
```

## Phase 3：Keycloak

* Realm作成
* OIDC Client作成
* Self Registration無効化
* email必須化
* 許可ドメイン検証
* 管理者・一般ユーザー作成
* 未登録テストユーザー作成
* Realm discovery確認

完了確認：

```text
http://localhost:8180/realms/workflow/.well-known/openid-configuration
```

## Phase 4：Spring Boot

* Spring Bootプロジェクト作成
* Resource Server設定
* JPA EntityとRepository
* `/api/me`
* JWTクレーム検証
* 未登録ユーザー記録
* Mailpit通知
* Actuator
* 単体・結合テスト

完了確認：

```bash
make test-backend
```

## Phase 5：Next.js・Better Auth

* Next.js App Router構築
* Better Authステートレス設定
* Generic OAuthによるKeycloak連携
* ログイン画面
* サーバー側セッション検証
* BFFクライアント
* Topページ
* 未登録画面
* ログアウト

完了確認：

* 一般ユーザーでログイン可能
* Topページに`/api/me`の結果を表示
* ブラウザへアクセストークンを露出しない

## Phase 6：Docker統合

* frontend Dockerfile
* backend Dockerfile
* 起動順序
* 内部ネットワーク
* makeコマンド
* 初期化スクリプト
* 全サービス統合

完了確認：

```bash
make reset
make verify
```

## Phase 7：Playwright

* Playwright環境
* 必須シナリオ実装
* Mailpit確認
* trace・スクリーンショット設定
* Makefile統合

完了確認：

```bash
make test-e2e
```

## Phase 8：最終検証

クリーンな状態から次を実行する。

```bash
make clean
make setup
make init
make test
```

すべて成功すること。

---

# 25. 受け入れ条件

以下をすべて満たした場合のみ完成とする。

1. `~/projects/workflow`配下にモノレポが作成されている
2. `make init`で環境を初期構築できる
3. `make up`で全サービスを起動できる
4. `http://localhost:3000`でログイン画面を表示できる
5. Next.jsにID・パスワード入力欄がない
6. ログインボタンからKeycloakへ遷移する
7. 一般ユーザーでログインできる
8. 管理者ユーザーでログインできる
9. 認証後にTopページへ遷移する
10. Topページの情報がSpring Bootの`GET /api/me`から取得される
11. Spring BootがKeycloak JWTを検証する
12. Spring BootがPostgreSQLから業務ユーザーを取得する
13. 業務ロールがPostgreSQLで管理されている
14. Next.jsがPostgreSQLへ接続していない
15. ブラウザがSpring Bootへ直接アクセスしていない
16. Spring Bootがホストへ公開されていない
17. PostgreSQLがホストへ公開されていない
18. 未ログイン状態ではTopページを表示できない
19. ログアウト後はTopページを表示できない
20. Keycloakには存在するがDB未登録の社内ユーザーは403になる
21. 未登録ユーザーのアクセスがDBへ記録される
22. 管理者宛て通知がMailpitへ送信される
23. 許可ドメイン外ユーザーは拒否される
24. KeycloakのSelf Registrationが無効である
25. `.env`がGit管理対象外である
26. `.env.example`が用意されている
27. `make test`で自動テストを実行できる
28. Playwrightの必須シナリオが成功する
29. READMEだけで別の開発者が環境を再現できる
30. クリーンなDocker環境から再構築できる

---

# 26. Codexの作業報告形式

各Phaseの終了時に、次の形式で報告すること。

```text
## Phase N 完了報告

### 実装内容
- ...

### 作成・変更ファイル
- ...

### 実行コマンド
- ...

### テスト結果
- ...

### 残課題
- ...

### 次のPhase
- ...
```

最終報告では次も記載すること。

* 採用した全バージョン
* 起動方法
* テストアカウント
* アクセスURL
* テスト結果
* 既知の制約
* Better AuthのステートレスOIDC構成の確認結果
* KeycloakからEntra IDへ移行する場合の変更箇所
* セキュリティ上、本番利用前に変更が必要な設定

---

# 27. Codexへの実行指示

このPlanを確認したら、最初にリポジトリとWSL環境を調査すること。

その後、Phase 1から順番に実装すること。

各Phaseで以下を必ず行う。

1. 実装
2. ビルド
3. テスト
4. エラー修正
5. 完了報告

途中のビルドエラーやテスト失敗を放置したまま次のPhaseへ進まないこと。

既存ファイルがある場合は、その内容を尊重し、破壊的変更を行う前に差分を確認すること。

プロトタイプの目的に不要な機能は追加しないこと。

受け入れ条件を満たすまで、実装、検証、修正を継続すること。
