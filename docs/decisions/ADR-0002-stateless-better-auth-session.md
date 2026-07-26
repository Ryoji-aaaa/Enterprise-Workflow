# ADR-0002: Better AuthのDBなしセッション

- Status: Accepted
- Date: 2026-07-26
- Related commits: `d05c8fd`, `4ae9cb4`
- Related files: `frontend/src/lib/auth.ts`, `frontend/src/app/api/auth/logout/route.ts`

## Context

Next.jsでOIDC loginを維持しつつ、Better Auth専用DBとNext.jsからPostgreSQLへの
接続を追加せず、access tokenをブラウザへ公開しない構成が必要である。

## Decision

Better Auth Generic OAuthをDB adapterなしで使用する。OAuth state、session、
provider accountは`BETTER_AUTH_SECRET`で署名・暗号化したHTTP-only Cookieへ保存する。
access tokenはNext.jsサーバーで取得し、Spring BootへのBFF通信だけに使用する。
Client Component、localStorage、sessionStorageへtokenを渡さない。

## Rationale

Better Auth専用DBの運用と資格情報を増やさず、Next.jsをdatabase networkから分離できる。
tokenをBrowserのJavaScript実行環境から隔離できる。

## Alternatives considered

- Better Auth database adapterと永続session tableを追加する
- Browser storageへtokenを保存してSpring Bootへ直接送る
- tokenをNext.jsの画面データへ埋め込む

## Consequences

サーバー側から個別Cookie sessionを直接失効できない。logout時のCookie削除、
Keycloak session失効、token有効期限を組み合わせる。`BETTER_AUTH_SECRET`の変更は
全Cookie sessionを無効にする。

## Temporary measures

なし。
