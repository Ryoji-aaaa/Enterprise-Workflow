# 開発用組織・ユーザーデータ

開発用データはFlywayへ入れず、`development`または`manual-seed` profileかつ
`workflow.seed.enabled=true`の場合だけInitializerで作成する。Docker Composeの通常Backendは
`development`だけを有効にし、stagingの通常Backendとproductionでは実行しない。

`DevelopmentSeedData`にSDCJ配下37組織単位の固定コードと階層を定義する。統治組織3件を
除く34組織に責任者・一般ユーザーを1名ずつ作成し、会社直下に社長を1名作成するため、
組織図用DBユーザーは69名である。既存の開発管理者・一般ユーザーを含めた業務ユーザーには
必要なDBロールを付与する。さらに雇用区分の境界確認専用として、所属を持たないパート・嘱託
ユーザーを各1名作成する。この2名は`ORGANIZATION_CHART_VIEWER`を持つがBackendで閲覧を
拒否される。

外部PoC確認用の`guest00@example.com`から`guest03@example.com`までは、69名の組織図生成
ユーザーへ混ぜず、4名の独立した`AppUser`として別枠で作成する。全員を
`SYSTEM_SOLUTION_PROJECT_1`の`MEMBER`へPRIMARY所属させ、直属上司を同Projectの責任者とする。
業務ロールは通常一般ユーザーと同じ`APPLICATION_USER`と`ORGANIZATION_CHART_VIEWER`だけで、
Guest専用Role・Permissionや`WORKFLOW_APPROVER`は付与しない。
`MEMBER`の`approvalLevel=0`をそのまま使うため、経費申請は既存Workflow定義により
`SAME_UNIT_MANAGER`から`ACCOUNTING`へ進み、Guest本人は承認Candidateにならない。

各有人組織の責任者と経理課の一般ユーザーには`WORKFLOW_APPROVER`を付与する。経理課の有効な
所属ユーザーは全員が経費承認Candidateになるため、一般ユーザーにも承認Permissionを明示する。
個別申請の処理にはこのPermissionに加えて、申請時に保存したCandidateとの一致が必要である。

Initializerは組織コード、email、役職コードを自然キーとして存在しない行だけを追加する。
既存行の手動変更は上書きせず、所属・ロールは期間重複検査で再起動時の重複を防ぐ。

Keycloak側は最初に`DEV_ADMIN_EMAIL`と`DEV_USER_EMAIL`を作成または同期し、その後
`keycloak/development-users.tsv`を読み、組織図用69名と境界確認用2名をemailで検索して
作成または同期する。同じemailが複数経路にあっても既存ユーザーを同期するだけで重複作成しない。
これにより、DB seedの一般ユーザー（既定`example.user1@sdcj.co.jp`）とKeycloak seedのログイン
ユーザーが一致する。Keycloak Roleは業務認可に使わない。

Local Keycloakの外部PoC Guestは`keycloak/guest-users.tsv`を別catalogとして読み、通常ユーザーの
`DEV_SEED_PASSWORD`とは独立した`GUEST_SEED_PASSWORD`で同期する。外部メール許可は
`example.com`ドメイン全体ではなく、`ALLOWED_EXTERNAL_EMAILS`に列挙した4アドレスの完全一致である。

## Canonical staging test fixture

stagingは完全なテスト専用環境として扱い、productionの実ユーザーや実業務データを投入しない。
staging manual seedで作成される組織、ユーザー、所属、役職、ロール割当は、E2E・smoke testで
利用できるcanonical staging test fixtureである。stagingが再構築可能な環境であることは、
master fixtureを通常テスト中に自由に書き換えてよいことを意味しない。再現性を保つため、
master fixtureはcontrolled dataとして扱う。

seedされた全ユーザーはテストに利用できるが、繰り返し使う代表的な役割は
[`tests/fixtures/staging-test-personas.json`](../../tests/fixtures/staging-test-personas.json)の
Test Persona catalogで意味論として宣言する。Personaはemail aliasではなく、対象ユーザーが
満たすべき業務契約を表す。たとえば`STANDARD_APPLICANT`は、現在のfixture mappingとして
`first-si-sales-section.user@sdcj.co.jp`を指すが、本質的な契約は有効な主所属、申請作成・本人参照、
Document Analysis権限、事業部ancestor、部門承認者と経理承認者までの承認経路を持つことである。

初期catalogは次の代表personaだけを名前付きで固定する。これはseed user全件の一覧ではない。

| Persona | email | 主所属unit | 役職 | 主な用途 |
| --- | --- | --- | --- | --- |
| `STANDARD_APPLICANT` | `first-si-sales-section.user@sdcj.co.jp` | `FIRST_SI_SALES_SECTION` | `MEMBER` | 通常申請、Document Analysis、AUTO_ENTRY |
| `DEPARTMENT_MANAGER` | `first-si-sales-section.head@sdcj.co.jp` | `FIRST_SI_SALES_SECTION` | `SECTION_HEAD` | 部門承認 |
| `DIVISION_HEAD` | `first-si-division.head@sdcj.co.jp` | `FIRST_SI_DIVISION` | `DIVISION_HEAD` | 事業部長承認、上位承認 |
| `ACCOUNTING_APPROVER` | `accounting-section.user@sdcj.co.jp` | `ACCOUNTING_SECTION` | `MEMBER` | 経理承認候補 |
| `PRESIDENT` | `president@sdcj.co.jp` | `SDCJ` | `PRESIDENT` | 全社・最上位権限の確認 |

`ACCOUNTING_APPROVER`は唯一の経理承認者であることを契約にしない。経理課の有効な所属ユーザーが
増えても、承認経路candidateに含まれることを保証する。

通常テストが作成・更新してよいデータは、経費申請、申請明細、workflow Instance/Step/Candidate/Action、
Document Analysis、AUTO_ENTRY context、添付、通知などのtransaction dataを基本とする。
組織、組織単位、役職、canonical user profile、所属、ロール、Permission対応、Role割当、
canonical Keycloak user stateは通常テストで変更しない。master fixtureそのものの変更を検証する場合は、
共有stagingではなく隔離されたlocal integration testで行う。

manual seedは冪等であり、存在しない行の追加と既存ユーザーの必要な同期を行う。一方で、
人為的に変更されたmaster fixtureを破壊的に初期状態へ戻すreset操作とは定義しない。fixture driftを
解消する必要がある場合は、対象環境の再構築または専用の復旧手順として扱う。

## stagingへの一時投入

stagingには通常Backendとは別のseed専用imageを使い、手動トリガーだけを持つAzure
Container Apps Jobを置く。seed imageはstaging deployだけがbuild・pushし、productionが
promoteする通常3 imageには含めない。
通常Backendは`WORKFLOW_SEED_ENABLED=false`のままであり、deployや再起動では開発データを
投入しない。Jobは次の3種類で、必要な対象だけを実行できる。

| Job名 | 対象 |
| --- | --- |
| `job-ewf-stg-seed-db` | 業務DBのみ |
| `job-ewf-stg-seed-kc` | Keycloak userのみ |
| `job-ewf-stg-seed-all` | DB、Keycloakの順に両方 |

Job定義は`WORKFLOW_MANUAL_SEED_ENABLED=true`、
`WORKFLOW_DEPLOYMENT_ENVIRONMENT=staging`、対象別の
`WORKFLOW_MANUAL_SEED_TARGET`を明示する。入口スクリプトと各seed処理が値を再検証し、
値の欠落、不正値、`production`を終了コード非0で拒否する。Terraformはproductionに
これらのJobを作成しない。

各処理はemail、組織コード、役職コードと期間重複を自然キーとして冪等に動作する。
Keycloakの既存ユーザーは毎回passwordを指定値へ同期するため、既存ユーザーは`existing`と
`updated`の両方へ計上される。終了時には次の形式で集計をログ出力し、成功時は終了コード0、
失敗時は非0で終了する。

`all` JobのDB seedとKeycloak seedは単一transactionではない。DB seedが成功した後に
Keycloak seedが失敗した場合、Job自体は非0終了するが、DBへの投入結果はcommit済みの
部分成功として残る。失敗原因を解消して同じJobを再実行し、冪等なDB seedを再確認してから
Keycloak seedを完了させる。

```text
manual_seed_result target=db created=... existing=... updated=... failed=...
manual_seed_result target=keycloak created=... existing=... updated=... failed=...
```

実行前にstaging Key Vaultへ`development-seed-password`を登録する。実行は対象Jobを明示して
行い、返されたexecution名とログの集計を保存する。

DB seedはFlyway migrationを実行しない。seed imageは起動時に
`--spring.flyway.enabled=false`を指定するため、対象revisionに必要な通常Backend Flyway migrationが
すべて成功済みであることが必須である。`employment_type does not exist`が発生した状態でseedを
再試行せず、先に通常Backendのmigration設定と履歴を直す。

```bash
az containerapp job start \
  --resource-group rg-enterprise-workflow-staging \
  --name job-ewf-stg-seed-db

az containerapp job start \
  --resource-group rg-enterprise-workflow-staging \
  --name job-ewf-stg-seed-kc
```

`all` Jobも利用できるが、初回確認と障害復旧では部分成功の境界を明確にするため、DB、
Keycloakを個別に実行する。stagingで確認済みの運用順は次のとおり。

1. staging Key Vaultに`development-seed-password`の有効なversionがあり、JobのManaged
   Identityに参照権限があることを確認する。secret値は画面共有やログへ表示しない。
2. `Deploy staging`を実行し、対象SHAのBackend、Frontend、Keycloak、seed imageと3つのJobを
   Terraformで反映する。
3. 通常BackendのConsole logと`flyway_schema_history`で対象revisionに必要な全migrationの成功を確認する。
4. `job-ewf-stg-seed-db`を開始し、`manual_seed_result target=db ... failed=0`を確認する。
5. `job-ewf-stg-seed-kc`を開始し、
   `manual_seed_result target=keycloak ... failed=0`を確認する。
6. `president@sdcj.co.jp`でログインし、組織図とユーザー管理を表示できることを確認する。
7. 一般ユーザーの編集不可と、パート・嘱託の組織図閲覧不可を確認する。

Portalでは対象Jobの`Execution history`からexecutionを選び、`Console`で
`manual_seed_result`とSpring例外を確認する。`System`はimage pull、replica作成、Managed
Identity、secret参照など基盤側の調査に使う。アプリケーション例外の正本はConsole logであり、
System logだけを見て原因を判断しない。長期検索はContainer Apps Environmentに接続された
Log Analytics workspaceで対象Job名、execution名、時刻を絞り込む。

同じJobは冪等に再実行できる。実行履歴、execution名、対象image SHA、2種類の
`manual_seed_result`を運用記録へ残す。productionにはJobも
`development-seed-password`も作成せず、staging用imageをproductionで実行しない。

Phase 1ではGuest DB fixtureが`manual-seed` profileからも利用できるが、Azure/Terraform、staging
Backendの`ALLOWED_EXTERNAL_EMAILS`、staging KeycloakのGuest user・password、seed Jobへの
`GUEST_SEED_PASSWORD`配線は行わない。更新imageでstaging DB seedだけを実行するとGuestのDB rowは
作成され得るため、完全なstaging Guestログイン環境はPhase 2の配線完了後に検証する。
