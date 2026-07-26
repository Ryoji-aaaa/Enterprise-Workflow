# ネットワーク境界

## 許可する通信

| 送信元 | 送信先 | 用途 |
| --- | --- | --- |
| Browser | Next.js | 画面、認証開始、BFF |
| Browser | Keycloak | ログイン、ログアウト |
| Browser | Mailpit | ローカルでの通知確認 |
| Next.js | Keycloak | token交換、userinfo |
| Next.js | Spring Boot | Bearer JWT付き業務API |
| Spring Boot | PostgreSQL | 業務データ |
| Spring Boot | Mailpit | 未登録ユーザー通知 |
| Keycloak | PostgreSQL | Realm・認証データ |

## 禁止する通信

- Spring BootとPostgreSQLのホストポート公開
- BrowserからSpring BootまたはPostgreSQLへの直接接続
- Next.jsからPostgreSQLへの接続
- Next.jsへのDB資格情報の設定
- PostgreSQLの用途別ロールによる相互データベース接続

`make verify`はCompose定義と実コンテナのネットワーク所属、公開ポート、
Next.jsからPostgreSQLへのDNS到達不能、DB資格情報の非設定を検証します。
PlaywrightはSpring Bootのホスト非公開とBFF経由アクセスを確認します。

ネットワークの実装は[Docker Compose仕様](../infrastructure/docker-compose.md)、
判断理由は[ADR-0005](../decisions/ADR-0005-application-network-boundaries.md)を
参照してください。
