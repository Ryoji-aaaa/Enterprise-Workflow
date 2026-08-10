# Azureデプロイ

初回は次の順番で行う。

1. bootstrap resourceとOIDC/RBACを作る。
2. 環境を`provision_workloads=false`でapplyし、経費証憑Storage Account、非公開container、
   Backend Blob専用Managed Identityを含むfoundationを作る。
3. Key Vaultへ6個の通常秘密値と、stagingだけに開発seed passwordを登録する。
4. GitHub Environmentの`PROVISION_WORKLOADS`を`true`にする。
5. staging workflowを手動実行するか、実装をmainへmergeする。
6. SHA image push、Terraform apply、Keycloak設定、smoke testの成功を確認する。
7. production workflowを`foundation` phaseで実行する。
8. production Key Vaultへ別の秘密値を登録する。
9. `workloads` phaseを選び、stagingで検証した同じ40文字SHAを入力する。

通常のstaging deployはmainのCI成功を起点に自動実行する。productionは手動だけであり、
ACRに3 imageが存在することを確認してから同じtagを適用する。`latest`は作成も参照もしない。

smoke testはfrontend、OIDC discovery、login入口を匿名で確認する。Backend Actuatorは
external URLを持たないため、Container Apps revisionのprobeとLog Analyticsで確認する。
本番でテストユーザーを使う完全E2Eは行わない。

staging開発データはdeployから投入しない。必要な期間だけ、
[`development-seed-data.md`](../backend/development-seed-data.md)の手動Container Apps Jobを
対象別に開始する。productionにはseed Jobを作成せず、seed入口もproductionを拒否する。

Azureにはメールサービスを配置せず、通知delivery modeは`disabled`固定とする。SMTP、メール配送、
通知Outbox行、メール履歴API・画面は存在しない。Backendのliveness/readinessはmailを評価せず、
通常の業務APIを提供できる状態をReadyとする。未登録ユーザーのアクセス要求はDBへ保存する。
メールサービス導入時は別の設計変更とし、配送成否をprobeから独立して監視する。

デプロイ後はPortalで最新Backend revisionがActiveかつRunning、trafficが100%、
replicaが1以上であることを確認する。再ログイン後に`/api/backend/me`が
`BACKEND_UNAVAILABLE`にならないことを確認する。業務DB未登録ユーザーは
`APPLICATION_USER_NOT_REGISTERED`から未登録ユーザー画面へ進めば正常であり、登録済み
ユーザーはTopページが表示されることを確認する。Portalからprobeや環境変数を変更せず、
差異があればTerraformを修正する。

Frontend revisionの`BACKEND_INTERNAL_URL`はTerraformがBackend ingressから取得した
`https://<backend-name>.internal.<environment-default-domain>`形式であることを確認する。
Backend ingressはinternalのままとし、外部URLやBrowserから直接疎通確認しない。
`BACKEND_UNAVAILABLE`が続く場合はFrontendログで接続先とproxy errorを確認し、Backend
ログで`/api/me`到達を確認する。環境変数をPortalから修正せず、Terraformの生成値を直して
Frontend revisionを更新する。

Backend revisionには`AZURE_STORAGE_BLOB_ENDPOINT`、`AZURE_STORAGE_CONTAINER_NAME`、
`AZURE_CLIENT_ID`がTerraformから設定され、Blob専用identityが追加されていることを確認する。
`AZURE_STORAGE_CONNECTION_STRING`、Storage key、SASは設定しない。Storage Accountはshared key無効、
container非公開で、Backend専用identityだけがcontainer scopeの`Storage Blob Data Contributor`を持つ。
FrontendとKeycloakへこのidentityまたはBlob RBACを付与しない。確認方法と障害時の境界は
[経費証憑Blob Storage](../infrastructure/expense-attachment-storage.md)を参照する。

PRの環境別planを有効にする前に、`staging-plan`と`production-plan`のGitHub Environmentへ
`AZURE_ATTACHMENT_STORAGE_ACCOUNT_NAME`を各環境の値で個別に登録する。値が未設定の状態で
`AZURE_OIDC_CONFIGURED=true`へ変更せず、workflowが`Azure plan skipped`ではなくstaging、
production双方の`Terraform plan`まで成功したことを確認する。

Document Analysis Azure mode用に、`staging-plan`、`staging`、`production-plan`、`production`へ次の
resource名を環境ごとに登録する。値はsecretではなく、workflowやTerraformコードへ実名を固定しない。

```text
AZURE_DOCUMENT_INTELLIGENCE_ACCOUNT_NAME
AZURE_CONTENT_UNDERSTANDING_ACCOUNT_NAME
AZURE_DOCUMENT_ANALYSIS_STORAGE_ACCOUNT_NAME
```

activation flagは次の3つである。未設定時はworkflowで`false`として扱う。

```text
WORKFLOW_DOCUMENT_ANALYSIS_ENABLED
DOCUMENT_INTELLIGENCE_ENABLED
CONTENT_UNDERSTANDING_ENABLED
```

stagingのDocument Analysis rolloutは二段階で行う。Phase Aでは3つのactivation flagをすべて`false`にして
Terraform plan/applyし、Document Intelligence、Foundry、Document Analysis Storage、2つのcontainer、
2つの専用User Assigned Managed Identity、3つのPrivate Endpoint、Private DNS link、RBAC role
assignmentが作成されていることを確認する。Document IntelligenceとFoundryはlocal auth disabled、
public network disabled、Storageはshared key disabled、public network disabledであることも確認する。
この段階ではBackendの既存機能が正常であり、Document Analysis runtimeは`disabled`である。

Phase BはPhase A成功後だけ実施する。stagingの3つのactivation flagを`true`に変更し、同じ検証済み
image SHAを再deployする。Backend revisionで`WORKFLOW_DOCUMENT_ANALYSIS_EXECUTION_MODE=azure`、
`AZURE_DOCUMENT_ANALYSIS_CLIENT_ID`、`DOCUMENT_ANALYSIS_STORAGE_MANAGED_IDENTITY_CLIENT_ID`、
Document Intelligence/Content Understanding endpoint、Document Analysis Storage endpointとcontainer名が
設定されていることを確認する。既存の`AZURE_CLIENT_ID`は経費証憑Blob専用identityのclient IDのままである。
Content UnderstandingのendpointはTerraform outputだけで確定せず、現在のJava
`ContentUnderstandingClient`で`prebuilt-layout`分析が成功することをstaging smokeで確認する。
もしFoundry resourceの標準endpointがSDK contractと合わないことをlive smokeで確認した場合だけ、
services.ai系などSDKが受け付けるendpointへTerraformを修正し、`make verify-infra`とplanを再実行する。
public networkを一時的に有効化して回避しない。

Private DNSはBackend Container App内部から確認する。`az containerapp exec`などでBackend revision内から
Document Intelligence endpoint、Content Understanding endpoint、Storage blob endpointを名前解決し、
private IPが返ることを確認する。GitHub-hosted runnerや運用端末からprivate IPへ直接接続できることは
期待しない。public IPへ解決される場合は、activation flagを有効にしたままsmoke testへ進まない。

RBACはAzure CLIで次を確認する。一時回避としてOwner、Contributor、Content Understanding Contributor、
Storage Account Contributorなどを追加しない。

```text
Document Analysis AI UAMI:
  Document Intelligence scope: Cognitive Services Data Reader
  Foundry scope: Cognitive Services Content Understanding Reader

Document Analysis Storage UAMI:
  document-analysis-input container scope: Storage Blob Data Contributor
  document-analysis-result container scope: Storage Blob Data Contributor
```

staging application smokeは既存Frontendから行う。`/document-intelligence`で小さいPDFを分析し、
`QUEUED`または`RUNNING`から`SUCCEEDED`になり、Markdown、Paragraphs、Tables、Raw Resultが表示されることを
確認する。Raw Resultが`backend-fake-provider`ではなくDocument Intelligence native resultであることも
確認する。`/content-understanding`でも同様に`CONTENT_UNDERSTANDING` providerとして成功し、API
`2025-11-01`で処理されることを確認する。Content Understandingの`prebuilt-layout`ではFoundry model
deploymentが0件でも分析できる。
保持期限確認では期限切れJobのBlob cleanup後もPostgreSQLのJob metadataが`EXPIRED`で残ることを確認する。
`RUNNING`はcleanup対象ではなく、lease expiry後に`FAILED_RECOVERY_REQUIRED`となってからcleanup対象になる。

BrowserのNetwork logには`*.cognitiveservices.azure.com`、`*.services.ai.azure.com`、
`*.blob.core.windows.net`への直接requestが存在せず、従来どおり`/api/backend/...`だけを呼ぶことを確認する。
productionではPlan7導入時点で`WORKFLOW_DOCUMENT_ANALYSIS_ENABLED=false`を維持する。production activationは
stagingのDocument Intelligence成功、Content Understanding成功、RBAC確認、Private DNS確認、cost/retention
確認、検証済みimage SHA promotionの後に明示的に行う。

Phase A/Phase Bのcontrol plane検査には、Azure login済みの担当者が次を使う。scriptはread-onlyであり、
resource、role assignment、container、revisionを変更しない。

```bash
./scripts/verify-document-analysis-azure.sh
```

Phase Aでは3 flagを`false`にした新しいstaging deployの後にこの検査を実行する。Phase Bでは同じ40文字SHAの
まま3 flagを`true`に変更して**新しい**staging deployを実行し、active revision、Flyway、readiness、既存の
匿名public smokeを確認してから、次の手動workflowを起動する。Environment variable変更前のrunをrerunしない。

```bash
gh workflow run document-analysis-staging-smoke.yml --ref main -f image_sha=<40-character-sha>
```

このworkflowはseed Jobを開始しない。staging seed user、`development-seed-password`、Key Vault accessが
事前に揃っていなければ、安全にfailした後で
[開発・staging用seedデータ](../backend/development-seed-data.md)の手順を使う。成功summaryから同一SHA、2 Provider、
analysis ID、API version、終了時刻をrelease recordへ転記するが、文書本文やRaw JSONは転記しない。

rollbackは3 activation flagを`false`へ戻して、同じ検証済みSHAで新しいdeploy runを開始する。Azure resource、
Storage container、Job metadataを削除せず、`FAILED_RECOVERY_REQUIRED` Jobを自動再queueしない。

## stagingの確認項目

stagingではPostgreSQL、Key Vault、経費証憑Storage Account・container、Blob専用identity、
3つの通常Container Apps、3つの手動seed JobがTerraform
stateと一致することを確認する。現在の業務DBはFlyway V008まで適用済みであり、GitHub
Environment `staging`の`CONTRACT_LEGACY_USER_COLUMNS=true`を維持する。deploy後は次を確認する。

1. workflow summaryのimage tagが対象の40文字commit SHAである。
2. Frontend、Backend、Keycloakの最新revisionがRunningで、必要なtrafficを受けている。
3. BackendのConsole logで対象revisionの最新Flyway（現在はV014）まで成功し、
   readinessが成功している。
4. Keycloak realm/client設定とpublic smoke testが成功している。
5. seedが必要な場合だけ、[seed手順](../backend/development-seed-data.md)に従ってJobを手動実行する。

Jobの`Execution history`は開始・終了時刻、状態、execution名を確認する入口である。各executionの
`Console`にはSpring Bootまたはseed scriptの標準出力・例外、`System`にはimage pull、replica、
Managed Identity、secret参照などContainer Apps基盤のイベントが出る。アプリケーション例外は
Consoleを先に確認する。期間をまたいだ検索や複数replicaの照合には、Container Apps Environmentの
Log Analytics workspaceを使い、Container AppまたはJob名、revision/execution名、時刻で絞り込む。

## 障害調査

失敗時はworkflowの失敗step、Container AppsのrevisionまたはJob execution、Console log、
System log、Log Analytics、依存先の順に調べる。代表例は次のとおり。

| 症状 | 確認・対応 |
| --- | --- |
| `development-seed-password`を参照できない | staging Key Vaultに有効なsecret versionがあることと、JobのUser Assigned Managed Identityに`Key Vault Secrets User`があることを確認する。値はログへ出さない。 |
| `employment_type does not exist` | 通常Backendが`SPRING_FLYWAY_TARGET=006`で止まっていないか、`CONTRACT_LEGACY_USER_COLUMNS=true`か、`flyway_schema_history`がV008まで成功しているかを確認する。DB seed Job自身はFlywayを無効化している。 |
| Docker build中のDocker Hub `i/o timeout` | base image取得時だけの一時通信障害ならworkflowを再実行する。コード、migration、Terraformの失敗と混同しない。 |
| Container Apps Jobが`Failed` | System logだけで判断せず、対象executionのConsole logでSpring例外と`manual_seed_result ... failed=...`を確認する。部分成功後は原因を直し、冪等な対象Jobを再実行する。 |
| Flyway V007が失敗 | 旧revisionの停止とwrite drain、reconciliation対象データ、Console log、履歴を確認する。`flyway repair`は使用せず、原因を解消してcontract deployを再試行する。 |
| 添付APIが`EXPENSE_ATTACHMENT_STORAGE_UNAVAILABLE` | Backend revisionにBlob専用identityとendpoint/container/client IDがあること、container scope RBACが反映済みであること、Storage Accountのservice状態を確認する。connection stringやshared keyを追加せずTerraformを修正する。 |
| Document AnalysisがAzureで`401`または`403`になる | `AZURE_DOCUMENT_ANALYSIS_CLIENT_ID`がAI専用identityを指すこと、Document Intelligenceに`Cognitive Services Data Reader`、Foundryに`Cognitive Services Content Understanding Reader`が付与されていること、RBAC propagationを確認する。API Key、client secret、Owner/Contributorを追加しない。 |
| Document Analysis Storageが利用できない | `DOCUMENT_ANALYSIS_STORAGE_MANAGED_IDENTITY_CLIENT_ID`がStorage専用identityを指すこと、input/result container scopeに`Storage Blob Data Contributor`があること、Storage Private EndpointとBlob Private DNSを確認する。既存の経費証憑Storageや`AZURE_CLIENT_ID`へ切り替えない。 |

PortalでTerraform管理の環境変数、secret参照、probe、trafficを恒久変更しない。調査中に必要な
構成差分が判明した場合はコードと文書をレビューし、GitHub Actionsからapplyする。
