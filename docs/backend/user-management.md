# ユーザー管理

## 目的

認証基盤の識別子と業務ユーザーを分離し、IdPを変更しても変わらないUUIDを
ワークフロー内の利用者参照に使う。利用可否はbooleanではなく状態と有効期間で管理し、
状態変更を現在値の上書きだけで失わないようにする。

## 責務とテーブル

| テーブル | 責務 |
| --- | --- |
| `app_users` | IdPに依存しないユーザー本人、現在のアカウント状態、有効期間 |
| `user_external_identities` | `issuer + external_subject`と業務ユーザーの対応 |
| `user_account_status_histories` | アカウント状態変更の追記専用履歴 |
| `access_requests` | 業務DBへ未登録の認証済み利用者によるアクセス要求 |

`app_users.id`はUUIDであり、Keycloakの`sub`、email、社員コードのいずれも主キーにしない。
emailはログイン候補との照合に使えるが、外部ID連携後の本人識別の正本ではない。

### `app_users`

主な列は次のとおり。

```text
id
employee_code
email
display_name
account_status
account_status_reason
valid_from
valid_until
last_login_at
created_by / created_at
updated_by / updated_at
version
```

emailは小文字へ正規化し、大文字・小文字を区別せず必須かつ一意とする。社員コードは任意だが値がある場合は一意とする。
`valid_until`を設定する場合は`valid_from`より後でなければならない。
`version`はJPAの楽観ロックに使う。

アカウント状態は次のいずれかである。

| 状態 | 意味 |
| --- | --- |
| `PRE_REGISTERED` | 事前登録済みで、外部IDはまだ連携されていない |
| `ACTIVE` | 有効期間内であれば業務APIを利用できる |
| `SUSPENDED` | 一時停止中 |
| `DISABLED` | 管理上の理由で無効 |
| `RETIRED` | 退職または契約終了。通常の再有効化対象にしない |

### `user_external_identities`

外部IDは、業務ユーザーとは別の行として次を保持する。

```text
id
user_id
identity_provider
issuer
external_subject
external_email
linked_at
unlinked_at
created_by / created_at
updated_by / updated_at
version
```

`issuer + external_subject`は全体で一意、`user_id + issuer`も一意とする。
`external_email`は連携時の外部属性を確認するための値であり、権限の正本ではない。
解除日時を持つ場合は連携日時以後でなければならない。
連携解除した行は一意キーを保持する。解除済みの同じ外部IDで再ログインしても自動再連携や
未登録アクセス申請へ落とさず、`EXTERNAL_IDENTITY_UNLINKED`で拒否して監査する。再連携を
許可する場合は、本人確認を伴う管理者操作を別途実装する。

### `user_account_status_histories`

状態変更ごとに変更前後、理由、適用日時、変更主体、発生源、request IDを記録する。
発生源は`ADMIN_UI`、`SYSTEM`、`IDENTITY_PROVIDER`、`BATCH`、`MIGRATION`のいずれかとする。
履歴行は訂正のためにも更新・削除せず、必要なら新しい変更として追記する。
現在値を即時更新するAPIには未来の`effective_at`を指定できない。スケジューラを持たない
現段階では`FUTURE_ACCOUNT_STATUS_CHANGE_UNSUPPORTED`として拒否し、過去または現在時刻の
管理変更だけを記録する。

## 有効期間

ユーザーが現在利用可能である条件は、状態が`ACTIVE`であり、基準時刻を`t`として
次を満たすことである。

```text
valid_from <= t AND (valid_until IS NULL OR t < valid_until)
```

終了時刻は含まない。判定処理は`Instant.now()`を各Repository内で個別に呼ばず、
サービスが同じ基準時刻を渡す。これにより認証、所属、ロールの境界時刻を一貫して判定できる。

## 認証との関係

Spring SecurityによるJWTの署名、issuer、有効期限と業務クレームの検証条件は維持する。
JWT検証後のユーザー解決は次の順序で行う。

```text
JWT issuer + subject
  -> user_external_identities
  -> app_users
  -> account_statusと有効期間
```

有効な対応がない場合に限り、検証済みJWTのemailと
`PRE_REGISTERED`ユーザーを照合する。V001から`enabled = true`で移行した外部ID未連携の
`ACTIVE`ユーザーも後方互換のため同じメール連携対象とする。候補が一意で利用可能期間内なら、同一トランザクションで
外部IDを追加し、ユーザーを`ACTIVE`へ変更し、状態変更履歴と監査ログを追記する。
`external_subject`がない事前登録ユーザーには、移行時点で外部ID行を作らない。
段階移行中に旧revisionが先に外部IDを連携し、正規化済み外部IDと`PRE_REGISTERED`ユーザーが
一時的に共存した場合も、既存外部IDの解決経路で同じ有効化・履歴・監査処理を実行する。

`SUSPENDED`、`DISABLED`、`RETIRED`または有効期間外のユーザーは、IdPで認証済みでも
業務APIを利用できない。未登録利用者については、既存の`access_requests`の冪等記録と
Mailpit通知を維持する。

## 権限判定との関係

ユーザー解決後の業務認可はKeycloak RoleではなくDBのロール・権限を使う。
ユーザーの現在の状態と有効期間を確認した後に、有効な`user_role_assignments`を解決する。
詳細は[業務認可](authorization.md)を参照する。

## 更新、履歴、監査

アカウント状態変更は、次を1つのトランザクションで実施する。

1. 現在状態と許可された遷移を検証する。
2. `app_users`の現在値を更新する。
3. `user_account_status_histories`へ追記する。
4. `audit_logs`へ管理操作を追記する。

履歴または監査ログの保存に失敗した場合は、現在値の変更もロールバックする。
作成者・更新者はサービス層が認証済みの`app_users.id`を明示して設定し、未認証の
システム処理と初期データではSYSTEMユーザーを使う。

SYSTEMユーザーは固定UUID `00000000-0000-0000-0000-000000000001`を持つログイン不可の
`DISABLED`ユーザーであり、外部IDを持たない。固定値はアプリケーション内の定数へ集約する。

監査ログへtoken、Cookie、Authorizationヘッダー、password、client secretを保存しない。
状態変更の`reasonCode`と`reasonText`に一般的なcredential形式が含まれる場合は、現在値、状態履歴、
監査ログへ保存する前に`[REDACTED]`へ統一する。
詳細は[監査ログ](audit-logging.md)を参照する。

## 既存データの移行

既存の`app_users`は失わず、次の対応で新構造へ移す。

```text
enabled = true   -> account_status = ACTIVE
enabled = false  -> account_status = DISABLED
identity_provider / issuer / external_subject -> user_external_identities
email -> user_external_identities.external_email（外部IDがある場合）
```

旧`department_name`は組織所属へ、旧`business_role`はロール割当へ移す。
移行とアプリケーション切替が完了して参照がなくなった後にだけ旧列を削除する。
V001で許容されていた大文字・小文字だけが異なるemailは自動統合しない。V002の事前検査で
対象emailを示して停止するため、同一人物か別人かを運用者が確認してから名称変更または統合を
行い、Flywayを再実行する。

## APIと実装境界

管理画面の全面実装は対象外だが、次の管理APIを基盤の動作確認と後続画面のために提供する。

| Method | Path | 必要な権限 |
| --- | --- | --- |
| `GET` | `/api/admin/users` | `USER_READ` |
| `GET` | `/api/admin/users/{userId}` | `USER_READ` |
| `PATCH` | `/api/admin/users/{userId}/status` | `USER_STATUS_CHANGE` |

一覧のページング、request/response DTO、エラーコードは実際のControllerと結合テストを正本と
する。状態変更APIは上記サービスを迂回してEntityを直接更新してはならない。

## 承認経路との関係

後続の承認経路と申請実行データは`app_users.id`を参照する。IdP変更、email変更、
所属変更があっても、申請者・承認者の業務ユーザーIDは維持する。申請時点の表示名や
組織情報を証跡として固定する必要がある場合は、実行テーブル側にsnapshotを持たせる。
