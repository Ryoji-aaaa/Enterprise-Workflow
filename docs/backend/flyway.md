# FlywayによるDBマイグレーション

## 目的

PostgreSQLのスキーマ変更をFlywayのVersioned MigrationとしてGitで管理し、
ローカル、テスト、将来のCI/CDで同じ順序の変更を適用する。
HibernateによるDDL生成とSpring Boot SQL Initializationは通常実行時に使用しない。

## ディレクトリ構成

マイグレーションはSpring Boot標準の次の場所へ配置する。

```text
backend/src/main/resources/db/migration/
├── V001__create_initial_schema.sql
├── V002__expand_user_management_schema.sql
├── V003__create_organization_management_schema.sql
├── V004__create_authorization_management_schema.sql
├── V005__create_audit_log_schema.sql
├── V006__seed_and_migrate_user_organization_authorization_data.sql
├── V007__contract_legacy_app_user_columns.sql
└── V008__add_employment_type_project_and_organization_chart_roles.sql
```

V001は従来の`app_users`と`access_requests`、V002からV005は新しい管理基盤、V006は
SYSTEM・マスタseedと既存データ移行、V007は切替後の旧列削除、V008は雇用区分、PROJECT、
組織図権限と関連ロールを扱う。
適用履歴、ファイル名、checksum、成功状態はPostgreSQLの
`flyway_schema_history`に記録される。

stagingではV001からV008までの適用成功を確認済みである。V007によって旧ユーザー列を
contract済みであり、GitHub Environment `staging`の
`CONTRACT_LEGACY_USER_COLUMNS`は以後`true`を維持する。通常Backendは最新migrationまでを
適用し、V008の`employment_type`、`PROJECT`、`ORGANIZATION_CHART_READ`と関連ロールを
利用できる状態を前提とする。

V003の期間重複排他制約は`btree_gist`を使用する。Azure Database for PostgreSQL
Flexible ServerではTerraformが`azure.extensions=BTREE_GIST`を設定し、backendの
database bootstrapが管理者権限で拡張を先に作成する。Flyway実行ユーザーへ
データベース全体の`CREATE`権限を追加しない。

## ファイル命名規則

```text
V{3桁連番}__{英小文字の説明}.sql
```

例:

```text
V009__create_workflow_requests.sql
V010__add_approval_status.sql
```

番号は既存ファイルの最大値から1つ進める。versionは重複させず、説明には英小文字と
underscoreを使用する。

## 新しいマイグレーションの追加手順

1. 対応するJPAモデルと同じPRで、新しいVersioned Migrationを追加する。
2. 既存データを維持できる順序と後方互換性を確認する。
3. H2上の既存APIテストと、空のPostgreSQLに対する全migrationを検証する。

```bash
touch backend/src/main/resources/db/migration/V008__add_example_column.sql
make test-backend
make reset
make verify
```

`make test-backend`はH2上のサービス/APIテストに加え、一時PostgreSQL 18コンテナで次を自動確認する。

- 空DBへのV001からV008とHibernate schema validation
- V001既存ユーザーからV008までの実データ移行
- email正規化の事前検査、排他制約、追記専用trigger
- 二回目起動時のFlyway・基盤seedの冪等性

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

## 基盤seedと開発用seedの責務分離

SYSTEMユーザー、初期ロール・権限、移行に必要な組織など、全環境で同じ基盤データは
Flywayで冪等に投入する。開発用管理者・一般ユーザーは`DevelopmentUserInitializer`が投入し、
`workflow.seed.enabled`で有効・無効を切り替える。環境依存の開発データを
migrationへ含めない。

stagingの手動seed Jobは`--spring.flyway.enabled=false`で動作し、migrationを実行しない。
schema更新は通常Backend revisionの起動で先に完了させる。DB seedは少なくともV008まで
成功し、`employment_type`など必要なschemaが存在することを確認してから実行する。
seed Jobの詳細は[開発・staging用seedデータ](development-seed-data.md)を参照する。

## ローカル環境での確認方法

Flyway導入前のvolumeにはテーブルが存在しても履歴がないため、そのまま移行しない。
開発データを削除してよいことを確認してから次を実行する。

```bash
make reset
make verify
make restart
make verify
```

最初の検証ではV001からV008が1回ずつ成功していること、再起動後も履歴行とseedが
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

V002からV007はV001の既存ユーザーを保持して新構造へ移行できる。ただし、共有環境と本番では
V006とV007を同じContainer Apps revision作成中に適用しない。Terraformの
`contract_legacy_user_columns`は既定で`false`であり、backendへ
`SPRING_FLYWAY_TARGET=006`を渡す。

1. DB backupを取得し、case-insensitive email重複がないことを確認する。
2. `CONTRACT_LEGACY_USER_COLUMNS=false`のまま新アプリをdeployする。
3. Flyway V006、ユーザー件数、外部ID、主所属、ロール、認証・認可、監査を確認する。
4. 新revisionだけがactiveで、旧revisionへtrafficがないことを確認する。V007適用中は管理更新と
   初回loginを停止するwrite drainを設け、Flywayを実行するbackendを1 instanceだけ起動する。
5. GitHub Environmentの`CONTRACT_LEGACY_USER_COLUMNS=true`へ変更し、同じ検証済みimageを
   再deployしてV007を適用する。
6. V007のユーザー単位reconciliation成功と旧列削除を確認し、フラグを以後`true`に保つ。
   `true`では`SPRING_FLYWAY_TARGET`を渡さないため、V008以降も通常どおりlatestまで適用される。

V007の失敗時は`flyway repair`で履歴を合わせない。V007はtransaction内で失敗を
rollbackするため、Console logと`flyway_schema_history`、旧revisionの停止、reconciliation
対象データを確認して原因を解消し、contract deployを再実行する。

V007は外部ID、主所属、旧ロールに欠落があればDDL実行前に失敗する。V006までなら旧アプリへ
戻せるが、V007後は旧列へ依存するimageへ戻さず、前進修正または承認済みbackup restoreを行う。
write drain前に旧revisionがV006後のユーザー行を作成した場合も、その行はreconciliation対象として
識別され、正規化した主所属・ロールがなければV007は失敗して旧列を保持する。欠落を補正してから
contract deployを再実行する。互換triggerにはapp_usersと子tableの双方向投影があるため、write drainを
省略するとlock競合またはdeadlockで起動が一度失敗し得る。その場合もV007はtransaction rollbackされる。
旧revisionを停止したことを再確認してから、contract deployを再試行する。

V006は`app_users`を排他して最終再同期した後、application-switch用の互換triggerを同一transaction
で設置する。新modelのユーザー・状態・外部ID・主所属・全体scopeのlegacy相当ロールは旧列へ安全側に
投影され、旧revisionで進行中だった初回loginのsubject更新は正規化tableへ同期される。旧binaryは組織
scopeを表現できないため、組織scope付きロールだけを持つユーザーは全体ADMIN/USERへ平坦化せず
`enabled=false`とする。明示的にunlinkした外部IDや全体scopeのlegacy相当ロールがないユーザーも旧
projectionを`enabled=false`にして、rollback時の権限復活を防ぐ。
これらの一時列・trigger・helper functionはreconciliation成功後にV007が削除する。V007も
source/target tableをtransaction lockしてから照合するため、確認済み状態と旧列削除の間へ管理writeが
割り込むことはない。
