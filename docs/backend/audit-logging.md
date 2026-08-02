# 監査ログ

## 目的

ユーザー、組織、所属、役職、ロール、権限に対する管理操作を横断的に追跡する。
業務データの現在値とは別に、誰が、いつ、何を、どの結果で実行したかを追記専用で保存する。

監査ログはアプリケーションのデバッグログとは異なる。秘密情報を収集する場所ではなく、
認可の代替やイベント再実行キューとしても使用しない。

## テーブル構成

`audit_logs`は次の情報を保持する。

| 分類 | 列 |
| --- | --- |
| 識別・時刻 | `id`, `occurred_at` |
| 主体 | `actor_user_id`, `actor_type`, `actor_display_name` |
| 操作対象 | `action_type`, `target_type`, `target_id` |
| 追跡 | `request_id`, `correlation_id` |
| 接続情報 | `source_ip`, `user_agent` |
| 変更内容 | `before_data`, `after_data`, `reason` |
| 結果 | `result` |

主体種別は`USER`、`SYSTEM`、`BATCH`、`IDENTITY_PROVIDER`、結果は`SUCCESS`、
`FAILURE`、`DENIED`のいずれかである。`actor_user_id`は削除されない業務ユーザーを参照できる
場合に設定し、外部主体など参照できない場合はnullableとする。
`actor_display_name`は後の表示名変更に影響されない最小限のsnapshotである。

`target_id`を文字列にしているのはUUID以外の管理対象も記録するためであり、入力されたURLや
任意のrequest body全体をそのまま保存するためではない。

## 記録対象

少なくとも次の管理処理を監査対象とする。該当する機能を実装するときは、業務更新と同時に
監査イベントを追加する。

- ユーザー登録と外部ID連携
- アカウント状態変更
- 組織の作成、更新、無効化
- 所属と役職の変更
- ロールの付与、剥奪、期間・スコープ変更
- ロールと権限の対応変更
- 管理APIの認可拒否
- 監査ログの参照と出力

参照系を無制限に記録してログを自己増殖させない。監査ログ検索は1リクエストにつき1つの
参照イベントとし、参照イベント自身を検索結果へ含める場合も再帰的な記録は行わない。

## 追記専用規則

`audit_logs`、`user_account_status_histories`、`user_role_change_histories`は追記専用である。
通常のアプリケーションコードからUPDATEまたはDELETEしない。監査ログは可変Entityとして
公開せず、INSERTだけを提供する専用Repositoryまたは`AuditLogService`を経由する。
PostgreSQLのtriggerも、この3テーブルに対するUPDATE、DELETE、TRUNCATEを拒否する。

誤ったイベントを後から上書きせず、必要なら訂正イベントを新しいIDと時刻で追記する。
保持期限による削除、法令対応、partition運用が必要になった場合は、通常APIとは分離した
権限・承認・記録を持つ運用手順を別途決定する。

## トランザクション境界

成功した管理操作では、対象データの変更、専用の変更履歴、`SUCCESS`監査ログを同一
トランザクションで保存する。監査ログ保存に失敗した場合、管理操作もcommitしない。

`DENIED`や、業務トランザクション自体が失敗したことを表す`FAILURE`は、ロールバック対象の
トランザクションへ入れると消える。このため、入力検証前後で安全に最小情報を組み立て、
失敗・拒否記録用の独立した保存境界を使う。監査ログ保存失敗によって元の拒否を成功扱いに
変えず、運用ログとメトリクスで検知する。

認証・認可済みの`/api/admin/**`で処理が失敗した場合は、HTTP methodやrequest body、例外文を
保存せず、`MANAGEMENT_OPERATION_FAILED`、endpoint path、安全なエラーコードだけを
`FAILURE`として独立トランザクションへ記録する。MVCの型変換など個別handlerより前後で確定する
4xxには`HTTP_<status>`をfallbackとして使う。401/403はこのfallbackから除外し、権限不足は
従来どおり`DENIED`として区別する。同一request内のhandler/filter記録は1件へdeduplicateする。

## 監査主体

認証済みリクエストでは、JWTの`sub`を直接保存主体にせず、解決済みの`app_users.id`を使う。
サービスはrequest単位の`CurrentUserProvider`相当から主体を取得し、ControllerごとにJWTを
再解析しない。外部IDから業務ユーザーを解決できた後に状態・期間で拒否した場合も、認可済み
principalとして再利用せず、監査主体だけに解決済み業務ユーザーを保持する。解決失敗もrequest内で
cacheし、管理filterとmethod認可による外部ID照会、未登録通知、拒否監査を重複させない。

初期データ、migration、未認証のシステム処理では固定SYSTEMユーザーを使用する。
外部IdP起点のイベントは`actor_type = IDENTITY_PROVIDER`、バッチは`BATCH`とし、該当する
業務ユーザーがない場合に架空のユーザーIDを作らない。

`request_id`は1回の業務要求に含まれる状態履歴、ロール履歴、監査ログを関連付けるUUID、
`correlation_id`はBFFや外部システムをまたぐ追跡値として扱う。外部から受け取った値は長さを
制限し、制御文字と一般的なcredential形式を除去してから保存する。

`source_ip`は信頼するproxyから渡された情報だけを採用し、任意の`X-Forwarded-For`を
無条件に信頼しない。user agentは上限長で切り詰め、制御文字を除外する。

## before/afterデータ

`before_data`と`after_data`はJSONBである。変更理由の説明に必要なフィールドだけを
allowlistで構成し、JPA EntityやHTTP requestを汎用serializerで丸ごと保存しない。

次の情報は保存禁止である。

```text
access token / ID token / refresh token
session Cookie
password
client secret
Authorization header
不要な個人情報
```

外部subject、email、IPアドレス、user agentも個人情報になり得る。目的を満たす最小限にし、
画面表示時のアクセス制御とログ出力時のmaskingを行う。例外messageやstack traceを
`reason`へそのままコピーしない。allowlistで組み立てたデータに対する多層防御として、
ネストした文字列値、`reason`、`correlation_id`、user agentに一般的なcredential形式が
含まれる場合は`[REDACTED]`へ置換する。
管理APIの状態変更理由とロール変更理由にも同じ防御をサービス入口で適用し、現在値、追記専用
変更履歴、監査ログのいずれにもcredential原文を残さない。

## 検索と出力

`GET /api/admin/audit-logs`は`AUDIT_LOG_READ`を要求し、ページングを必須とする。
検索条件として`actorUserId`、`actionType`、`targetType`、`targetId`、`from`、`to`、`result`を
受け付ける。安定した順序として`occurred_at`と`id`を使用し、件数上限を設ける。

検索・出力自体も監査対象とする。利用可能なHTTP APIは実際のControllerと結合テストを
正本とする。

## 認証・認可との関係

監査記録は認証・認可判定の結果を説明する証跡であり、監査ログの存在を使ってアクセスを
許可しない。Keycloak Roleではなく[業務認可](authorization.md)で参照権限を判定する。
ユーザーと外部IDの関係は[ユーザー管理](user-management.md)を参照する。

## 承認経路との関係

経費申請・承認では、各Stepの判断、comment、差戻しを経費承認実行テーブルへ保持し、作成、
更新、申請、再申請、取下げ、Step承認、差戻し、最終承認の横断イベントを`audit_logs`へも
追記する。業務更新と成功監査は同じtransactionに含める。承認履歴を管理監査ログだけで
代用せず、必要に応じてrequest/correlation IDで関連付ける。Candidate外・自己承認・所有者外の
参照または更新は、拒否された業務transactionから独立した`DENIED`監査として保持する。
