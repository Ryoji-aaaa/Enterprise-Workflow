# ADR-0013: 監査ログと変更履歴を追記専用にする

- Status: Accepted
- Date: 2026-07-31
- Related files: `backend/src/main/resources/db/migration/V002__expand_user_management_schema.sql`,
  `backend/src/main/resources/db/migration/V004__create_authorization_management_schema.sql`,
  `backend/src/main/resources/db/migration/V005__create_audit_log_schema.sql`,
  `docs/backend/audit-logging.md`

## Context

ユーザー状態やロール割当の現在値だけでは、誰がどの理由で変更したか、認可拒否を含む管理操作が
どの結果になったかを後から説明できない。通常のアプリケーションログは保持期間や形式が異なり、
変更前後の業務データと安定して関連付ける用途には向かない。

一方、HTTP requestやEntityを無差別に保存するとtoken、Cookie、秘密情報、不要な個人情報を
永続化する危険がある。失敗・拒否の記録をロールバックされる業務トランザクションへ入れると、
残すべき証跡自体が失われる。

## Decision

横断的な管理操作を`audit_logs`へ保存し、状態固有の情報を
`user_account_status_histories`と`user_role_change_histories`へ保存する。この3テーブルは
追記専用とし、通常のアプリケーション処理からUPDATEまたはDELETEしない。

- 成功した変更は、現在値、専用変更履歴、`SUCCESS`監査ログを同一トランザクションで保存する。
- 認可拒否と、ロールバックされる操作失敗は、元の業務トランザクションとは独立した保存境界で
  `DENIED`または`FAILURE`として記録する。
- 監査主体には解決済みの`app_users.id`を使い、システム処理ではログイン不可のSYSTEMユーザーを
  使う。外部主体やバッチはactor typeで区別する。
- before/after JSONは操作ごとのallowlistで最小限に構成する。request、Entity、例外を
  汎用serializerで丸ごと保存しない。
- token、session Cookie、password、client secret、Authorizationヘッダー、不要な個人情報を
  保存しない。

訂正が必要な場合も既存行を書き換えず、元イベントを識別できる新しいイベントを追記する。

## Rationale

現在値の整合性と変更証跡を同じcommit境界で保証でき、通常の更新APIから過去の記録を
改変しにくくなる。横断ログとドメイン固有履歴を分けることで、検索に必要な共通情報と、
状態遷移の意味をそれぞれ明確に保てる。

allowlist方式は、監査の目的に不要な秘密情報や個人情報を収集するリスクを抑えられる。

## Alternatives considered

- 現在値の`updated_by / updated_at`だけを保持する
- 通常のtext application logだけを監査証跡にする
- 更新可能な汎用audit EntityをCRUD Repositoryで管理する
- すべてのHTTP request/responseをそのままJSONへ保存する
- database triggerだけで変更内容を自動収集する

## Consequences

状態変更・ロール変更・主要管理操作は、履歴・監査を必ず伴うサービスを経由する。
直接のEntity更新や汎用CRUD endpointを許可しない。失敗・拒否用の独立トランザクションは、
元の結果を変えずに保存失敗を検知できるようにする。

監査ログ参照には`AUDIT_LOG_READ`を要求し、ページングと検索上限を設ける。参照・出力自体も
監査するが、再帰的なイベント生成を避ける。

保持期間、partition、archive、削除権限、SIEM連携は運用・法令要件が確定した時点で
別途決定する。追記専用は無期限保持を意味しないが、通常の業務APIに削除能力を与えない。

## Temporary measures

なし。
