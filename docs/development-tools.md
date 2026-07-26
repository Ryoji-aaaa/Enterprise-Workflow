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
- ripgrep

Node.js、npm、Java、Mavenなど、コンテナに閉じ込められる依存はホストへ導入しない。

## 設定ファイル

`.env.example`をGit管理し、実値を設定する`.env`は管理対象外とする。
`replace-with-`で始まる値はローカル起動前に変更する。

Realm JSONとUser Profile取得結果は`keycloak/generated`へ生成し、このディレクトリも
Git管理対象外とする。

## 主要Makeターゲット

| ターゲット | 役割 |
| --- | --- |
| `make setup` | ホスト依存確認、`.env`作成、必要ディレクトリと実行権限の準備 |
| `make init` | image build、インフラ、Keycloak設定、アプリ起動、全体検証 |
| `make up` | 全サービスを起動し、有限時間でhealthyまで待機 |
| `make restart` | volumeを保持して全サービスを再起動 |
| `make phase1-check` | リポジトリ骨格とCompose構文の確認 |
| `make phase2-check` | PostgreSQLとMailpitの起動・検証 |
| `make phase3-check` | Keycloakの生成、起動、設定、検証 |
| `make test-backend` | Docker testステージでSpring BootのJUnitを実行 |
| `make test-frontend` | Docker testステージでlint、型、単体テスト、build、production依存監査 |
| `make test-e2e` | Playwright専用コンテナで認証・BFF・通知・境界のE2Eを実行 |
| `make test` | backend、frontend、E2Eを順に実行 |
| `make phase4-check` | backendのテスト、起動、Actuator・依存サービス検証 |
| `make phase5-check` | Better Auth、BFF、ログイン、ロール、未登録、ログアウトの統合検証 |
| `make verify` | health、HTTP、初期データ、公開ポート、実network境界を検証 |
| `make down` | コンテナ停止。ボリュームは保持 |
| `make reset` | 対象Compose projectの開発用volumeを削除して完全再構築 |

`make setup`は既存`.env`を上書きしない。`make reset`は実行対象のCompose project名と
PostgreSQL・Keycloakデータ削除の警告を表示する。自動検証では通常projectと異なる
`COMPOSE_PROJECT_NAME`を使用する。

## シェル実装規約

- 厳格モードとしてBashは`set -Eeuo pipefail`、POSIX shは`set -eu`を使用
- JSONの生成・更新・検証には`jq`を使用
- HTTPはステータスを明示的に検証し、エラー本文は秘密情報を除いて表示
- 固定の長時間sleepや無限再試行を使用しない
- Makefileへ複雑な複数行`jq`式を置かず、専用スクリプトへ分離

Phase 3の静的検証はCompose構文、全シェルの構文、生成JSONの構文へ限定し、
実際のマウントとネットワークは`keycloak-init`サービスの実行によって確認する。

`make test-e2e`の前処理、実行、事後検証はそれぞれ
`scripts/prepare-e2e.sh`、`scripts/test-e2e.sh`、`scripts/verify-e2e.sh`へ分離する。
通常のMakeターゲットへ複雑なDB操作やMailpit API処理を記述しない。
