# ADR-0006: FlywayによるDBマイグレーション

- Status: Accepted
- Date: 2026-07-27
- Related files: `backend/pom.xml`, `backend/src/main/resources/db/migration/`,
  `scripts/verify.sh`

## Context

これまではSpring Boot SQL Initializationが`schema.sql`を毎起動時に実行していた。
プロトタイプの拡張とCI/CD導入に備え、既存データを維持しながらDB変更を順番に適用し、
各環境の適用状態を検証できる仕組みが必要になった。

Hibernateの自動DDL生成は通常実行時に無効化しており、今後もアプリケーション起動時の
暗黙的なschema変更には使用しない。

## Decision

PostgreSQLのschema管理にFlywayを採用し、`db/migration`内のVersioned Migrationを使う。
初回のV001で現行の`app_users`と`access_requests`を作成し、
以降の変更は新しいversionとして追加する。

適用済みmigrationの内容とファイル名は変更しない。Hibernateの`ddl-auto: none`を維持し、
Spring Boot SQL Initializationは使用しない。開発用ユーザーは
`DevelopmentUserInitializer`が引き続き管理し、migrationへ混在させない。

初回導入では既存DBをbaselineせず、破棄可能な開発用volumeを再作成してV001から適用する。

## Rationale

DB変更履歴と適用順をGitでレビューでき、Flywayの履歴テーブルとchecksumによって
環境ごとの適用状態と適用済みファイルの変更を検出できる。ローカル、テスト、
将来のCI/CDで同じmigrationを利用できる。

## Alternatives considered

- Spring Boot SQL Initializationを継続する
- Hibernate `ddl-auto`でschemaを自動変更する
- Liquibaseを採用する
- DB変更を手動手順だけで運用する

## Consequences

schema変更にはversion命名、適用順、既存データとの互換性を考慮する必要がある。
適用済みSQLを直接修正できないため、誤りは新しいmigrationで前進修正する。
破壊的変更にはデータ移行とロールバックを含む明示的なレビューが必要になる。

H2を使う既存API結合テストは高速なJPA・業務機能検証として残し、Flywayは無効化する。
実際のPostgreSQLへのmigration適用と再起動時の冪等性はCompose環境で検証する。

## Temporary measures

既存DBを保持したbaseline移行、本番向け移行、undo migrationは今回扱わない。
共有環境または本番環境へ導入する前に、別のPlanとADRで移行方式を決定する。
