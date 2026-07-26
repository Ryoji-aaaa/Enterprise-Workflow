# Make / Shell開発運用仕様

## ホスト依存

`scripts/install-host-dependencies.sh`はUbuntu上で次を導入・確認する。

- Docker Engine
- Docker Composeプラグイン
- GNU Make
- Git
- GitHub CLI
- jq
- curl
- gettext（`envsubst`）

Node.js、npm、Java、Mavenなど、コンテナに閉じ込められる依存はホストへ導入しない。

## 設定ファイル

`.env.example`をGit管理し、実値を設定する`.env`は管理対象外とする。
`replace-with-`で始まる値はローカル起動前に変更する。

Realm JSONとUser Profile取得結果は`keycloak/generated`へ生成し、このディレクトリも
Git管理対象外とする。

## 主要Makeターゲット

| ターゲット | 役割 |
| --- | --- |
| `make setup` | ホスト依存確認と`.env`作成 |
| `make phase1-check` | リポジトリ骨格とCompose構文の確認 |
| `make phase2-check` | PostgreSQLとMailpitの起動・検証 |
| `make phase3-check` | Keycloakの生成、起動、設定、検証 |
| `make test-backend` | Docker testステージでSpring BootのJUnitを実行 |
| `make phase4-check` | backendのテスト、起動、Actuator・依存サービス検証 |
| `make verify` | 起動中サービスのhealth確認 |
| `make down` | コンテナ停止。ボリュームは保持 |
| `make reset` | ボリュームを含む開発環境の再作成 |

## シェル実装規約

- 厳格モードとしてBashは`set -Eeuo pipefail`、POSIX shは`set -eu`を使用
- JSONの生成・更新・検証には`jq`を使用
- HTTPはステータスを明示的に検証し、エラー本文は秘密情報を除いて表示
- 固定の長時間sleepや無限再試行を使用しない
- Makefileへ複雑な複数行`jq`式を置かず、専用スクリプトへ分離

Phase 3の静的検証はCompose構文、全シェルの構文、生成JSONの構文へ限定し、
実際のマウントとネットワークは`keycloak-init`サービスの実行によって確認する。
