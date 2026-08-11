# Document Analysis Backend

## 目的

Document Analysisは、Backend APIから文書ファイルを受け付け、Provider-neutralなJobとして
PostgreSQLへ保存し、Backend内WorkerがBlob Storage上の入力文書を分析して結果JSONを保存する。
ローカル開発ではFake Providerを使い、`execution-mode=azure`ではAzure AI Document
Intelligence AdapterとAzure AI Content Understanding Adapterを使える。Azure resource、Managed
Identity、RBAC、Private Endpoint、Private DNSはTerraformで環境ごとに作成する。
staging/productionの有効化設定は公開可否を決めるFeature Flagではなく、Azure接続やWorkerを停止する
runtime controlとして管理する。

BrowserはSpring Boot、Blob Storage、Azure AIへ直接接続しない。FrontendはNext.js BFFの
`/api/backend/document-analyses...`だけを呼び、BFFがSpring Bootの
`/api/document-analyses...`へ転送する。画面側の仕様は
[Frontend Document Analysis](../frontend/document-analysis.md)を参照する。

`/api/me.features`のFeature Flag frameworkは将来の未公開機能用に維持するが、DI/CUの
`documentIntelligence`と`contentUnderstanding`は返さない。現在の`features`は
`mailNotificationHistory`だけであり、Document Analysisの利用可否はDB Permissionで表す。

## API

Base pathは`/api/document-analyses`である。Controllerは
`workflow.document-analysis.enabled=true`の場合だけ登録される。

### `POST /api/document-analyses`

`multipart/form-data`で`provider`、`file`、任意の`profile`を受け付ける。`provider`は
`DOCUMENT_INTELLIGENCE`または`CONTENT_UNDERSTANDING`である。`profile`は`GENERAL`または
`AUTO_ENTRY`で、未指定時は後方互換のため`GENERAL`である。`AUTO_ENTRY`は
`CONTENT_UNDERSTANDING`だけで利用でき、他Providerとの組合せは
`400 DOCUMENT_ANALYSIS_PROFILE_PROVIDER_INVALID`にする。`modelId`、
`providerApiVersion`、`normalizedSchemaVersion`、Blob object name、保持期限、
credentialはBrowserから受け取らず、Backend設定から決定する。

`GENERAL`はContent Understandingの`prebuilt-layout`を使う。`AUTO_ENTRY`はBackend設定の
Custom Analyzer `enterprise_workflow_auto_entry_v2.1`をJobへsnapshotする。BrowserはAnalyzer ID、
completion model deployment、embedding model deployment、API versionを指定または上書きできない。

成功時は`202 Accepted`を返し、`Location`に`/api/document-analyses/{analysisId}`を設定する。
Jobは`QUEUED`で作成される。

Provider別の権限はService層で再確認する。

| Provider | 必要な権限 |
| --- | --- |
| `DOCUMENT_INTELLIGENCE` | `DOCUMENT_INTELLIGENCE_ANALYZE` |
| `CONTENT_UNDERSTANDING` | `CONTENT_UNDERSTANDING_ANALYZE` |

### `GET /api/document-analyses`

`DOCUMENT_ANALYSIS_READ_OWN`を要求し、自分のJobだけを返す。`provider`で任意に絞り込める。
`profile`は任意で、未指定時は`GENERAL`である。既存Workbenchの履歴に`AUTO_ENTRY` Jobを混在
させないため、`AUTO_ENTRY`を読むときは`profile=AUTO_ENTRY`を明示する。既定page sizeは20、
最大page sizeは100で、`createdAt DESC, id DESC`で返す。

### `GET /api/document-analyses/{analysisId}`

`DOCUMENT_ANALYSIS_READ_OWN`を要求し、自分のJobだけを返す。`profile`は任意で、未指定時は
`GENERAL`である。他利用者のID、profileが一致しないID、存在しないIDはどれも
`404 DOCUMENT_ANALYSIS_NOT_FOUND`になる。`source`、`view`、`raw-result`も同じprofile条件を使う。

### `GET /api/document-analyses/{analysisId}/source`

自分の保持期限内Jobの入力文書を返す。`Content-Type`は検証済みのDB値、`Content-Disposition`
は`inline`、`Cache-Control`は`no-store, private`、`X-Content-Type-Options`は`nosniff`である。
BlobのlengthがDBの`fileSize`と一致しない場合は`503 DOCUMENT_ANALYSIS_STORAGE_UNAVAILABLE`にする。
Job metadataのownerと保持期限判定をDB transaction内で完了し、Blob読込とlength検証は
transaction終了後に行う。Blob読込成功後だけ、別transactionで参照監査を記録する。

### `GET /api/document-analyses/{analysisId}/view`

`SUCCEEDED`かつ保持期限内のJobだけ、`result/{analysisId}/view-v1.json`を
`application/json`で返す。未完了の場合は`409 DOCUMENT_ANALYSIS_RESULT_NOT_READY`、
保持期限切れは`410 DOCUMENT_ANALYSIS_EXPIRED`である。
Job metadataのowner、保持期限、status判定をDB transaction内で完了し、Blob読込と
`application/json`検証はtransaction終了後に行う。Blob読込成功後だけ、別transactionで
結果参照監査を記録する。

### `GET /api/document-analyses/{analysisId}/raw-result`

`SUCCEEDED`かつ保持期限内のJobだけ、`result/{analysisId}/raw.json`を`application/json`で返す。
Raw JSONはBlob Storageに保存し、PostgreSQLへ保存しない。

## ファイル検証

Backendはアップロードされたファイルを必ず検証する。

- 必須、非empty
- 10MiB以下
- original filenameが非blank、設定上限以下、`/`、`\`、制御文字を含まない
- 拡張子、宣言MIME、magic bytesの一致
- PDF、JPEG、PNGのみ許可
- SHA-256を計算してJob metadataへ保存

multipartの全体サイズ超過は、Document Analysis pathでは
`413 DOCUMENT_ANALYSIS_TOO_LARGE`を返す。既存の経費添付APIでは従来どおり
`EXPENSE_ATTACHMENT_TOO_LARGE`を返す。

## JobとWorker

Job metadataは`document_analysis_jobs`に保存する。文書本体、Raw JSON、Markdown、
Normalized JSONはPostgreSQLへ保存しない。`analysis_profile`、Analyzer ID（既存の`model_id`）、
API versionもJob作成時のsnapshotである。`AUTO_ENTRY`のContent Understanding Jobだけは
`completion_model_deployment_name`と`embedding_model_deployment_name`もsnapshotし、`GENERAL`では
両方を`null`にする。

Workerは`workflow.document-analysis.enabled=true`の場合に登録される。
`workflow.document-analysis.execution-mode=disabled`ではJobをclaimしない。
`fake`ではFake Provider、`azure`では登録済みのAzure Provider AdapterへProviderRegistry経由で
委譲する。
定期実行では次を行う。

1. staleな`RUNNING` Jobを`FAILED_RECOVERY_REQUIRED`へ変更する。
2. 保持期限内の`QUEUED` Jobだけを`FOR UPDATE SKIP LOCKED`でclaimする。
3. Jobを`RUNNING`へ変更し、attempt numberとleaseを保存してtransactionを終了する。
4. Blob Storageからsourceをloadする。
5. Providerを呼び出す。
6. Provider-neutralなresult contractを検証する。
7. `raw.json`と`view-v1.json`をresult containerへ保存する。
8. expected attempt numberが一致する場合だけJobを`SUCCEEDED`へ変更する。

Provider呼び出しとBlob I/O中にDB transactionは保持しない。古いWorkerが戻ってきた場合も、
attempt numberが一致しない完了更新は無視する。

現時点では自動retryを実装しない。lease期限切れの`RUNNING` Jobは
`FAILED_RECOVERY_REQUIRED`になり、`QUEUED`へ戻さない。`FAILED`も自動再queueしない。
Provider operation IDは結果または状態不明failureで保存するが、operation IDからの自動resume、
manual retry API、repair APIは実装しない。Provider送信直後のdurable checkpointを完全には保証して
いないため、不明状態は`FAILED_RECOVERY_REQUIRED`として人手確認の対象にする。

Job statusは次の意味で扱う。

| Status | 意味 |
| --- | --- |
| `QUEUED` | 受付済みで、保持期限内ならWorkerがclaimできる |
| `RUNNING` | Workerがclaim済みでlease中 |
| `SUCCEEDED` | Raw resultとNormalized viewをBlobへ保存済み |
| `FAILED` | Providerが安全に失敗として分類できた |
| `FAILED_RECOVERY_REQUIRED` | Provider状態または保存結果が不明で自動再送しない |
| `EXPIRED` | 保持期限後にBlob cleanupを完了し、metadataだけを残す |

## Result Contract検証

Provider成功後、Blob保存前に`DocumentAnalysisResultValidator`がFake、Document Intelligence、
Content Understandingの全Providerへ同じ検証を行う。検証対象はProvider固有のRaw shapeではなく、
Provider-neutralな最低限のcontractである。

- `rawJson`が非empty、valid JSON、root objectである
- `normalizedJson`が非emptyで`DocumentAnalysisViewV1`へdeserializeできる
- `schemaVersion`がJob claimのschema versionかつ`1`
- `analysisId`、`provider`、`modelId`、`providerApiVersion`がclaimと一致する
- `status`が`SUCCEEDED`
- `documents`と`metrics`が存在する

contract不整合は`DOCUMENT_ANALYSIS_RESULT_CONTRACT_INVALID`で
`FAILED_RECOVERY_REQUIRED`へ遷移する。Raw JSON本文、文書内容、Azure response bodyはログへ出さない。

## Retention Cleanup

保持期限の既定は7日である。`expires_at <= now`になったJobのうち、`QUEUED`、`SUCCEEDED`、
`FAILED`、`FAILED_RECOVERY_REQUIRED`だけをcleanup対象にする。`RUNNING`はactive leaseの有無にかかわらず
cleanupせず、stale recoveryで`FAILED_RECOVERY_REQUIRED`になった後に次回cleanup対象になる。

cleanupは次の順序で行う。

1. DB transaction内で`expires_at ASC, id ASC`のcandidate snapshotをbatch取得する。
2. transactionを終了し、input source、raw result、normalized viewを`deleteIfExists`で削除する。
3. 全Blob削除が成功した場合だけ、短いtransactionでJobをlockし、期限とstatusを再確認する。
4. `DocumentAnalysisJob.expire()`で`EXPIRED`へ遷移し、`lease_expires_at`をnullにする。

Blob削除に失敗したJobは`EXPIRED`へ変更しない。次回cleanupで同じdeleteIfExistsを再実行するため、
inputまたは片方のresultだけ削除済みでも再試行できる。PostgreSQLのJob rowは削除せず、
metadataと監査証跡を残す。

## Fake Provider

Fake Providerは外部networkへ接続しない。`DOCUMENT_INTELLIGENCE`と
`CONTENT_UNDERSTANDING`の両方を処理し、`fake:{analysisId}`形式のoperation IDを返す。

Raw resultは`source=backend-fake-provider`を含むJSONである。Normalized resultは
schema version 1のJSONで、発注書のMarkdown、paragraphs、tables、fields、metricsを含む。
このV1 contractは後続のAzure Provider normalizerでも出力先になる。

## Azure AI Document Intelligence Adapter

`execution-mode=azure`かつ`document-intelligence.enabled=true`の場合だけ、
Azure AI Document Intelligence Adapterを登録する。APIの`POST`では実Adapterが無いProviderを
`403 DOCUMENT_ANALYSIS_PROVIDER_DISABLED`で拒否し、処理不能なJobをqueueへ積まない。

Document Intelligence Adapterは`prebuilt-layout`を既定modelとし、Jobに保存された`modelId`を
Azure SDK呼び出しへ渡す。Service API versionは`2024-11-30`だけを許可し、
`DocumentIntelligenceServiceVersion.V2024_11_30`を明示する。SDKのlatest既定値へは依存しない。

分析要求では入力Blobを`BinaryData.fromStream`で渡し、`outputContentFormat=markdown`、
`stringIndexType=utf16CodeUnit`を指定する。locale、pages、query fields、追加課金featureは設定しない。
Azure Layoutの`AnalyzeResult.content`をNormalized V1の`documents[0].markdown`へそのまま保存し、
MarkdownをBackendで再構築したり、Markdownからtableを再parseしたりしない。

`DocumentAnalysisAzureSdkWireContractTest`は実Azure networkを使わず、Azure Java SDKのHTTP pipelineへ
固定credentialと4xx responseを渡して最初のrequestを検査する。Document IntelligenceのGA API
`2024-11-30`、`prebuilt-layout`、Markdown、UTF-16 code unitと入力PDFの`base64Source`をSDK serializerの
出力で固定する。Document Intelligence SDKのtyped optionはJSON bodyを送るため、requestの`Content-Type`は
`application/json`であり、PDF MIMEを無理にheaderへ上書きしない。

Paragraphsは`AnalyzeResult.paragraphs`から順序、role、最初のbounding regionのpage numberとpolygon、
最初のspanのUTF-16 offset/lengthを正規化する。Tablesは`AnalyzeResult.tables`からrow/column countと
cellsを正規化し、`columnHeader`以外のcell kindはFrontend互換のため`content`へ丸める。
Document Intelligence Layoutから直接得られないconfidenceは捏造せず、`null`または省略値のまま扱う。

`raw.json`にはAzure SDKの`AnalyzeResult.toJsonBytes()`で生成したJSONを保存する。Raw JSONと
Normalized JSONはBlob Storageへ保存し、PostgreSQLへ保存しない。Authorization header、token、
credential、Azure response bodyはraw、監査、ログへ保存しない。

認証はMicrosoft Entra IDの`DefaultAzureCredential`を使う。`AZURE_DOCUMENT_ANALYSIS_CLIENT_ID`が
設定されている場合だけUser Assigned Managed Identity client IDとして渡し、空の場合はローカルの
developer credential chainを許容する。API Key、connection string、client secretはDocument
Intelligence認証として使用しない。

LROは同期Pollerで待機し、`document-intelligence.analysis-timeout`を有限timeoutとして使う。
既定は25分で、`processing-timeout`より短い値だけを許可する。Azureが明示的にterminal failureを
返した場合は`FAILED`へ遷移する。polling timeoutやpolling中のnetwork failureなど、Azureが要求を
受理した後の最終状態が不明な場合は`FAILED_RECOVERY_REQUIRED`にし、同じJobから新しい
`beginAnalyzeDocument`を自動実行しない。

Provider errorは安全なerror codeへ分類する。400/415/422は
`DOCUMENT_INTELLIGENCE_INVALID_DOCUMENT`、401/403は
`DOCUMENT_INTELLIGENCE_AUTHENTICATION_FAILED`、404は
`DOCUMENT_INTELLIGENCE_RESOURCE_NOT_FOUND`、429は`DOCUMENT_INTELLIGENCE_THROTTLED`、
5xxは`DOCUMENT_INTELLIGENCE_UNAVAILABLE`、状態不明は
`DOCUMENT_INTELLIGENCE_OPERATION_STATE_UNKNOWN`として扱う。Azure response bodyは
`error_message`やログへ保存しない。

## Azure AI Content Understanding Adapter

`execution-mode=azure`かつ`content-understanding.enabled=true`の場合だけ、
Azure AI Content Understanding Adapterを登録する。`ContentUnderstandingClient`はSpring singleton
beanとして生成し、Jobごとにclientを作らない。Service API versionは`2025-11-01`だけを許可し、
`ContentUnderstandingServiceVersion.V2025_11_01`を明示する。SDKのlatest既定値やpreview APIへは
fallbackしない。

`GENERAL`のAnalyzerは`prebuilt-layout`であり、`AUTO_ENTRY`のAnalyzerはBackend設定から
`enterprise_workflow_auto_entry_v2.1`をsnapshotする。BrowserからAnalyzer IDを受け取らない。
`AUTO_ENTRY`のcompletion/embedding deployment名もProvider Requestまで保持するが、Phase 1Aでは
SDKの`modelDeployments`へ渡さない。Custom AnalyzerのCopy/Ready確認、作成・更新、`updateDefaults`、
GPT/embedding deploymentをAnalyzerへ設定する処理はPhase 1B以降の範囲である。

認証はDocument Intelligenceと同じくMicrosoft Entra IDの`DefaultAzureCredential`を使う。
`AZURE_DOCUMENT_ANALYSIS_CLIENT_ID`が設定されている場合だけUser Assigned Managed Identity client
IDとして渡し、空の場合はローカルのdeveloper credential chainを許容する。API Key、client
secret、AzureKeyCredentialはContent Understanding認証として使用しない。

分析要求では入力Blobを`BinaryData.fromStream`で渡し、typed
`beginAnalyzeBinary(modelId, binaryData, null, contentType, ProcessingLocation.GEOGRAPHY)`を使う。
`contentType`は検証済みの`application/pdf`、`image/jpeg`、`image/png`をそのまま渡す。
`ProcessingLocation.GEOGRAPHY`を明示し、service defaultのGLOBALへ依存しない。DATA_ZONEやGLOBALを
UI/APIへ露出しない。LROは同期Pollerで待機し、`content-understanding.analysis-timeout`を有限timeout
として使う。既定は25分で、`processing-timeout`より短い値だけを許可する。

同SDKの公開5引数overloadの第3引数は`ContentRange`であり、`null`はstring encodingを意味しない。このoverloadは
SDK内部で`stringEncoding=utf16`を設定するため、GA API `2025-11-01`、`processingLocation=geography`、
PDF MIMEとともにwire contract testで固定する。binary overloadのSDK contractは
`prebuilt-layout:analyzeBinary` pathを使うため、RESTのURL input用`:analyze` pathへ置き換えない。

成功時は`AnalysisResult.contents`の各`DocumentContent`をNormalized V1の`documents[]`へ変換する。
`DocumentContent.markdown`をそのまま`documents[n].markdown`へ保存し、BackendでMarkdownを再構築せず、
Markdownからtableを再parseしない。ParagraphsとTablesはstructured resultから生成する。
Paragraphの`role`はAzureの`SemanticRole`値を使い、nullの場合は`content`にする。spanはUTF-16の
offset/lengthとして扱う。`DocumentSource.parse`でsource文字列を解釈し、最初のsegmentのpage numberと
polygonをNormalized V1へ写す。独自正規表現でsourceをparseしない。Table cellは
`COLUMN_HEADER`だけ`columnHeader`へ変換し、それ以外はV1互換の`content`へ丸める。confidenceは
捏造せず`null`にする。prebuilt-layout専用のため`fields`は空objectとする。

Warningsは`AnalysisResult.getWarnings()`から安全な`code`だけをNormalized V1へ入れ、Azure warning
message本文を複製しない。Metricsの`pageCount`は返却された`DocumentContent.pages`数の合計、
`durationMilliseconds`はProvider分析開始からresult validationとnormalization完了までの時間である。
Raw JSONはContent Understanding SDKの`AnalysisResult.toJsonBytes()`で生成し、PostgreSQLへ保存しない。

Provider errorは安全なerror codeへ分類する。400/413/415/422は
`CONTENT_UNDERSTANDING_INVALID_DOCUMENT`、401/403は
`CONTENT_UNDERSTANDING_AUTHENTICATION_FAILED`、404は
`CONTENT_UNDERSTANDING_RESOURCE_NOT_FOUND`、429は`CONTENT_UNDERSTANDING_THROTTLED`、5xxは
`CONTENT_UNDERSTANDING_UNAVAILABLE`として扱う。Azureが明示的にterminal failureまたはcancelを返した場合は
`CONTENT_UNDERSTANDING_ANALYSIS_FAILED`で`FAILED`へ遷移する。polling timeoutやpolling中のnetwork
failureなど、Azureが要求を受理した後の最終状態が不明な場合は
`CONTENT_UNDERSTANDING_OPERATION_STATE_UNKNOWN`で`FAILED_RECOVERY_REQUIRED`にし、同じJobから新しい
`beginAnalyzeBinary`を自動実行しない。成功operationの結果が不整合な場合は
`CONTENT_UNDERSTANDING_RESULT_INVALID`で`FAILED_RECOVERY_REQUIRED`にする。Azure response bodyは
`error_message`やログへ保存しない。

## 設定

既定では安全側のruntime設定としてDocument AnalysisとProviderを無効にする。この既定値はUI公開を
制御するFeature Flagではなく、Provider呼び出しとJob処理を停止するoperational kill switchである。

```yaml
workflow:
  document-analysis:
    enabled: false
    execution-mode: disabled
    max-file-size: 10MB
    max-original-file-name-length: 255
    retention: 7d
    retention-cleanup-interval: 1h
    retention-cleanup-batch-size: 50
    batch-size: 2
    dispatch-interval: 2s
    processing-timeout: 30m
    max-active-jobs-per-user: 2
    max-requests-per-user-per-hour: 20
    azure:
      managed-identity-client-id: ""
    document-intelligence:
      enabled: false
      endpoint: ""
      model-id: prebuilt-layout
      api-version: 2024-11-30
      analysis-timeout: 25m
    content-understanding:
      enabled: false
      endpoint: ""
      model-id: prebuilt-layout
      api-version: 2025-11-01
      analysis-timeout: 25m
      auto-entry-analyzer-id: enterprise_workflow_auto_entry_v2.1
      auto-entry-completion-model-deployment-name: auto-entry-gpt-5-2
      auto-entry-embedding-model-deployment-name: auto-entry-text-embedding-3-large
```

ローカルComposeでは`enabled=true`、`execution-mode=fake`、両Provider enabled、
既存Azuriteの`document-analysis-input`と`document-analysis-result` containerを使用する。
Azureでは`DOCUMENT_ANALYSIS_STORAGE_BLOB_ENDPOINT`のBlob service endpointを先に解決し、
その後でinput/result container名を設定する。末尾`/`付きservice endpointをcontainerなしの
`$root`として再解釈させないため、この設定順を維持する。
`retention-cleanup-interval`と`retention-cleanup-batch-size`は
`WORKFLOW_DOCUMENT_ANALYSIS_RETENTION_CLEANUP_INTERVAL`、
`WORKFLOW_DOCUMENT_ANALYSIS_RETENTION_CLEANUP_BATCH_SIZE`で上書きできる。Azure Terraformには個別値を
渡さず、application既定値を使用する。

`WORKFLOW_DOCUMENT_ANALYSIS_ENABLED`はDocument Analysis全体のkill switch、
`WORKFLOW_DOCUMENT_ANALYSIS_EXECUTION_MODE`は`disabled` / `fake` / `azure`のruntime selector、
`DOCUMENT_INTELLIGENCE_ENABLED`と`CONTENT_UNDERSTANDING_ENABLED`はProvider別runtime enablementである。
これらを`/api/me`、メニュー、直接URLの公開判定には使用しない。正式提供構成が安定した後は、Provider別
`enabled`を整理し、`execution-mode`をruntime状態の正本へ一本化できるか検討するが、現行の環境変数と
Bean登録条件は維持する。

`CONTENT_UNDERSTANDING_AUTO_ENTRY_ANALYZER_ID`、
`CONTENT_UNDERSTANDING_AUTO_ENTRY_COMPLETION_DEPLOYMENT_NAME`、
`CONTENT_UNDERSTANDING_AUTO_ENTRY_EMBEDDING_DEPLOYMENT_NAME`は`AUTO_ENTRY` Jobのsnapshot元である。
いずれもsecretではなく、stagingではTerraformが作成したdeployment名をBackendへ渡す。runtime controlの
値はAzure model deployment resourceの作成有無を制御しない。

## 監査

成功時に次の監査を記録する。

- `DOCUMENT_ANALYSIS_REQUESTED`
- `DOCUMENT_ANALYSIS_SOURCE_ACCESSED`
- `DOCUMENT_ANALYSIS_RESULT_ACCESSED`

metadataは`analysisId`、`provider`、`profile`、`contentType`、`fileSize`、`sha256`、`resultKind`などの
安全な値に限定する。文書本文、Markdown、Raw JSON、original filename、email、token、
credentialは監査へ保存しない。
