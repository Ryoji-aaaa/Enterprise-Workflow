# ADR-0005: アプリケーションのネットワーク境界

- Status: Accepted
- Date: 2026-07-26
- Related commits: `3dbffcf`, `d05c8fd`, `9284c3e`
- Related files: `docker-compose.yml`, `scripts/verify.sh`, `tests/e2e/specs/workflow.spec.ts`

## Context

ブラウザ向けサービス、業務API、DBの到達範囲を最小化し、BFFとDBアクセスの責務境界を
Compose定義だけでなく実コンテナでも保証する必要がある。

## Decision

許可する主要経路を次に限定する。

- Browser → frontend
- Browser → Keycloak
- frontend → backend
- backend → PostgreSQL
- backend → Mailpit
- Keycloak → PostgreSQL

backendとPostgreSQLはホストポートを公開しない。frontendはPostgreSQL networkへ参加せず、
DB環境変数を持たない。`application-network`と`database-network`はinternal networkとする。

## Rationale

Browserから業務API・DBへの直接接続と、frontend侵害時のDB直接到達を防ぎ、
Spring Bootを唯一の業務DBアクセス境界として維持できる。

## Alternatives considered

- 全サービスを単一networkへ接続する
- backendまたはPostgreSQLを開発用にホスト公開する
- frontendをdatabase networkへ接続する

## Consequences

ホストからbackendやPostgreSQLを直接デバッグする運用は採用しない。必要な確認は
`docker compose exec`、healthcheck、BFF、専用検証スクリプトを使う。
E2E runnerのhost network利用はOAuthのlocalhost整合用に限定し、
アプリケーションサービスの境界を変更しない。

## Temporary measures

なし。
