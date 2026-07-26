# PostgreSQL仕様

## 採用バージョン

- PostgreSQL 18.4

## データベース分離

単一のPostgreSQLコンテナ内に、用途の異なる2つのデータベースを作成する。

| データベース | ロール | 用途 |
| --- | --- | --- |
| `workflow` | `workflow` | Spring Bootの業務データ |
| `keycloak` | `keycloak` | Keycloakの内部データ |

各ロールは自分のデータベースだけへ接続できる。相手側データベースの
`CONNECT`権限は明示的に取り消す。Next.jsにはいずれのDB資格情報も渡さない。

## 初期化

`postgres/init/01-create-databases.sh`を
`/docker-entrypoint-initdb.d`へ読み取り専用でマウントする。
公式PostgreSQLイメージの初回初期化時だけ、DB、ロール、所有権、接続権限を設定する。

スクリプトは識別子を検証し、SQL値は`psql`変数として渡す。資格情報はログへ
出力しない。

## 永続化と再実行

データディレクトリは`postgres-data`へ永続化する。既存ボリュームがある場合、
初期化スクリプトは再実行されない。

`scripts/verify.sh postgres`では次を確認する。

- PostgreSQLが接続受付状態である
- 2つのデータベースが存在する
- 2つのログインロールが存在する
- 各ロールの接続権限が相互に分離されている

PostgreSQLのホストポートは公開しない。
