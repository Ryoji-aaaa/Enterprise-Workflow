# ADR-0015: 経費証憑本体をBackend専用Blob Storageへ保存する

- Status: Accepted
- Date: 2026-08-06
- Related files: `infra/modules/blob-storage/`,
  `backend/src/main/java/jp/co/sdcj/workflow/storage/AttachmentStorage.java`,
  `docs/infrastructure/expense-attachment-storage.md`

## Context

経費申請へ領収書・証憑を追加するにあたり、ファイル本体の容量を業務DBから分離しながら、申請と
同じDB Permission、Candidate、監査方針でアクセスを制御する必要がある。BrowserへStorage資格情報や
一時URLを渡す方式は、既存のNext.js BFF境界とBackend認可を迂回し得る。ローカルとE2EでもAzure依存を
持たず、同じBlob APIの保存経路を検証する必要がある。

## Decision

ファイル本体をAzure Blob Storage、メタデータをPostgreSQLへ保存する。Browserからの登録・取得・削除は
Next.js BFFとSpring Bootを必ず経由し、Spring BootだけがBlobへ接続する。AzureではBackend専用User
Assigned Managed Identityに対象container scopeの`Storage Blob Data Contributor`を付与し、shared key、
connection string、SASを使わない。ローカルとE2Eではホスト非公開のAzuriteを使用する。

BlobとDBの分散transactionは導入せず、登録時のDB失敗にはBlobのbest-effort補償削除を行う。削除は
Blob成功後だけDB論理削除と監査を確定する。Blob名はUUIDで一意にし、上書き・再利用しない。

## Rationale

大きなbinaryをDB transaction、backup、query負荷から分離しつつ、既存Backendの所有者・Candidate認可と
追記専用監査を一つの入口で適用できる。Managed Identityとcontainer scope RBACにより長期credentialを
排除し、FrontendやKeycloakへの権限伝播を防げる。AzuriteはAzure Blob SDKと同じinterfaceを使うため、
BrowserからStorageを直接操作せずにローカルE2Eを再現できる。

## Alternatives considered

- PostgreSQLのbyteaへファイル本体も保存する
- Next.jsまたはBrowserへSASを払い出してBlobへ直接upload/downloadする
- Container Appのlocal filesystemへ保存する
- Azure Filesまたは別cloudのobject storageを使用する
- BlobとDB間に分散transaction相当の独自調停処理を構築する

## Consequences

BlobとDBの整合性は補償処理、監査、障害調査で維持する必要があり、完全な原子性はない。Azure環境ごとに
Storage Account、container、専用identity、RBACが増える。soft deleteは30日とするが、法定保存期間や
正式なretention policyは別途決定が必要であり、現時点ではlifecycleによる自動削除を設定しない。

ファイル内容のmalware scan、OCR、thumbnail生成、暗号化keyの顧客管理、private endpointはこの判断の
対象外である。必要になった場合もBrowserへBlob権限を移さず、Backend境界または非同期処理として追加する。
