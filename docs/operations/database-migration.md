# Azure DB migration運用

## 運用原則

Azureの業務DB schemaは、Backend imageに含まれるFlyway Versioned MigrationをBackend起動時に
適用する。Hibernate `ddl-auto=none`、通常時のSQL initialization無効、適用済みmigration不変、
開発seed非混在を維持する。Azureでは`WORKFLOW_SEED_ENABLED=false`とする。

migrationファイルと順序は`backend/src/main/resources/db/migration/`、環境へ実際に適用された状態は
各DBの`flyway_schema_history`を正本とする。stagingやproductionの適用済みversionを文書へ固定しない。

## デプロイ前確認

1. 対象image SHAに含まれるmigrationと、対象DBの`flyway_schema_history`を比較する。
2. 未適用migrationのDDL、data migration、lock、所要時間、旧revisionとの互換性を確認する。
3. `DROP`、列型変更、必須列追加、既存行の書換えなどがあれば、backup、write drain、切戻し不能点を
   含む環境別計画を承認する。
4. migrationが必要とするAzure resource、extension、Managed Identity、application設定を先に確認する。
5. migrationを実行するBackend replicaと、起動中の旧revisionからのwriteを制御する。

新しい破壊的migrationに固有の確認事項を、この恒久runbookへversion順に追記し続けない。
その変更を導入するPR、release記録、または専用runbookに適用前条件と検証SQLを置き、完了後は
現行仕様と再利用可能な運用規則だけを残す。

## 適用と確認

新revisionのreadinessが成功しなければtrafficを正常と扱わない。Container AppsのConsole logで
Flywayの結果を確認し、DBでは次を実行する。

```sql
SELECT installed_rank, version, description, script, checksum,
       installed_on, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

次を確認する。

- 対象imageに含まれる全migrationがversion順に1回ずつ成功している
- checksum error、failed row、version欠落がない
- Hibernate schema validationとBackend readinessが成功している
- migration固有のdata/constraint検証が成功している
- active revisionとtrafficが意図した状態である

手動seed JobはFlywayを実行しない。通常Backendによるmigrationとreadinessが完了した後だけ、
[開発・staging用seedデータ](../backend/development-seed-data.md)の手順で実行する。

## legacy user列のcontract guard

`CONTRACT_LEGACY_USER_COLUMNS`は、legacy user列を削除するcontract migrationの適用境界を制御する。

- `flyway_schema_history`にcontract migrationが未適用の環境では、`false`により一つ前の互換schemaで
  Backendを起動し、移行件数、外部ID、主所属、role、認証・認可、監査を確認する。
- contract適用前に旧revisionを停止し、管理更新と初回loginをwrite drainする。Flywayを実行する
  Backendは1 replicaに限定する。
- 同じ検証済みimageで`true`へ変更した新しいdeployを開始し、reconciliationと旧列削除を確認する。
- contract migrationを適用済みの環境は`true`を維持する。`false`へ戻さず、旧列へ依存するimageへ
  rollbackしない。

この判定は環境名や文書上の「適用済み」記載ではなく、対象DBの`flyway_schema_history`で行う。

## 失敗時とrollback

失敗したmigrationのSQLを書き換えたり、`flyway repair`で履歴だけを合わせたりしない。Console log、
transaction rollback、`flyway_schema_history`、lock、対象データ、旧revisionの停止状態を確認し、原因を
解消して再deployする。transaction外のobjectや部分成功があり得る場合は、専用の復旧計画を作成する。

Flyway成功後にapplicationだけを以前のrevisionへ戻しても、DB schemaは戻らない。旧imageが現在の
schemaと互換であることを事前に確認し、互換でなければ承認済みbackup restoreまたは新しいmigrationで
前進修正する。共有環境をリセットせず、適用済みmigrationを手作業で削除・再作成しない。

一般的なrevision rollbackは[Container Apps Revisionの切り戻し](rollback.md)、migrationの作成規則と
ローカル検証は[Flyway仕様](../backend/flyway.md)を参照する。
