# FlywayによるDBマイグレーション

## 目的

PostgreSQLのスキーマ変更をFlywayのVersioned MigrationとしてGitで管理し、
ローカル、テスト、将来のCI/CDで同じ順序の変更を適用する。
HibernateによるDDL生成とSpring Boot SQL Initializationは通常実行時に使用しない。

## ディレクトリ構成

マイグレーションはSpring Boot標準の次の場所へ配置する。

```text
backend/src/main/resources/db/migration/
└── V001__create_initial_schema.sql
```

初回マイグレーションは`app_users`と`access_requests`を作成する。
適用履歴、ファイル名、checksum、成功状態はPostgreSQLの
`flyway_schema_history`に記録される。

## ファイル命名規則

```text
V{3桁連番}__{英小文字の説明}.sql
```

例:

```text
V002__create_workflow_requests.sql
V003__add_approval_status.sql
```

番号は既存ファイルの最大値から1つ進める。versionは重複させず、説明には英小文字と
underscoreを使用する。

## 新しいマイグレーションの追加手順

1. 対応するJPAモデルと同じPRで、新しいVersioned Migrationを追加する。
2. 既存データを維持できる順序と後方互換性を確認する。
3. H2上の既存APIテストと、空のPostgreSQLに対する全migrationを検証する。

```bash
touch backend/src/main/resources/db/migration/V002__add_example_column.sql
make test-backend
make reset
make verify
```

適用状況はPostgreSQLコンテナ内で確認する。

```bash
docker compose exec -T postgres \
  psql --username postgres --dbname workflow \
  --command 'SELECT * FROM flyway_schema_history ORDER BY installed_rank;'
```

## 適用済みファイルを変更してはいけない理由

Flywayは適用時のchecksumを保存し、起動時に現在のファイルと照合する。
適用後のSQLやファイル名を変更すると検証に失敗し、backendは起動しない。
誤りの修正や追加変更は、次のversionを持つ新しいmigrationで前進適用する。

`flyway repair`で安易に履歴を合わせてはならない。履歴修復が必要な場合は、
対象環境、原因、残存オブジェクトを確認し、専用の作業Planとレビューを用意する。

## 開発用seedとの責務分離

Flywayはテーブル、制約などのスキーマだけを管理する。
開発用管理者・一般ユーザーは従来どおり`DevelopmentUserInitializer`が投入し、
`workflow.seed.enabled`で有効・無効を切り替える。環境依存の開発データを
migrationへ含めない。

## ローカル環境での確認方法

Flyway導入前のvolumeにはテーブルが存在しても履歴がないため、そのまま移行しない。
開発データを削除してよいことを確認してから次を実行する。

```bash
make reset
make verify
make restart
make verify
```

最初の検証ではV001が1回だけ成功していること、再起動後も履歴行とseedが
重複しないことを確認する。

## マイグレーション失敗時の確認方法

```bash
docker compose logs --tail=200 backend
docker compose exec -T postgres \
  psql --username postgres --dbname workflow \
  --command 'SELECT * FROM flyway_schema_history ORDER BY installed_rank;'
```

SQLエラー、version重複、欠落したファイル、checksum不一致を確認する。
開発環境を空から再現できる場合は、原因を修正したうえで`make reset`を実行する。
共有環境のDBや必要なデータを、確認せずリセットしてはならない。

## 破壊的変更のルール

`DROP`、列型変更、NOT NULL追加など既存データを失う可能性がある変更は、
データ移行、アプリケーションとの適用順、ロールバック方法を明記してレビューする。
破壊的なDDLと対応するJPA変更は同じPRで扱う。

## 本番移行時の注意事項

今回の導入は、破棄可能なローカル開発DBを空にしてV001から適用することを前提とする。
既存データを保持する共有環境・本番環境にはそのまま適用しない。
対象DBの現行schemaと運用要件を調査し、baseline方式、バックアップ、停止時間、
段階的な互換性維持を別Planで決定する。
