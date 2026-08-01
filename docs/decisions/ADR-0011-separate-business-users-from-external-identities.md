# ADR-0011: 業務ユーザーと外部認証IDを分離する

- Status: Accepted
- Date: 2026-07-31
- Related files: `backend/src/main/resources/db/migration/V002__expand_user_management_schema.sql`,
  `backend/src/main/resources/db/migration/V006__seed_and_migrate_user_organization_authorization_data.sql`,
  `docs/backend/user-management.md`

## Context

従来の`app_users`は、業務ユーザーの表示情報・利用可否・業務ロールと、Keycloak由来の
`identity_provider`、`issuer`、`external_subject`を同じ行に保持していた。
この構造ではIdPの変更や再連携が業務ユーザーの主キーと履歴へ波及しやすく、外部IDがまだ
ない事前登録ユーザーも同じ形で表現しにくい。

一方、既存の認証フローは検証済みJWTの`issuer + subject`を優先し、初回だけemailで
事前登録ユーザーと紐付ける。この安全性と、未登録アクセス要求・通知の動作を維持する必要が
ある。

## Decision

`app_users.id`をIdP非依存の業務ユーザーIDとし、外部IDを
`user_external_identities`へ分離する。

- 外部主体は`issuer + external_subject`で一意に識別する。
- 1ユーザーにつき同じissuerの外部IDは1件に制限する。
- emailは`app_users`の業務連絡先として一意に保つ。外部emailは連携時の値として別に保持し、
  外部ID連携後の識別キーにはしない。
- `PRE_REGISTERED`ユーザーには外部IDを必須にしない。
- 初回連携では、JWT検証後にemailで`PRE_REGISTERED`ユーザーを照合し、外部ID追加、
  `ACTIVE`への状態変更、状態変更履歴、監査ログを同一トランザクションで保存する。
- `SUSPENDED`、`DISABLED`、`RETIRED`または有効期間外のユーザーは、外部認証に成功しても
  業務APIでは拒否する。
- 連携解除済みの一意キーは予約したままにし、同じ外部IDの自動再連携を拒否して監査する。
  再連携には本人確認を含む明示的な管理者フローを要求する。

既存のJWT署名・issuer・client・email検証、Next.js BFF境界、Keycloak Roleを業務認可に
使わない方針は変更しない。

## Rationale

業務ユーザーを安定したUUIDで参照でき、IdPやsubjectのライフサイクルをワークフローの
申請者・承認者参照から切り離せる。事前登録、連携、解除を明示的に表現でき、外部IDの
一意性もDBで保証できる。

## Alternatives considered

- 従来どおり`app_users`へissuerとsubjectを直接保持する
- Keycloakの`sub`を業務テーブルの主キーにする
- emailだけで毎回ユーザーを識別する
- Keycloakユーザーと業務ユーザーを常時同期し、Keycloakを業務ユーザーの正本にする

## Consequences

認証後のユーザー解決に外部IDテーブルとの結合が増える。初回連携の競合は一意制約と
トランザクションで処理し、email一致だけで既存の連携を付け替えてはならない。

既存データはexpand-and-contractで移行する。外部subjectがある行だけ外部IDを作り、
`enabled`をアカウント状態へ変換する。アプリケーション切替と検証後に旧外部ID列を削除する。

詳細な状態、有効期間、移行規則は[ユーザー管理](../backend/user-management.md)を正本とする。

## Temporary measures

V006のapplication-switch中だけ、新旧`app_users`形の双方向同期とrollback時のfail-closed投影を
database triggerで行う。V007のreconciliation完了後に旧列、marker、trigger、helper functionを
まとめて削除する。恒久的なdual-write機構にはしない。
