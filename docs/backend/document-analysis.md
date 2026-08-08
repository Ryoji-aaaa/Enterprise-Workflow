# Document Analysis Backend

## 目的

Document Analysisは、Backend APIから文書ファイルを受け付け、Provider-neutralなJobとして
PostgreSQLへ保存し、Backend内WorkerがBlob Storage上の入力文書を分析して結果JSONを保存する。
ローカル開発ではFake Providerを使い、`execution-mode=azure`ではAzure AI Document
Intelligence Adapterを使える。ただしAzure resource、Managed Identity、RBAC、Private
Endpointの作成は後続工程の対象であり、staging/productionでは有効化しない。

BrowserはSpring Boot、Blob Storage、Azure AIへ直接接続しない。FrontendはNext.js BFFの
`/api/backend/document-analyses...`だけを呼び、BFFがSpring Bootの
`/api/document-analyses...`へ転送する。画面側の仕様は
[Frontend Document Analysis](../frontend/document-analysis.md)を参照する。

## API

Base pathは`/api/document-analyses`である。Controllerは
`workflow.document-analysis.enabled=true`の場合だけ登録される。

### `POST /api/document-analyses`

`multipart/form-data`で`provider`と`file`を受け付ける。`provider`は
`DOCUMENT_INTELLIGENCE`または`CONTENT_UNDERSTANDING`である。`modelId`、
`providerApiVersion`、`normalizedSchemaVersion`、Blob object name、保持期限、
credentialはBrowserから受け取らず、Backend設定から決定する。

成功時は`202 Accepted`を返し、`Location`に`/api/document-analyses/{analysisId}`を設定する。
Jobは`QUEUED`で作成される。

Provider別の権限はService層で再確認する。

| Provider | 必要な権限 |
| --- | --- |
| `DOCUMENT_INTELLIGENCE` | `DOCUMENT_INTELLIGENCE_ANALYZE` |
| `CONTENT_UNDERSTANDING` | `CONTENT_UNDERSTANDING_ANALYZE` |

### `GET /api/document-analyses`

`DOCUMENT_ANALYSIS_READ_OWN`を要求し、自分のJobだけを返す。`provider`で任意に絞り込める。
既定page sizeは20、最大page sizeは100で、`createdAt DESC, id DESC`で返す。

### `GET /api/document-analyses/{analysisId}`

`DOCUMENT_ANALYSIS_READ_OWN`を要求し、自分のJobだけを返す。他利用者のIDまたは存在しないIDは
どちらも`404 DOCUMENT_ANALYSIS_NOT_FOUND`になる。

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
Normalized JSONはPostgreSQLへ保存しない。

Workerは`workflow.document-analysis.enabled=true`の場合に登録される。
`workflow.document-analysis.execution-mode=disabled`ではJobをclaimしない。
`fake`ではFake Provider、`azure`では登録済みのAzure Provider AdapterへProviderRegistry経由で
委譲する。
定期実行では次を行う。

1. staleな`RUNNING` Jobを`FAILED_RECOVERY_REQUIRED`へ変更する。
2. `QUEUED` Jobを`FOR UPDATE SKIP LOCKED`でclaimする。
3. Jobを`RUNNING`へ変更し、attempt numberとleaseを保存してtransactionを終了する。
4. Blob Storageからsourceをloadする。
5. Providerを呼び出す。
6. `raw.json`と`view-v1.json`をresult containerへ保存する。
7. expected attempt numberが一致する場合だけJobを`SUCCEEDED`へ変更する。

Provider呼び出しとBlob I/O中にDB transactionは保持しない。古いWorkerが戻ってきた場合も、
attempt numberが一致しない完了更新は無視する。

現時点では自動retryを実装しない。lease期限切れの`RUNNING` Jobは
`FAILED_RECOVERY_REQUIRED`になり、`QUEUED`へ戻さない。`FAILED`も自動再queueしない。

## Fake Provider

Fake Providerは外部networkへ接続しない。`DOCUMENT_INTELLIGENCE`と
`CONTENT_UNDERSTANDING`の両方を処理し、`fake:{analysisId}`形式のoperation IDを返す。

Raw resultは`source=backend-fake-provider`を含むJSONである。Normalized resultは
schema version 1のJSONで、発注書のMarkdown、paragraphs、tables、fields、metricsを含む。
このV1 contractは後続のAzure Provider normalizerでも出力先になる。

## Azure AI Document Intelligence Adapter

`execution-mode=azure`かつ`document-intelligence.enabled=true`の場合だけ、
Azure AI Document Intelligence Adapterを登録する。Content Understanding Adapterは未実装であるため、
Azure modeでは`content-understanding.enabled=true`にしてもProviderRegistry上で利用不可となり、
`/api/me.features`の`contentUnderstanding`は`false`になる。APIの`POST`でも実Adapterが無い
Providerは`403 DOCUMENT_ANALYSIS_PROVIDER_DISABLED`で拒否し、処理不能なJobをqueueへ積まない。

Document Intelligence Adapterは`prebuilt-layout`を既定modelとし、Jobに保存された`modelId`を
Azure SDK呼び出しへ渡す。Service API versionは`2024-11-30`だけを許可し、
`DocumentIntelligenceServiceVersion.V2024_11_30`を明示する。SDKのlatest既定値へは依存しない。

分析要求では入力Blobを`BinaryData.fromStream`で渡し、`outputContentFormat=markdown`、
`stringIndexType=utf16CodeUnit`を指定する。locale、pages、query fields、追加課金featureは設定しない。
Azure Layoutの`AnalyzeResult.content`をNormalized V1の`documents[0].markdown`へそのまま保存し、
MarkdownをBackendで再構築したり、Markdownからtableを再parseしたりしない。

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

## 設定

既定ではDocument Analysisは無効で、Providerも無効である。

```yaml
workflow:
  document-analysis:
    enabled: false
    execution-mode: disabled
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
```

ローカルComposeでは`enabled=true`、`execution-mode=fake`、両Provider enabled、
既存Azuriteの`document-analysis-input`と`document-analysis-result` containerを使用する。

## 監査

成功時に次の監査を記録する。

- `DOCUMENT_ANALYSIS_REQUESTED`
- `DOCUMENT_ANALYSIS_SOURCE_ACCESSED`
- `DOCUMENT_ANALYSIS_RESULT_ACCESSED`

metadataは`analysisId`、`provider`、`contentType`、`fileSize`、`sha256`、`resultKind`などの
安全な値に限定する。文書本文、Markdown、Raw JSON、original filename、email、token、
credentialは監査へ保存しない。
