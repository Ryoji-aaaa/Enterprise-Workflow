# FlywayによるDBマイグレーション

## 目的

PostgreSQLのschema変更をFlywayのVersioned MigrationとしてGitで管理し、ローカル、テスト、
CI/CDで同じ順序の変更を適用する。HibernateによるDDL生成とSpring Boot SQL Initializationは
通常実行時に使用しない。

## 配置場所と適用順

Versioned MigrationはSpring Boot標準の次の場所へ配置する。

```text
backend/src/main/resources/db/migration/
```

現在存在するmigrationと最新versionはディレクトリを正本とし、文書へファイル一覧を複製しない。
追加前には次のように実ファイルをversion順で確認する。

```bash
find backend/src/main/resources/db/migration -maxdepth 1 -type f -name 'V*.sql' \
  -printf '%f\n' | sort -V
```

適用version、ファイル名、checksum、成功状態は各PostgreSQL環境の
`flyway_schema_history`を正本とする。Git上の最新versionだけから、環境への適用完了を判断しない。

期間重複排他制約は`btree_gist`を使用する。Azure Database for PostgreSQL
Flexible ServerではTerraformが`azure.extensions=BTREE_GIST`を設定し、Backendのdatabase
bootstrapが管理者権限で拡張を先に作成する。Flyway実行ユーザーへデータベース全体の
`CREATE`権限を追加しない。

## ファイル命名規則

```text
V{3桁連番}__{英小文字の説明}.sql
```

既存ファイルの最大versionから1つ進め、versionを重複させない。説明には英小文字とunderscoreを
使用する。文書や過去のPRに記載された番号をコピーせず、作業時点のmigrationディレクトリを確認する。

## 新しいマイグレーションの追加手順

1. migrationディレクトリを確認し、未使用の次versionを決める。
2. 対応するJPA modelと同じPRでVersioned Migrationを追加する。
3. 既存データの変換、applicationとの互換期間、失敗時の復旧方法を設計する。
4. 破壊的変更では、適用前条件、backup、切戻し不能点をPRと運用手順へ明記する。
5. Backend testでfresh migration、既存fixtureからのupgrade、schema validationを確認する。

```bash
make test SUITES=backend
```

`make test SUITES=backend`はH2上のservice/API testに加え、一時PostgreSQLで次を検証する。

- 空DBへの全migration適用とHibernate schema validation
- repositoryが保持するupgrade fixtureから最新schemaへの移行
- PostgreSQL固有のconstraint、事前検査、追記専用trigger
- migration後のrepository query
- 二回目起動時のFlywayと基盤seedの冪等性

どの旧versionをupgrade fixtureとして保持するか、期待するmigration件数、個別constraintの詳細は
`tools/test/checks/postgres-migrations.sh`を正本とし、この文書へ複製しない。

起動済みローカル環境の適用状況は次で確認する。

```bash
docker compose exec -T postgres \
  psql --username postgres --dbname workflow \
  --command 'SELECT * FROM flyway_schema_history ORDER BY installed_rank;'
```

## 適用済みファイルを変更してはいけない理由

Flywayは適用時のchecksumを保存し、起動時に現在のファイルと照合する。適用後のSQLや
ファイル名を変更すると検証に失敗する。誤りの修正や追加変更は、新しいversionのmigrationで
前進修正する。

`flyway repair`で安易に履歴を合わせてはならない。履歴修復が必要な場合は、対象環境、原因、
残存objectを確認し、専用の作業計画とレビューを用意する。

## 基盤seedと開発用seedの責務分離

SYSTEMユーザー、初期role・permissionなど全環境で同じ基盤データはFlywayで冪等に投入する。
開発用の組織・ユーザーはInitializerが管理し、`workflow.seed.enabled`で有効・無効を切り替える。
環境依存の開発データをmigrationへ含めない。

stagingの手動seed JobはFlywayを無効にして動作する。Jobが要求するschemaを含む全migrationを
通常Backend revisionで先に適用し、Backendのreadinessと`flyway_schema_history`を確認してから
実行する。詳細は[開発・staging用seedデータ](development-seed-data.md)を参照する。

## ローカル環境での確認方法

Flyway導入前のvolumeにはtableが存在しても履歴がないため、そのまま移行しない。開発データを
削除してよいことを確認した場合だけ、ローカル環境を再作成する。

```bash
make reset
make verify
make restart
make verify
```

最初の検証ではrepository内の全migrationが1回ずつ成功していること、再起動後も履歴行とseedが
重複しないことを確認する。`make reset`は共有環境へ使用しない。

## マイグレーション失敗時の確認方法

```bash
docker compose logs --tail=200 backend
docker compose exec -T postgres \
  psql --username postgres --dbname workflow \
  --command 'SELECT * FROM flyway_schema_history ORDER BY installed_rank;'
```

SQL error、version重複、欠落ファイル、checksum不一致を確認する。開発環境を空から再現できる場合は
原因を修正したうえで再作成できるが、共有環境のDBや必要なデータをリセットしてはならない。

## 破壊的変更とAzure運用

`DROP`、列型変更、`NOT NULL`追加など既存データや旧applicationとの互換性に影響する変更は、
expand-contract、backup、applicationの適用順、切戻し方法を含む個別計画としてレビューする。
共有環境の適用状態は文書へ固定せず、deploy時に`flyway_schema_history`と対象imageのmigrationを
照合する。

legacy user列のcontract migrationを適用済みの環境では、GitHub Environmentの
`CONTRACT_LEGACY_USER_COLUMNS`を`true`から戻さず、旧列へ依存するimageへrollbackしない。
未適用環境の段階切替、失敗時対応、通常deployの確認方法は
[Azure DB migration運用](../operations/database-migration.md)を参照する。
