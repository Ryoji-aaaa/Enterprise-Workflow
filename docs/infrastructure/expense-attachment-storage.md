# 経費証憑Blob Storage

## 責務と境界

領収書・証憑のファイル本体は環境別Azure Blob Storage、メタデータは業務PostgreSQLへ保存する。
BrowserはNext.js BFF、Spring Bootを順に経由し、BrowserとNext.jsからBlobへ直接接続しない。
APIはBlob URL、SAS、Storage key、connection string、object名を返さない。

ローカル・E2EではComposeのAzuriteを使う。Azuriteはapplication network内だけに所属し、
ホストportを公開しない。Backendだけが開発用connection stringを持ち、起動時に
`expense-evidence` containerを作成する。`make reset`は`azurite-data` volumeも削除する。

## Azure構成

`infra/modules/blob-storage`は環境ごとに次を作成する。

- StorageV2、Standard LRS、Hot access tier
- HTTPS限定、TLS 1.2以上
- public container禁止、shared key無効、OAuthを既定認証に設定
- 非公開`expense-evidence` container
- Blob versioning無効、lifecycleによる自動削除なし
- Blobとcontainerのsoft delete 30日

Backend Blob専用User Assigned Managed Identityへ、container scopeの
`Storage Blob Data Contributor`だけを付与する。Backend Container Appだけにこのidentityを追加し、
Frontend、Keycloak、共通runtime identity、seed Jobには付与しない。Backend環境変数は次の非秘密値だけである。

```text
AZURE_STORAGE_BLOB_ENDPOINT=https://<account>.blob.core.windows.net
AZURE_STORAGE_CONTAINER_NAME=expense-evidence
AZURE_CLIENT_ID=<backend-blob-identity-client-id>
ATTACHMENT_STORAGE_CREATE_CONTAINER=false
```

Azureでは`DefaultAzureCredential`が指定client IDのManaged Identityを使用する。
connection string、Storage key、SASをKey Vault、Terraform、Container App環境変数へ登録しない。

## staging障害調査中の一時診断

Expense Attachment uploadの`InvalidUri`調査中に限り、stagingのBlob ServiceへTerraform管理の
Diagnostic Settingを一時的に設定し、`StorageWrite`だけを既存Log Analytics workspaceへ
resource-specific形式で送信する。`StorageRead`、`StorageDelete`、metricsは収集せず、productionには
Diagnostic Settingを作成しない。Azure platform diagnostic logの`Uri`と`ObjectKey`は、通常の
application運用ログに対する禁止事項の期間限定例外として扱う。

診断期間中もBackend application logへURI、object名、headers全体、body、ファイル内容、metadata、
credential、例外messageを追加しない。Backend Blob HTTP policyは失敗したrequestのmethod、HTTP status、
`x-ms-client-request-id`だけを記録し、既存のstorage failure logと`StorageBlobLogs.ClientRequestId`を
照合する。Azure Storage側の`Uri`と`ObjectKey`のraw値は調査担当者だけが確認し、chat、報告書、Git、
ドキュメントへ転記しない。

診断は承認済みの短い再現期間に限定し、必要な記録を取得後は別のTerraform変更でDiagnostic Settingを
削除する。削除後も取り込み済みログは即時消去されず、Log Analyticsの保持期間に従い、現在の設定では
最大30日残り得る。Diagnostic Settingの追加・削除は通常のPR、environment別plan、staging deploy経路で
行い、Portalや直接の`terraform apply`では変更しない。

## 作成・削除と復旧

登録はBlobを上書き禁止で保存してから、DB transaction内で申請をlockし、状態、件数、合計サイズを
再確認してメタデータと監査をcommitする。DB commitに失敗した場合は保存済みBlobをbest-effortで
削除する。補償削除にも失敗した場合は申請ID、添付ID、例外型だけを構造化ログへ記録し、内容や
credentialを記録しない。

Blobの登録、読込、削除で障害が発生した場合は、運用ログへ`event`、操作種別、申請ID、
添付ID、直接例外型、根本例外型、HTTP status、Storage error code、`x-ms-request-id`だけを
構造化記録する。取得できない値はnullとし、SDKの例外message・stack trace・Blob URLや
object名・ファイル名・メタデータ・HTTP response body・credentialは出力しない。APIと監査の
既存エラーコードは変更しない。

削除は申請と添付をlockし、DBの論理削除と成功監査を同じtransactionで先にcommitしてからBlobを
best-effortで削除する。DB更新またはcommitに失敗した場合はBlob削除を開始せず、有効なmetadataと
Blobを維持する。Blob削除に失敗してもAPIは204を返し、論理削除済み添付は一覧、content取得、再削除で
404として扱う。申請ID、添付ID、object名、例外型、再試行が必要であることを運用ログへ記録し、
失敗監査も追加するが、credential、connection string、SDKの生例外messageは記録しない。

object名は`expense-evidence/{applicationId}/{attachmentId}`で再利用しない。削除失敗で残るorphan Blobは
private container内に残り、APIからは参照できない。現時点では自動再試行を持たず、将来の定期cleanup
または削除queueで回収できる。soft deleteからの復元も自動化していないため、必要時は対象環境、
添付ID、監査ログを特定し、Azure権限を持つ運用者が承認済み手順で行う。Blob soft deleteは誤削除
対策であり、PostgreSQLとの分散transactionを提供するものではない。

## 検証

```bash
make test SUITES=backend,frontend,e2e
make verify-infra
make verify
```

`make verify-infra`はTerraform fmt/validateに加え、非公開container、shared key無効、30日soft delete、
Backend専用identity/RBAC、connection string・keyの非設定を静的確認する。E2EはBFFとBackendを通して
実際のAzuriteへPDF/PNGを保存・取得し、Azuriteへ直接接続しない。
