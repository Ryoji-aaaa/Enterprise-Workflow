# 組織・所属・役職管理

## 目的

法人、組織階層、役職、ユーザーの所属を正規化し、兼務、直属上司、適用期間を扱える
基礎データを提供する。組織上の役職とアプリケーションの操作権限は別概念として管理する。

## テーブル構成

| テーブル | 責務 |
| --- | --- |
| `organizations` | 法人・会社単位 |
| `organization_units` | 本部、部、課、チームなどの階層 |
| `positions` | 組織上の役職と承認レベル |
| `user_organization_assignments` | ユーザーの所属、兼務、役職、上司、適用期間 |

すべてのIDはUUIDである。マスタと割当は`created_by / created_at`、
`updated_by / updated_at`、楽観ロック用の`version`を持つ。

### `organizations`

`organization_code`を一意な業務コードとし、名称、`enabled`、有効開始日、任意の終了日を
保持する。終了日は開始日より前にできない。

### `organization_units`

各組織単位は1つの`organizations`に属し、同じ法人内の任意の親を参照する。
`organization_id + unit_code`を一意とする。種類は次のいずれかである。

```text
COMPANY / DIVISION / DEPARTMENT / SECTION / TEAM / OTHER
```

階層変更時は、DBのCHECK制約とtrigger、およびサービス層で次を検証する。

- 自分自身を親にしない。
- 自分の子孫を親にせず、循環を作らない。
- 異なる`organization_id`の組織単位を親にしない。

同一法人内の階層変更は`organizations`行の悲観ロックで直列化する。DB triggerも同じ行を
ロックしてから再帰検査するため、並行する`A -> B`と`B -> A`が古い階層を同時に読んで
循環を確定させることを防ぐ。

`display_order`は同じ親の配下を表示するための値であり、階層や認可を決める値ではない。

### `positions`

`position_code`を一意とし、名称、非負の`position_rank`と`approval_level`、
`enabled`を保持する。`approval_level`は将来の承認経路候補を絞るための基礎値であり、
それだけでAPI権限を付与しない。

### `user_organization_assignments`

1行が、1ユーザーの1組織単位における所属期間を表す。任意の役職と直属上司を持てる。
所属種別は次のいずれかである。

| 種別 | 用途 |
| --- | --- |
| `PRIMARY` | 主所属 |
| `CONCURRENT` | 兼務 |
| `TEMPORARY` | 期限付き所属 |
| `ACTING` | 代行・職務代理 |

`is_primary`は主所属検索に使用する。`is_primary = true`と`assignment_type = PRIMARY`を
同値とするDB制約を持ち、表現が食い違う割当を登録できない。

## 有効期間

組織、組織単位、所属は`DATE`で管理し、終了日を含む閉区間として判定する。
`Instant`から基準日へ変換する処理はUTCを用い、実行環境のローカルtimezoneに依存させない。
基準日を`d`とすると現在有効な行は次を満たす。

```text
valid_from <= d AND (valid_until IS NULL OR d <= valid_until)
```

新しい所属を作るときは、ユーザー、法人、組織単位、役職の状態と有効期間が割当期間を
満たすことを確認する。同一ユーザーについて、有効期間が重なる主所属は1件だけにする。
同一内容・同一期間の割当も登録できない。主所属の期間重複はPostgreSQLの排他制約、
その他の完全重複は式indexで防ぐ。主所属以外の期間の一部重複を禁止する業務規則が必要な
場合は、サービス検証または追加の排他制約として明示する。状態や階層をまたぐ規則はDB
triggerと同じトランザクション内のサービス検証で補完する。

直属上司には自分自身を指定できない。上司の所属関係を必須にするか、上司と部下の
組織単位を一致させるかは、承認経路要件と合わせて後続で決定する。

## 認証・権限との関係

組織割当は、認証で解決した`app_users.id`に結び付く。KeycloakのGroup、Role、部署属性を
組織の正本として使わない。

役職は人事上の概念であり、`positions.position_rank`や`approval_level`から
`permissions`を暗黙に生成しない。API操作権限は[業務認可](authorization.md)のロール割当で
明示する。組織スコープ付きロールは`organization_units.id`を参照するが、所属しているだけで
そのロールを得ることはない。

## 更新履歴と監査

組織・所属・役職の現在値はマスタまたは割当に保持し、作成・更新・無効化を
`audit_logs`へ追記する。所属・役職変更ではbefore/afterに必要最小限のID、種別、期間を
記録し、tokenや不要な個人情報を含めない。

同じ操作で複数行を変更する場合、業務データの変更と成功監査ログを同一トランザクションに
含める。詳細は[監査ログ](audit-logging.md)を参照する。
ユーザーや組織マスタが後から無効化されても、既存所属を安全側へ閉じる終了日短縮は許可し、
新規割当、対象変更、期間延長では現在状態と全期間の包含検証を必須とする。

## 既存部署の移行

初期法人`SDCJ`を作成し、既存`app_users.department_name`を可能な限り同名の
組織単位と所属へ移す。値がない、または正規化できないデータに対するfallbackとして
`Default Department`を使用する。移行は冪等に行い、ユーザーごとの主所属を重複させない。

## APIと実装境界

組織管理画面と更新APIは今回の対象外だが、`ORGANIZATION_READ`を要求する次の参照APIを
提供する。

```http
GET /api/admin/organizations
GET /api/admin/organization-units
```

request/response DTOとエラーコードは実際のControllerと結合テストを正本とする。
将来の更新APIは、階層循環、期間重複、無効マスタへの割当を検証するサービスを経由する。

## 承認経路との関係

後続の承認経路は、申請者の基準日時点における主所属、兼務、役職、直属上司、
`approval_level`を候補抽出に利用できる。ただし、経路定義・承認グループ・申請実行テーブルは
この基盤には含めない。過去の申請を現在の組織変更で書き換えないため、申請開始時に解決した
承認者と必要な組織snapshotはワークフロー実行側へ保存する。
