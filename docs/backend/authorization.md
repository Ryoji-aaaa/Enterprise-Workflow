# 業務認可

## 目的

本人認証を担当するKeycloakと、業務操作を許可するアプリケーション認可を分離する。
業務ロール、権限、付与期間、組織スコープをPostgreSQLで管理し、JWTやKeycloak Roleを
業務権限の正本にしない。

## テーブル構成

| テーブル | 責務 |
| --- | --- |
| `roles` | 業務ロールの定義 |
| `permissions` | resourceとactionに対する操作権限 |
| `role_permissions` | ロールと権限の多対多対応 |
| `user_role_assignments` | ユーザーへの期間・組織スコープ付きロール付与 |
| `user_role_change_histories` | 付与、剥奪、期間・スコープ変更の追記専用履歴 |

### ロール

ロール種別は`SYSTEM`、`BUSINESS`、`WORKFLOW`である。初期ロールは次のとおり。

```text
APPLICATION_USER
SYSTEM_ADMIN
USER_ADMIN
ORGANIZATION_ADMIN
WORKFLOW_DESIGNER
AUDITOR
```

`system_role`は組込みロールを識別するためのフラグであり、権限判定を迂回する特権フラグでは
ない。無効なロールは新規付与できず、認可判定にも使わない。

### 権限

権限は一意な`permission_code`と、`resource_type`、`action_type`で操作を表す。
初期権限は次のとおり。

```text
USER_READ                 USER_CREATE
USER_UPDATE               USER_STATUS_CHANGE
ROLE_READ                 ROLE_ASSIGN
ROLE_REVOKE               ORGANIZATION_READ
ORGANIZATION_MANAGE       AUDIT_LOG_READ
WORKFLOW_SUBMIT           WORKFLOW_APPROVE
WORKFLOW_ROUTE_MANAGE
```

初期対応では`SYSTEM_ADMIN`に全権限、`APPLICATION_USER`に`WORKFLOW_SUBMIT`、
`AUDITOR`に`AUDIT_LOG_READ`を付与する。その他の対応は明示的なseedまたは管理操作で追加する。

### ロール割当

`user_role_assignments`はユーザー、ロール、任意の`organization_unit_id`、開始・終了時刻、
理由、付与者と共通監査列を持つ。

- `organization_unit_id IS NULL`: 全体スコープ
- `organization_unit_id IS NOT NULL`: 指定された組織単位のスコープ

組織スコープは明示された組織単位との一致で評価する。子孫組織への自動継承は、別の要件と
テストを導入するまで行わない。同一ユーザー・ロール・スコープで有効期間が重なる割当は
登録できない。組織スコープ付き割当の期間は、組織単位とその所属組織の有効期間内に収める。
期間延長またはスコープ変更時には、変更時点でも組織単位と所属組織が有効であることを再確認する。

## 有効期間

ロール割当は終了時刻を含まない半開区間として扱う。基準時刻`t`で有効な条件は次である。

```text
valid_from <= t AND (valid_until IS NULL OR t < valid_until)
```

`valid_until`を指定する場合は`valid_from`より後でなければならない。認可処理では、
ユーザーが`ACTIVE`かつ有効期間内、ロールが有効、割当が有効期間内であることをすべて確認する。
同じリクエスト内では共通の基準時刻を使う。

## 権限判定

認証済みユーザー`userId`が権限`permissionCode`を持つかを、概念上次の結合で判定する。

```text
app_users
  -> user_role_assignments
  -> roles
  -> role_permissions
  -> permissions.permission_code
```

全体スコープの判定と、組織単位を指定した判定を提供する。

```java
boolean hasPermission(UUID userId, String permissionCode);

boolean hasPermission(
        UUID userId,
        String permissionCode,
        UUID organizationUnitId);
```

組織を指定しない判定では全体スコープの割当だけを使用する。組織を指定した判定では
全体スコープ、または同じ組織単位の割当を使用できる。単にロール名が
`SYSTEM_ADMIN`であることをControllerが個別判定せず、権限コードに集約する。

Spring Securityとの接続には既存構成に合う`AuthorizationManager`、
`PermissionEvaluator`または`@PreAuthorize`用Beanを利用できるが、DB判定を迂回する
独自のKeycloak authority mappingは追加しない。

## 認証との関係

JWTは署名済みの外部IDを伝えるだけである。認証処理が
`user_external_identities`から`app_users.id`を解決し、状態と有効期間を検証した後に、
認可サービスがDBの現在の割当を評価する。token内のrealm/client Roleが同名でも、
業務権限として採用しない。

## ロール変更履歴と監査

`user_role_change_histories`には`ASSIGNED`、`REVOKED`、`EXTENDED`、`SHORTENED`、
`SCOPE_CHANGED`を記録する。対象ユーザー、ロール、組織スコープ、変更前後の終了時刻、
理由、変更主体、発生源、request IDを保持し、更新・削除しない。

ロール付与・剥奪・期間変更では、現在の割当変更、変更履歴、`audit_logs`を同一
トランザクションで保存する。いずれかの保存に失敗した場合は全体をロールバックする。
認可拒否も監査対象だが、拒否レスポンスによってロールバックされない保存境界を使用する。
変更理由に一般的なcredential形式が含まれる場合は、割当現在値、変更履歴、監査ログへ保存する
前に`[REDACTED]`へ統一する。

監査データにはtoken、Cookie、Authorizationヘッダー、client secretを含めない。
詳細は[監査ログ](audit-logging.md)を参照する。

## 既存ロールの移行

既存の単一`business_role`は次の割当へ移す。

```text
USER  -> APPLICATION_USER
ADMIN -> SYSTEM_ADMIN
```

V006の旧binary向け互換投影は、組織scopeを表現できる新modelから権限を拡大しないよう、
`organization_unit_id IS NULL`の全体scope割当だけを旧`ADMIN`/`USER`へ投影する。
組織scope付き割当だけを持つユーザーは旧binary上で`enabled=false`となる。

移行後の認可は`app_users.business_role`を参照しない。旧列はV007で削除し、旧`UserRole`も
認証・通知・テストから除去した。未登録アクセス通知の宛先は、旧`ADMIN`列ではなく必要な
権限を持つ有効なユーザーから解決する。

## APIと実装境界

ユーザーのロール割当には次の管理APIを提供する。

| Method | Path | 必要な権限 |
| --- | --- | --- |
| `POST` | `/api/admin/users/{userId}/roles` | `ROLE_ASSIGN` |
| `DELETE` | `/api/admin/users/{userId}/roles/{assignmentId}` | `ROLE_REVOKE` |

その他の管理APIも操作ごとに`USER_READ`、`USER_STATUS_CHANGE`、`ORGANIZATION_READ`、
`AUDIT_LOG_READ`などを要求する。request/response DTOとエラーコードは実際のControllerと
結合テストを正本とし、ここにないendpointの存在を前提にしない。

## 承認経路との関係

`WORKFLOW_SUBMIT`、`WORKFLOW_APPROVE`、`WORKFLOW_ROUTE_MANAGE`はAPI操作可否を表す。
誰が特定申請の承認者になるかは、組織・役職・経路定義・申請時snapshotから別途決定する。
ロールを持つことと、個別ワークフローの承認者に選ばれることを同一視しない。
