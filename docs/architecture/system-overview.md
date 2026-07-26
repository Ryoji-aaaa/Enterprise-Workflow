# システム概要

## コンポーネント

| コンポーネント | 責務 |
| --- | --- |
| Browser | 画面表示、Keycloakでの対話的ログイン |
| Next.js | Better Authセッション、BFF、画面遷移 |
| Keycloak | OpenID Connect認証、トークン発行 |
| Spring Boot | JWT検証、業務ユーザー・利用申請API |
| PostgreSQL | 業務データとKeycloakデータの分離保存 |
| Mailpit | ローカル開発用の未登録ユーザー通知確認 |

```text
browser ──> frontend ──> backend ──> PostgreSQL
   │                       └───────> Mailpit
   └──────> Keycloak ─────────────> PostgreSQL
```

## 責務境界

- Next.jsは認証セッションとブラウザ向け応答を担当し、業務DBへ接続しない。
- Spring BootはResource ServerとしてJWTを検証し、業務DBへ接続する唯一の
  アプリケーションである。
- Keycloakは本人認証を担当するが、業務ロールは管理しない。
- 業務ロール、利用可否、未登録ユーザーの利用申請はPostgreSQLで管理する。
- Browserから業務APIへの通信はNext.js BFFを経由する。

実装詳細は[frontend仕様](../frontend/nextjs-better-auth.md)、
[backend仕様](../backend/spring-boot.md)、
[Compose仕様](../infrastructure/docker-compose.md)を参照してください。
