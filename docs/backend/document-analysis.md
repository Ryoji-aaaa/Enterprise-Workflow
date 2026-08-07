# Document Analysis Backend

## 目的

Document Analysisは、Backend APIから文書ファイルを受け付け、Provider-neutralなJobとして
PostgreSQLへ保存し、Backend内WorkerがBlob Storage上の入力文書を分析して結果JSONを保存する。
Plan3時点ではAzure AIへ接続せず、ローカル開発用のFake Providerだけを実行する。

BrowserはSpring Bootへ直接接続しない。Frontend/BFF接続は後続工程で行う。

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

### `GET /api/document-analyses/{analysisId}/view`

`SUCCEEDED`かつ保持期限内のJobだけ、`result/{analysisId}/view-v1.json`を
`application/json`で返す。未完了の場合は`409 DOCUMENT_ANALYSIS_RESULT_NOT_READY`、
保持期限切れは`410 DOCUMENT_ANALYSIS_EXPIRED`である。

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

Workerは`workflow.document-analysis.execution-mode=fake`の場合に起動する。
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

Plan3では自動retryを実装しない。lease期限切れの`RUNNING` Jobは
`FAILED_RECOVERY_REQUIRED`になり、`QUEUED`へ戻さない。`FAILED`も自動再queueしない。

## Fake Provider

Fake Providerは外部networkへ接続しない。`DOCUMENT_INTELLIGENCE`と
`CONTENT_UNDERSTANDING`の両方を処理し、`fake:{analysisId}`形式のoperation IDを返す。

Raw resultは`source=backend-fake-provider`を含むJSONである。Normalized resultは
schema version 1のJSONで、発注書のMarkdown、paragraphs、tables、fields、metricsを含む。
このV1 contractは後続のAzure Provider normalizerでも出力先になる。

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
