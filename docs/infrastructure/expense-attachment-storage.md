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

## 作成・削除と復旧

登録はBlobを上書き禁止で保存してから、DB transaction内で申請をlockし、状態、件数、合計サイズを
再確認してメタデータと監査をcommitする。DB commitに失敗した場合は保存済みBlobをbest-effortで
削除する。補償削除にも失敗した場合は申請ID、添付ID、例外型だけを構造化ログへ記録し、内容や
credentialを記録しない。

削除は申請と添付をlockし、Blob削除が成功した場合だけDBを論理削除して監査を保存する。
object名は`expense-evidence/{applicationId}/{attachmentId}`で再利用しない。Blob障害時は503を返し、
DB変更を確定しない。soft deleteからの復元は自動化していないため、必要時は対象環境、添付ID、
監査ログを特定し、Azure権限を持つ運用者が承認済み手順で行う。

## 検証

```bash
make test SUITES=backend,frontend,e2e
make verify-infra
make verify
```

`make verify-infra`はTerraform fmt/validateに加え、非公開container、shared key無効、30日soft delete、
Backend専用identity/RBAC、connection string・keyの非設定を静的確認する。E2EはBFFとBackendを通して
実際のAzuriteへPDF/PNGを保存・取得し、Azuriteへ直接接続しない。
