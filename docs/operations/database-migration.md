# Azure DB migration運用

業務DBは空の`workflow` databaseとして作成し、Spring Boot起動時に既存のFlyway V001から
適用する。Hibernate `ddl-auto=none`、通常時のSQL initialization無効、適用済みmigration
不変、seed非混在を維持する。Azureでは`WORKFLOW_SEED_ENABLED=false`とする。

新revisionのreadinessが成功しなければtrafficを正常と扱わず、Container Apps logsと
`flyway_schema_history`を確認する。

```sql
SELECT installed_rank, version, description, script, checksum,
       installed_on, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

stagingではV001からV008がchecksum付きで1回ずつ成功していることを確認済みである。V008は
`employment_type`、`PROJECT`組織種別、`ORGANIZATION_CHART_READ`、
`ORGANIZATION_CHART_VIEWER`、`USER_INFORMATION_MANAGER`、`WORKFLOW_APPROVER`を追加する。
手動DB seed JobはFlywayを無効化するため、V008確認より先に実行しない。

経費申請PoCを含むrevisionではV009を適用し、経費申請5テーブル、申請番号sequence、3つの
業務Permissionと既存Roleへの割当を追加する。V009は既存データを削除・変更せず、通常Backendの
Flyway起動で適用する。適用後は`expense_applications`から`expense_approval_candidates`までの
5テーブルと`expense_application_number_seq`、`EXPENSE_APPLICATION_CREATE`、
`EXPENSE_APPLICATION_READ_OWN`、`EXPENSE_APPLICATION_APPROVE`を確認する。手動seed Jobは不要である。

経費証憑を含むrevisionでは続けてV010を適用する。V010は既存行を更新せず、
`expense_application_attachments`、申請への外部key、storage object一意制約、サイズ・SHA-256・
論理削除整合性制約、有効添付の一覧indexを追加する。適用後は次を確認する。

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name = 'expense_application_attachments';

SELECT version, description, success
FROM flyway_schema_history
WHERE version IN ('009', '010')
ORDER BY installed_rank;
```

V010はmetadata schemaだけを作成し、Blob containerや既存ファイルを操作しない。先にTerraformで
Storage Account、非公開container、Backend専用Managed Identity/RBACをapplyし、Backend revisionへ
Blob endpoint、container名、client IDを設定する。Blob設定不足でBackendが起動できない場合も
`flyway repair`やDB手動変更を行わず、Terraformとrevision設定を修正する。

通知基盤を含むrevisionではV011からV013を順に適用する。V011は`notification_outbox`と
`access_requests.notification_queued_at`、V012は既存送付時刻のqueue時刻へのbackfill、V013は
`MAIL_NOTIFICATION_READ`と`SYSTEM_ADMIN`への割当を追加する。Azureではdelivery modeが
`disabled`のためOutbox行と履歴APIは作動しないが、schemaとPermissionは全環境で同じmigrationを
適用する。適用後はFlyway V013成功、Permissionが1件、SYSTEM_ADMIN mappingが1件であることを確認する。

V006からV007への切替では、GitHub Environmentの
`CONTRACT_LEGACY_USER_COLUMNS=false`によりTerraformが通常Backendへ
`SPRING_FLYWAY_TARGET=006`を渡す。V006の移行内容、旧revision停止、write drainを確認してから
`CONTRACT_LEGACY_USER_COLUMNS=true`へ変更して新しいdeployを開始する。`true`ではtargetを
渡さないためV007以降が適用される。V007を適用した環境は旧列へ戻せないため、フラグを以後
`true`に保つ。stagingはV007とV008を適用済みである。

失敗したmigrationを書き換えず、原因を直した新versionを追加する。V007がデータ照合や
lock競合で失敗した場合も`flyway repair`を使わず、transaction rollbackと履歴を確認して原因を
解消する。破壊的変更はExpand and Contractで行い、productionではbackup/restore、互換期間、
切り戻し不能点を含む個別計画を承認する。詳細は[Flyway仕様](../backend/flyway.md)を参照する。
