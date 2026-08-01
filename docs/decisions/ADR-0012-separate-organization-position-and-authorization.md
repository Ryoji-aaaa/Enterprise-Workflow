# ADR-0012: 組織・役職・業務権限を分離する

- Status: Accepted
- Date: 2026-07-31
- Related files: `backend/src/main/resources/db/migration/V003__create_organization_management_schema.sql`,
  `backend/src/main/resources/db/migration/V004__create_authorization_management_schema.sql`,
  `docs/backend/organization-management.md`, `docs/backend/authorization.md`

## Context

従来の`app_users.department_name`と`business_role`は、単一の部署名と`USER` / `ADMIN`だけを
表せた。兼務、階層、役職、直属上司、適用期間、組織限定権限を扱えず、将来の承認経路で
「所属・役職」と「アプリケーション操作権限」を区別できない。

Keycloak Roleや組織属性を使えば一部を表現できるが、業務DBのトランザクション・履歴・
組織マスタと一貫して管理できず、IdPへ業務認可を結合してしまう。

## Decision

次の3概念を分離してPostgreSQLで管理する。

1. `organizations`と`organization_units`で法人と組織階層を管理する。
2. `positions`と`user_organization_assignments`で役職、主所属、兼務、上司、適用期間を管理する。
3. `roles`、`permissions`、`role_permissions`、`user_role_assignments`で操作権限を管理する。

役職のrankやapproval levelから操作権限を暗黙に導出しない。組織へ所属しただけで業務ロールを
自動付与せず、必要なロール割当を明示する。ロール割当は任意の組織単位スコープと有効期間を
持ち、DBの権限コードを認可の正本とする。Keycloak Roleは業務認可に使わない。

組織スコープは全体、または指定した組織単位への明示的な割当とする。階層の子孫への権限継承は
自動で行わない。必要になった場合は、継承規則、性能、組織再編時の意味を別途決定する。

## Rationale

人事上の所属・職位とシステム上の権限を独立して変更でき、最小権限と職務分掌を表現できる。
期間付きの兼務や権限付与を時点指定で解決でき、後続の承認経路は同じ組織基盤を参照できる。

## Alternatives considered

- `app_users`の部署名と単一ロールを拡張し続ける
- positionをアプリケーションroleとして兼用する
- Keycloak GroupとRoleを組織・業務権限の正本にする
- 組織階層から子孫への権限継承を常に暗黙適用する

## Consequences

認可時に有効なユーザー、ロール割当、ロール、権限の結合が必要になるため、Repository queryと
indexを整備する。認可に用いる基準時刻を揃え、終了時刻を含まない期間として評価する。

組織階層の循環、異なる法人への親参照、無効マスタへの割当、主所属やロール割当の期間重複を
防ぐ必要がある。単一行で表現できる規則はDB制約、階層や状態をまたぐ規則はトランザクション内の
サービス検証で保証する。

既存`USER`は`APPLICATION_USER`、`ADMIN`は`SYSTEM_ADMIN`へ移行する。既存部署名は
組織単位と主所属へ移し、参照がなくなってから旧列を削除する。

将来の承認経路では、役職・上司・approval levelを候補解決に使えるが、実際の承認者と
組織snapshotは申請実行側に固定し、現在の組織変更で過去の証跡を書き換えない。

## Temporary measures

承認経路定義、承認グループ、申請・承認実行テーブルは今回の決定対象外とする。
