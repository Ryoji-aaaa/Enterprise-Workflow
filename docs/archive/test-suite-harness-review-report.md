# テストハーネス修正 作業報告書

作成日: 2026-08-06

対象ブランチ: `refactor/test-suite-harness`

比較基準: `origin/refactor/test-suite-harness`の`7c86931c9cf14de8691458551036752656e1d286`

本書は、Reporter異常判定・並行実行・Playwright結果・preflightに関する承認済みPlanの
実行結果と、その後に追加された`[RESULT]`行の色表示変更をChatGPTでレビューするための
作業報告である。現在の操作仕様の正本は
[統括テスト実行](../testing/test-execution.md)とする。

## 変更要望

### 承認済みPlan

- Python Reporterの終了コード契約`PASS=0`、`FAIL=1`、`ERROR=2`を維持する。
- 有効な`overall=ERROR`とexit code 2をReporter故障としてfallbackしない。
- fallbackはReporterが有効な最終成果物を生成できなかった場合だけ実行する。
- Reporter imageとpreserve imageのtagをrun ID単位にし、並行runの競合を解消する。
- Playwright未実行時はJUnit/JSON欠落を派生エラーとして追加しない。
- `envsubst`はKeycloakまたはE2Eを選択したpreflightでだけ要求する。
- 並行run対応、Playwright JUnitを件数・成否の正本とする設計、既存の認証・BFF・DB境界を維持する。
- 業務コード、テストケース本文、Flyway SQL、認証、BFF、DB schema、Terraform、Azureは変更しない。

### 追加変更

- `FINAL TEST SUMMARY`より前に表示される各Suiteの`[RESULT]`途中結果をstatus別に色付けする。
- `[RESULT]`ラベルだけでなく行全体を色付けする。
- PASSは緑、FAILは赤、ERRORも赤で表示する。

## 実装結果

### Reporterとfallback

- `summary.json`、`summary.md`、`merged-junit.xml`、コンソールの`FINAL TEST SUMMARY`、
  `overall`とexit codeの対応を検証してReporter完了を判定するようにした。
- `PASS:0`、`FAIL:1`、`ERROR:2`が一致し成果物が有効ならReporter出力を採用する。
- 有効な`ERROR:2` fixtureでfallbackが動かず、`structured reporter` failureが追加されないこと、
  cleanup errorが1件のまま集計されることをBash self-testで確認した。
- Reporter container起動失敗、成果物欠落、`overall`とexit code不一致ではfallbackし、
  `structured reporter` failureを追加することを確認した。

### 並行runとimage tag

- Reporter、preserve、Backend、Frontend、E2E、Keycloak初期化imageをrun ID固有tagにした。
- 通常cleanup後の最終Reporterはrun固有preserve imageを使用し、最終集計後に通常tagと
  preserve tagをexact指定で削除する。
- `parallel-a`と`parallel-b`を同時実行し、両runでFrontend 19/19、Required checks 9/9、
  exit code 0を確認した。片方のcleanup対象に他方のtagが含まれず、終了後に両runのReporter tagが
  残らないことも確認した。

### Playwright結果

- Playwright phaseのstatusが`passed`または`failed`の場合だけ、Playwrightが実行されたと判定する。
- phaseが`error`または`cancelled`の場合はJUnit/JSON欠落を追加しない。
- 未実行fixtureのFailure一覧が元の`services did not become healthy`だけであり、
  `Playwright JSON report is missing`と`JUnit XML is missing for selected suite e2e`を
  含まないことを確認した。
- `passed`または`failed`なのにJSONがない場合は従来どおりSuite ERRORとし、
  有効なJSONがある場合はfile、line、retry、attachmentの補完を維持した。

### 条件付きpreflight

- Backendのみ、Frontendのみでは`envsubst`を要求しない。
- Keycloak、E2E、全Suiteでは`envsubst`を1件だけ要求する。
- Keycloak選択時に`envsubst`を検出できないfixtureでは、preflightが
  `Missing required commands: envsubst`を表示しexit code 2になることを確認した。

### `[RESULT]`行の色表示

- 既存の共通log色制御を再利用し、`[RESULT]`を含む行全体をstatus別に色付けした。
- PASSは緑、FAILとERRORは赤、未知statusは警告色の黄とした。
- 対話TTYでだけ色を有効にし、`CI`、`NO_COLOR`、`TERM=dumb`、標準出力のリダイレクトでは
  ANSI escape sequenceを出力しない。構造化成果物と最終summaryの形式は変更していない。
- pseudo-terminal self-testでPASS/FAIL/ERRORの色とresetを確認し、非TTY、`CI=1`、
  `NO_COLOR=1`、`TERM=dumb`ではescape sequenceがないことを確認した。

## 変更ファイル

承認済みPlanの実装commit `7c86931`:

- `tools/test/run.sh`
- `tools/test/lib/harness.bash`
- `tools/test/report/aggregate.py`
- `tools/test/report/test_aggregate.py`
- `tools/test/tests/test-harness.sh`
- `docs/testing/test-execution.md`

追加変更:

- `tools/test/lib/harness.bash`: `[RESULT]`行の色選択と出力
- `tools/test/tests/test-harness.sh`: TTY、非TTY、`CI`、`NO_COLOR`の色表示self-test
- `docs/testing/test-execution.md`: 色表示条件の操作仕様
- `docs/archive/test-suite-harness-review-report.md`: 本作業報告

## テスト結果

### 承認済みPlanの検証

| 検証 | 結果 |
| --- | --- |
| Python Reporter unit tests | 22件成功 |
| Bash harness self-test | 成功 |
| Backend個別Suite | 109/109成功、Required checks 13/13成功 |
| Frontend個別Suite | 19/19成功、Required checks 9/9成功 |
| Keycloak個別Suite | 16/16成功、Required checks 8/8成功 |
| E2E個別Suite（Tomcat停止前） | 12件中11件成功。ホストの8080番で別プロセスが応答したため、ネットワーク境界テスト1件失敗 |
| 並行Frontend 2 run | `parallel-a`、`parallel-b`とも19/19成功、Required checks 9/9成功 |
| 全Suite（Tomcat停止後） | run ID `20260806T043214Z-7c86931c-333067`で156/156成功、Required checks 27/27成功 |

Tomcat停止前のE2E Failureは`Spring Bootへホストから直接接続できない`であり、
アプリケーションのテスト用Backendではなくホストの別プロセスが8080番へ応答したことによる。
Tomcat停止後はE2E 12/12を含めて全件成功した。

### 追加変更後の検証

| コマンドまたは確認 | 結果 |
| --- | --- |
| `bash -n tools/test/lib/harness.bash` | 成功 |
| `bash -n tools/test/tests/test-harness.sh` | 成功 |
| `bash tools/test/tests/test-harness.sh` | 成功 |
| `git diff --check` | 成功 |
| ShellCheck | hostに未導入のため未実行。Bash構文検証とself-testで代替 |
| `make test` | run ID `20260806T044155Z-7c86931c-368949`で156/156成功、Required checks 27/27成功 |
| 対話端末の`make test` | run ID `20260806T044715Z-7c86931c-393063`で156/156成功、Required checks 27/27成功 |
| 隔離環境の`make up` | 成功 |
| 隔離環境の`make verify` | 全service、DB分離、Keycloak、Backend、Frontend/BFF、Docker network境界が成功 |
| 隔離環境の`make down` | 成功。一時container、network、volume、生成ファイルを削除 |

通常の`make up`は、対象リポジトリ外のCompose project `dcordering`がホストの8025番を
使用中だったため起動できなかった。該当projectは停止していない。通常環境へ影響を与えずに
`make verify`を実施するため、別project名、専用volume、空いている一時ポートを使う隔離Compose環境を
起動した。最初の検証では、新規Keycloak DBに開発ユーザー74件とメールドメイン制約が未設定のため
該当する2 contractが失敗した。既存のconfigure手順で開発ユーザーとUser Profile制約を設定して
再実行したところ、検証はすべて成功した。一時env、Keycloak生成ファイル、container、network、
volumeは削除済みである。

## 影響範囲

- Database/Flyway migration: 変更なし。
- 認証、認可、監査: 変更なし。
- 業務コード、Backend/Frontend API、BFF: 変更なし。
- Playwrightテストケース本文: 変更なし。
- Terraform、Azure、deployment構成: 変更なし。
- npm、Maven、Python依存: 追加なし。

## ChatGPTレビュー観点

1. Python Reporterの`PASS=0`、`FAIL=1`、`ERROR=2`契約を維持しつつ、成果物検証によって
   Reporter故障と有効なSuite ERRORを正しく区別できているか。
2. run固有の通常Reporter tagとpreserve tagの切替・削除順が、通常終了、異常終了、
   `KEEP_TEST_ENV=1`、並行runで競合しないか。
3. Playwrightの実行済み判定が`passed`/`failed`だけで、未実行時のFailure一覧に派生エラーを
   重複追加しないか。
4. `envsubst`がKeycloak/E2Eでだけ必須になり、Backend/Frontendのpreflightを不必要に
   厳しくしていないか。
5. `[RESULT]`行全体の色付けが既存の共通色制御と一致し、CI、非TTY、`NO_COLOR`、
   `TERM=dumb`で機械可読なplain textを維持しているか。
6. 変更がテストハーネスと文書に限定され、業務・認証・DB・Infrastructureへ波及していないか。

## 未実行・留意事項

- 対象リポジトリ外の`dcordering`を停止して通常ポートで`make up`する検証は実施していない。
  代わりに、同じCompose定義を専用projectと一時ポートで起動し、`make verify`を完了した。
