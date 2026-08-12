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

runtime controlは次の3つである。未設定時はworkflowで`false`として扱う。これらはFrontendの
メニュー公開を制御するFeature Flagではなく、全体またはProviderを停止するoperational kill switchである。

```text
WORKFLOW_DOCUMENT_ANALYSIS_ENABLED
DOCUMENT_INTELLIGENCE_ENABLED
CONTENT_UNDERSTANDING_ENABLED
```

stagingのDocument Analysis rolloutは二段階で行う。Phase Aでは3つのruntime controlをすべて`false`にして
Terraform plan/applyし、Document Intelligence、Foundry、Document Analysis Storage、2つのcontainer、
2つの専用User Assigned Managed Identity、3つのPrivate Endpoint、Private DNS link、RBAC role
assignment、Phase 1Aの`auto-entry-gpt-5-2`と`auto-entry-text-embedding-3-large` deploymentが作成されて
いることを確認する。前者は`gpt-5.2` version `2025-12-11`、後者は`text-embedding-3-large` version `1`で、
両方とも`GlobalStandard` capacity 150、`NoAutoUpgrade`でなければならない。Document IntelligenceとFoundryはlocal auth disabled、
public network disabled、Storageはshared key disabled、public network disabledであることも確認する。
この段階ではBackendの既存機能が正常であり、Document Analysis runtimeは`disabled`である。

Phase 1AではCustom AnalyzerのCopy/Ready確認、Analyzerへのmodel deployment設定、`AUTO_ENTRY`のAzure分析を
実行しない。CopyはVNet内から手動で開始し、Readyを確認してからのみ後続Phase 1Bへ進む。Portalでmodel deploymentを
手動作成・変更したり、public networkを一時的に有効化して回避したりしない。productionはPhase 1Aでこの2 deploymentを
作成しない。

### AUTO_ENTRY Analyzer patchのsource作成と受入

`enterprise_workflow_auto_entry_v2.1.1`は、source Content Understanding resourceで新規作成してから
stagingへcopyする。既存の`enterprise_workflow_auto_entry_v2.1`は削除・更新せず、
definitionも履歴として保持する。APIはAzure Content Understanding GA `2025-11-01`の
[Create Or Replace](https://learn.microsoft.com/en-us/rest/api/contentunderstanding/content-analyzers/create-or-replace?view=rest-contentunderstanding-2025-11-01)を使う。

作成前に新旧definitionの静的検査を実行し、sourceの`v2.1.1`が未作成であることをGETで
確認する。同じIDが存在する場合は中断し、削除や置換で回避しない。Analyzer definitionは
GET response由来のserver-generated propertyを含む可能性があるため、PUTでは正式request bodyの
`baseAnalyzerId`、`config`、`description`、`dynamicFieldSchema`、`fieldSchema`、
`knowledgeSources`、`models`、`processingLocation`、`tags`だけを一時JSONへ抽出する。
`analyzerId`はURIで指定し、`createdAt`、`lastModifiedAt`、`status`、`supportedModels`、
`warnings`とともにrequest bodyへ入れない。

初回作成時の存在確認と、正式source Analyzerのdefinition同期は分けて扱う。初回作成では前述のとおり
既存IDを置換しない。一方、正式sourceの`v2.1.1`だけが未commitの調整でcanonical definitionから
ずれたことを正式PUT対象フィールドの比較で確認できた場合は、review済みcanonical bodyに限り
source URIへ`allowReplace=true`を付けて復元できる。復元前後にstagingへPUT / Copyを送らず、sourceの
`ready`とrepository / source / stagingのcanonicalized comparisonを再確認する。この手順を追加tuningや
新しいpatch Analyzerの作成には使用しない。

リポジトリrootで実行し、一時JSONは`mktemp -d`で作成したdirectoryに置き、次のように
`az rest --body @file`で送る。
URIへ`allowReplace`を付けない。Microsoft Entra IDのAzure CLI sessionを使い、access token、
API key、client secret、SASをfile、shell history、console log、artifactへ出力しない。

```bash
(
  set -Eeuo pipefail
  umask 077
  : "${SOURCE_CONTENT_UNDERSTANDING_ENDPOINT:?source endpoint must be set}"

  readonly analyzer_id="enterprise_workflow_auto_entry_v2.1.1"
  readonly api_version="2025-11-01"
  readonly analyzer_definition="infra/content-understanding/analyzers/${analyzer_id}.json"
  temporary_directory="$(mktemp -d)"
  readonly temporary_directory
  readonly put_body="${temporary_directory}/create-analyzer.json"
  trap 'rm -f -- "${put_body}"; rmdir -- "${temporary_directory}"' EXIT

  jq -e 'with_entries(
    select(.key as $key |
      ["baseAnalyzerId", "config", "description", "dynamicFieldSchema", "fieldSchema",
       "knowledgeSources", "models", "processingLocation", "tags"] | index($key)
    )
  ) | if type == "object" and has("fieldSchema") and has("models") and
         has("processingLocation")
      then . else error("required create request fields are missing") end' \
    "${analyzer_definition}" >"${put_body}"

  az rest --method put \
    --url "${SOURCE_CONTENT_UNDERSTANDING_ENDPOINT%/}/contentunderstanding/analyzers/${analyzer_id}?api-version=${api_version}" \
    --headers "Content-Type=application/json" \
    --body @"${put_body}" \
    --output none
)
```

PUT後はsourceのAnalyzer GETを有限timeoutでpollし、`status=ready`を確認する。`failed`、
timeout、読み取り不能は受入失敗として中断する。Ready後はGET responseから正式PUT対象の
`baseAnalyzerId`、`config`、`description`、`dynamicFieldSchema`、`fieldSchema`、
`knowledgeSources`、`models`、`processingLocation`、`tags`だけを抽出し、key順を正規化して
repositoryのcanonical definitionと一致することを確認する。server-generated propertyの有無や順序を
definition差分として扱わない。

Phase 1B-Aの必須GateはIntegration / Contractであり、同一帳票に対するAI抽出結果の3/3完全一致を
source acceptanceまたはstaging acceptanceの必須条件にしない。抽出の反復実行はExtraction Qualityの
観測として実施できるが、成功回だけを選ぶretryや抽出値の補完には使わない。Raw result、帳票本文、
source polygon、分析用URLはrelease recordやCI artifactへ保存しない。

### sourceからstagingへのcross-resource Copy

sourceが`ready`でcanonical definitionと一致した場合だけ、GA `2025-11-01`の
[Grant Copy Authorization](https://learn.microsoft.com/en-us/rest/api/contentunderstanding/content-analyzers/grant-copy-authorization?view=rest-contentunderstanding-2025-11-01)と
[Copy](https://learn.microsoft.com/en-us/rest/api/contentunderstanding/content-analyzers/copy?view=rest-contentunderstanding-2025-11-01)を使い、次の順でcopyする。

1. sourceとstaging Content Understanding accountのAzure resource ID、region、endpointをcontrol planeから取得する。
   stagingに`enterprise_workflow_auto_entry_v2.1.1`が未作成であることを確認し、存在する場合は中断する。
2. Copy専用の一時User Assigned Managed Identityを作成し、sourceとstagingのそれぞれの
   Content Understanding account scopeに`Cognitive Services Content Understanding Contributor`を付与する。
   role assignment IDは後で正確に削除できるよう記録する。
3. staging Container Apps Environment内に、該当identityだけをattachしたmanual triggerの
   temporary Container Apps Jobを作成する。Jobはingress、secret、scheduleを持たせず、
   不変のimage tagと有限execution timeoutを使う。RBAC propagation後に1回だけ起動する。
4. Job内で一時identityを使ってsourceの
   `POST /contentunderstanding/analyzers/enterprise_workflow_auto_entry_v2.1.1:grantCopyAuthorization?api-version=2025-11-01`
   を実行する。bodyにstaging accountの`targetAzureResourceId`と`targetRegion`を入れ、
   responseの`expiresAt`より前に後続処理を完了する。
5. 同じJob内からstagingの
   `POST /contentunderstanding/analyzers/enterprise_workflow_auto_entry_v2.1.1:copy?api-version=2025-11-01`
   を実行する。bodyはsourceの`sourceAzureResourceId`、`sourceRegion`、
   `sourceAnalyzerId=enterprise_workflow_auto_entry_v2.1.1`だけとし、`allowReplace`をURIへ付けない。
6. stagingのAnalyzer GETを有限timeoutでpollし、`status=ready`を確認する。`failed`、timeout、
   GET失敗はJobを失敗させる。Ready後にsourceとstagingのGET responseを正式PUT allowlistで抽出し、
   request-relevantなdefinitionがsourceと一致することも確認する。
7. 成否にかかわらずtemporary Job、2つのrole assignment、temporary UAMIの順に削除する。
   記録したresource IDを使い、他のrole assignmentや通常Jobを対象にしない。削除後は3種の
   temporary resourceが存在しないことをread-only commandで確認する。

この手順中もstaging Content Understanding accountのpublic networkは無効のままとし、VNet内の
temporary Jobからprivate endpoint経由でアクセスする。既存Backend runtime UAMIの
`Cognitive Services Content Understanding Reader`は変更せず、Contributorを追加したりCopy Jobへ転用したり
しない。cleanup後にpublic networkが`Disabled`、runtime UAMIがReaderだけであり、temporary UAMIと
Contributor role assignmentが残っていないことを確認する。productionではこの手順を実行しない。

### Phase B rollout

Phase BはPhase Aとsource definitionの受入が成功し、stagingの`v2.1.1`がReadyになった後だけ実施する。
Backend runtimeのAnalyzer IDを`enterprise_workflow_auto_entry_v2.1.1`へ更新した検証済みcommitで、
stagingの3つのruntime controlを`true`に変更し、`main`から到達可能な
同じ検証済みimage SHAを再deployする。Backend revisionで`WORKFLOW_DOCUMENT_ANALYSIS_EXECUTION_MODE=azure`、
`DOCUMENT_INTELLIGENCE_ENABLED=true`、`CONTENT_UNDERSTANDING_ENABLED=true`、
`DOCUMENT_ANALYSIS_STORAGE_CREATE_CONTAINERS=false`、
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
期待しない。public IPへ解決される場合は、runtime controlを有効にしたままsmoke testへ進まない。

RBACはAzure CLIで次を確認する。既存Backend runtime UAMIへ一時回避としてOwner、Contributor、
Content Understanding Contributor、Storage Account Contributorなどを追加しない。前記cross-resource Copyの
実行中に限り、Copy専用temporary UAMIへ付与する2つのContent Understanding Contributorだけを例外とする。

```text
Document Analysis AI UAMI:
  Document Intelligence scope: Cognitive Services User
  Foundry scope: Cognitive Services Content Understanding Reader

Document Analysis Storage UAMI:
  document-analysis-input container scope: Storage Blob Data Contributor
  document-analysis-result container scope: Storage Blob Data Contributor
```

staging application smokeは既存Frontendから行う。`/document-intelligence`で小さいPDFを分析し、
`QUEUED`または`RUNNING`から`SUCCEEDED`になり、Markdown、Paragraphs、Tables、Raw Resultが表示されることを
確認する。Raw Resultが`backend-fake-provider`ではなくDocument Intelligence native resultであることも
確認する。`/content-understanding`でも同様に`CONTENT_UNDERSTANDING` providerとして成功し、API
`2025-11-01`で処理されることを確認する。`GENERAL`の`prebuilt-layout`分析は自動入力用model deploymentを
Provider呼出しへ渡さない。
保持期限確認では期限切れJobのBlob cleanup後もPostgreSQLのJob metadataが`EXPIRED`で残ることを確認する。
`RUNNING`はcleanup対象ではなく、lease expiry後に`FAILED_RECOVERY_REQUIRED`となってからcleanup対象になる。

`v2.1.1`のrevisionをdeployした後は、`invoice-02.jpg`を1回、
`provider=CONTENT_UNDERSTANDING`、`profile=AUTO_ENTRY`のJobとしてNext.js BFFの
`/api/backend/document-analyses...`経由で送信する。Job statusとnormalized viewの取得もBFF経由とし、
Browserや運用clientからSpring Boot、Content Understanding、Blob Storageへ直接接続しない。Phase 1B-Aの
Integration / Contract smokeは次をすべて満たした場合にPASSとする。

- Jobが有限timeout内に`SUCCEEDED`となり、`modelId=enterprise_workflow_auto_entry_v2.1.1`、
  `providerApiVersion=2025-11-01`である
- Normalized viewが外側`schemaVersion=1`、`status=SUCCEEDED`、
  `fields.autoEntry.schemaVersion="2.1"`である
- `DocumentType=INVOICE`で、non-nullのconfidenceとsourceを保持する
- `TaxBreakdown`がarray/object contractを維持し、`STANDARD / 10%対象額`と
  `REDUCED / 軽減8%対象額`の要素、および各`CategoryNotation`のnon-null confidenceとsourceを保持する
- Browser requestが同一originのBFFだけを通り、Azure AI、Blob Storage、Spring Bootへ直接接続しない

`TaxRatePercent`が同じ帳票でも非決定的に`null`になることは、`v2.1.1`の既知のExtraction Quality
limitationとして扱う。これ単独ではPhase 1B-AをFAILにしない。`Category`、`CategoryNotation`、金額などから
値を補完・推測せず、`null`をそのままNormalized contractへ保持する。Phase 1B-Bのreview / validationが
`MISSING`として安全に検出し、税内訳の整合性を人手確認へ回す。

Job失敗、timeout、異なるAnalyzer ID、異なるAPI version、異なるnormalized schema version、または上記の
Integration / Contract不整合がある場合はrolloutを完了扱いにせず、production操作に進まない。
public network有効化、runtime UAMIの権限昇格、API keyやSASへのfallback、およびBackendへの直接アクセスで
回避しない。受入記録はJob ID、Analyzer ID、status、contract criterionのPASS/FAILに限定し、帳票本文、
Raw JSON、credentialを転記しない。

BrowserのNetwork logには`*.cognitiveservices.azure.com`、`*.services.ai.azure.com`、
`*.blob.core.windows.net`への直接requestが存在せず、従来どおり`/api/backend/...`だけを呼ぶことを確認する。
productionの通常提供では3つのruntime controlを`true`、execution modeを`azure`にする。変更は
stagingのDocument Intelligence成功、Content Understanding成功、RBAC確認、Private DNS確認、cost/retention
確認、検証済みimage SHA promotionの後に明示的に行う。緊急停止時もFrontendのメニューは隠さない。

Phase A/Phase Bのcontrol plane検査には、Azure login済みの担当者が次を使う。scriptはread-onlyであり、
resource、role assignment、container、revisionを変更しない。input/result containerは
`az storage container-rm show`でMicrosoft.Storage control planeから検査するため、GitHub-hosted runnerは
Storage data planeへ接続しない。Shared Key、SAS、connection stringへのfallbackはない。Cognitive Account、identity、
RBAC、Private Endpoint、Private DNS、Container Apps revisionを含むAzure readが失敗した場合はfail-closedで終了する。

```bash
./scripts/verify-document-analysis-azure.sh
```

Phase Aでは3 runtime controlを`false`にした新しいstaging deployの後にこの検査を実行する。Phase Bでは`main`から到達可能な
同じ40文字SHAのまま3 runtime controlを`true`に変更して**新しい**staging deployを実行し、trafficを受ける最新revision、Flyway、readiness、既存の
匿名public smokeを確認してから、次の手動workflowを起動する。Environment variable変更前のrunをrerunしない。

```bash
gh workflow run document-analysis-staging-smoke.yml --ref main -f image_sha=<40-character-sha>
```

このworkflowはseed Jobを開始しない。staging seed user、`development-seed-password`、Key Vault accessが
事前に揃っていなければ、安全にfailした後で
[開発・staging用seedデータ](../backend/development-seed-data.md)の手順を使う。成功summaryから同一SHA、2 Provider、
status、API version、Azure Job responseの実際のcreatedAt/completedAtをrelease recordへ転記するが、文書本文やRaw JSONは転記しない。

rollbackは3 runtime controlを`false`へ戻して、同じ検証済みSHAで新しいdeploy runを開始する。Azure resource、
Storage container、Job metadataを削除せず、`FAILED_RECOVERY_REQUIRED` Jobを自動再queueしない。

## stagingの確認項目

stagingではPostgreSQL、Key Vault、経費証憑Storage Account・container、Blob専用identity、
3つの通常Container Apps、3つの手動seed JobがTerraform
stateと一致することを確認する。現在の業務DBはFlyway V008まで適用済みであり、GitHub
Environment `staging`の`CONTRACT_LEGACY_USER_COLUMNS=true`を維持する。deploy後は次を確認する。

1. workflow summaryのimage tagが対象の40文字commit SHAである。
2. Frontend、Backend、Keycloakの最新revisionがRunningで、必要なtrafficを受けている。
3. BackendのConsole logで対象revisionの最新Flyway（現在はV016）まで成功し、
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
| Document AnalysisがAzureで`401`または`403`になる | `AZURE_DOCUMENT_ANALYSIS_CLIENT_ID`がAI専用identityを指すこと、Document Intelligenceのaccount scopeに`Cognitive Services User`、Foundryに`Cognitive Services Content Understanding Reader`が付与されていること、RBAC propagationを確認する。`Cognitive Services User`はDocument Intelligence APIの実行に必要な組み込みロールであり、local authenticationを無効のまま維持する。API Key、client secret、SAS、Owner/Contributorを追加しない。 |
| Document Analysis Storageが利用できない | `DOCUMENT_ANALYSIS_STORAGE_MANAGED_IDENTITY_CLIENT_ID`がStorage専用identityを指すこと、input/result container scopeに`Storage Blob Data Contributor`があること、Storage Private EndpointとBlob Private DNSを確認する。既存の経費証憑Storageや`AZURE_CLIENT_ID`へ切り替えない。 |

PortalでTerraform管理の環境変数、secret参照、probe、trafficを恒久変更しない。調査中に必要な
構成差分が判明した場合はコードと文書をレビューし、GitHub Actionsからapplyする。
