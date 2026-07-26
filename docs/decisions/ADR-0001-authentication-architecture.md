# ADR-0001: 認証アーキテクチャ

- Status: Accepted
- Date: 2026-07-26
- Related commits: `3dbffcf`, `d05c8fd`
- Related files: `frontend/src/lib/auth.ts`, `frontend/src/lib/backend-client.ts`, `backend/src/main/java/jp/co/sdcj/workflow/config/SecurityConfig.java`

## Context

ブラウザログイン、OIDC tokenの安全な取り扱い、業務ユーザーと業務権限のDB管理を
分離する必要がある。将来KeycloakをMicrosoft Entra IDへ変更しても、業務APIとDBの
責務を保てる境界が必要である。

## Decision

Next.jsをBFF、Spring BootをOAuth2 Resource Serverとする。BrowserはNext.jsから
認証を開始し、Next.jsサーバーだけがaccess tokenをSpring Bootへ送る。
Spring Bootだけが業務DBへ接続し、業務権限をDBから取得する。

IdP固有処理はNext.jsのOAuth provider設定・logoutとSpring Bootのissuer/JWKS・claim
検証へ閉じ込め、業務ユーザー識別は`issuer + subject`を使う。

## Rationale

tokenをブラウザJavaScriptから隔離でき、認証と業務認可を独立して変更できる。
Next.jsへDB資格情報を与えず、DBアクセスと業務規則をSpring Bootへ集約できる。

## Alternatives considered

- BrowserからSpring Bootへ直接Bearer tokenを送るSPA構成
- Next.jsから業務DBへ直接接続する構成
- Keycloak Roleを業務権限の正本にする構成

## Consequences

Browserの業務API通信は必ずBFFを経由する。IdP移行時はissuerとsubjectが変わるため、
監査可能な対応表とDB migrationが必要であり、emailだけで権限を自動移行しない。

## Temporary measures

なし。
