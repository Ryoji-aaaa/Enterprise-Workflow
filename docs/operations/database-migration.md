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

初回はV001がchecksum付きで1回だけ成功していることを確認する。失敗したmigrationを
書き換えず、原因を直した新versionを追加する。破壊的変更はExpand and Contractで行い、
productionではbackup/restore、互換期間、切り戻し不能点を含む個別計画を承認する。
