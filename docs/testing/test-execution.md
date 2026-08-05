# 統括テスト実行仕様

## 公開コマンド

自動テストの公開入口は`make test`だけとする。未指定時はBackend、Frontend、Keycloak、E2Eを
この固定順で実行する。個別または複数suiteは`SUITES`で選択する。

```bash
make test
make test SUITES=backend
make test SUITES=frontend
make test SUITES=keycloak
make test SUITES=e2e
make test SUITES=backend,frontend
```

`SUITES=all`は未指定と同じである。使用可能な名前以外、空要素、重複、略称は実行前に
usage errorとなる。指定順にかかわらず実行順は変わらない。

生ログを端末にも表示する場合は`VERBOSE=1`を指定する。失敗した隔離Compose環境を調査のため
残す場合は`KEEP_TEST_ENV=1`を指定する。

```bash
make test SUITES=frontend VERBOSE=1
make test SUITES=e2e KEEP_TEST_ENV=1
```

## テストとRequired checks

テスト件数にはMaven Surefire、Node.js JUnit reporter、Keycloakの名前付きcontract case、
Playwrightの論理テストケースだけを含める。lint、型検査、production build、image build、
環境準備、PostgreSQL migration契約、E2E事後条件、architecture検証はRequired checksとして
別に集計する。

各suiteはDiscovered、Executed、Pass、Fail、Error、Skipを表示する。テストfailureと
JUnitの`error`はSuite Fail、結果欠落、不正XML、0件、setup・runner異常はSuite Errorとなる。
独立する後続suiteは先行suiteが失敗しても実行する。

## 出力と調査方法

実行単位の成果物は`test-results/<run-id>/`へ保存する。

```text
metadata.json
phases/
raw/junit/
raw/cases/keycloak.ndjson
raw/checks/checks.ndjson
logs/
diagnostics/e2e/
summary.json
summary.md
merged-junit.xml
```

最新run IDは`test-results/latest-run.txt`で確認できる。失敗時は最終summaryの失敗名、fileと
line、理由を確認し、表示されたlog、E2Eの場合は`diagnostics/e2e/`のHTML report、trace、
スクリーンショット、videoの順に調査する。端末へ表示するmessageは1000文字までだが、
構造化結果とlogには完全な内容を保持する。

終了コードは全成功が0、テストまたはRequired check failureが1、usage・preflight・setup・
runner・reporter errorが2、利用者による割り込みが130である。

## 隔離環境とCI

KeycloakとE2Eは`workflow-test-<run-id>`という専用Compose project、専用volume、通常開発環境と
異なるポートを使用する。既定はFrontend 13000、Keycloak 18180、Mailpit 18025で、使用中なら
既存環境を停止せずerrorにする。並行runでは`TEST_FRONTEND_PORT`、`TEST_KEYCLOAK_PORT`、
`TEST_MAILPIT_PORT`へ別のポートを指定する。`.env`がなければ`.env.example`を基底に一時envだけを作り、
秘密を含むenvとKeycloak生成JSONは`/tmp`から成果物へコピーしない。

CIもローカルと同じ`make test`を使用し、`summary.md`をGitHub Step Summaryへ掲載して、
`test-results/`全体を14日間のartifactとして保存する。
