# Keycloak / OpenID Connect仕様

## 採用バージョンとURL

- Keycloak 26.7.0
- 外部URL: `http://localhost:8180`
- 内部URL: `http://keycloak:8080`
- Realm: `workflow`

## RealmとClient

Realmの初回作成にはKeycloakのstartup importを使用する。
環境変数を`keycloak/realm-template.json`へ反映し、生成したRealm JSONだけを
`/opt/keycloak/data/import`へマウントする。

`workflow-web` Clientは次の設定を持つ。

- confidential client
- Authorization Code Flow有効
- PKCE S256必須
- Implicit Flow無効
- Direct Access Grants無効

Self RegistrationはRealm設定で無効化する。KeycloakのRealm RoleやClient Roleは
業務権限に使用しない。

## 開発ユーザー

| 種別 | ユーザー名 | Keycloak | Workflow DB |
| --- | --- | --- | --- |
| 管理者 | `example.admin1@sdcj.co.jp` | 登録済み | DBで`SYSTEM_ADMIN`を割当 |
| 一般 | `example.user1@sdcj.co.jp` | 登録済み | DBで`APPLICATION_USER`を割当 |
| 未登録テスト | `example.pending1@sdcj.co.jp` | 登録済み | 登録しない |

全ユーザーは有効かつemail verifiedである。サンプルパスワードはローカル開発専用である。
組織図用69ユーザーと雇用区分境界テスト用2ユーザーは
`keycloak/development-users.tsv`を正本としてAdmin REST APIで冪等作成・更新する。
共通パスワードは`DEV_SEED_PASSWORD`（既定`password`）であり、
この初期化コンテナはローカルdevelopmentでのみ使用する。

外部PoC確認用の4ユーザーは`keycloak/guest-users.tsv`で別管理し、
`guest00@example.com`から`guest03@example.com`までを冪等に同期する。passwordは通常ユーザーとは
独立した`GUEST_SEED_PASSWORD`を必須とし、`DEV_SEED_PASSWORD`へfallbackしない。実passwordは
Git、ログ、文書へ記録しない。Azure stagingへのpassword・allowlist・Guest seed配線はPhase 2で扱う。

## User Profile

Realm import対象ではないため、User Profile JSONをimportディレクトリへ配置しない。
Realm起動後、内部ネットワーク上のAdmin REST APIで現在値をGETし、次の順に最小更新する。

1. email属性を常時必須の`required={}`にする
2. email属性へ会社ドメインまたは外部メール完全一致allowlistの正規表現を設定する

正規表現は`ALLOWED_EMAIL_DOMAIN`のドットと`ALLOWED_EXTERNAL_EMAILS`の各文字列を正規表現用に
エスケープし、`jq --arg`でJSONへ設定する。外部許可は`example.com`全体ではなく4 Guestの完全一致である。

Keycloak 26.7.0では、GET結果に存在しない`unmanagedAttributePolicy`へ`DISABLED`を
追加してPUTするとHTTP 400になる。そのため、この項目は追加せず、既存値がある場合も
変更しない。

## Admin REST APIによる初期化と検証

`kcadm.sh`は使用しない。`admin-cli`のPassword Grantは管理初期化専用の
一時コンテナ内に限定し、アプリケーション認証には使用しない。

- `configure-keycloak.sh`: User ProfileのGETとPUT
- `check-keycloak.sh`: Realm、Client、ユーザー、User Profile、Discoveryのhuman/NDJSON検証

トークン、管理者パスワード、レスポンス中の資格情報はログへ出力しない。
検証処理は設定を変更しない。

## 検証項目

- Realmが存在しSelf Registrationが無効
- `workflow-web`が1件だけ存在し、要求したFlow設定を持つ
- 管理者、一般、未登録テストユーザーが存在
- 外部PoC Guest 4名が存在し、通常ユーザーと別のpassword sourceでログインできる
- emailが必須
- 会社ドメインと外部メール完全一致allowlistの正規表現が設定済み
- `unmanagedAttributePolicy`が未設定
- Discovery endpointがHTTP 200で、有効なissuerを返す
