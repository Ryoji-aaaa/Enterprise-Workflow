# ADR-0017: Document AnalysisをBFF境界と非同期Jobで実装する

- Status: Accepted
- Date: 2026-08-08
- Related files: `backend/src/main/java/jp/co/sdcj/workflow/service/documentanalysis/`,
  `frontend/src/components/document-analysis/`,
  `infra/modules/environment-stack/main.tf`

## Context

Document IntelligenceとContent Understandingを使う文書分析では、BrowserへAzure endpoint、Blob URL、
SAS、access token、API keyを渡さず、既存のNext.js BFFとSpring Boot Backendの境界を維持する必要がある。
分析は外部ProviderのLROを伴い、HTTP request内で完了を保証しづらい。また、Providerが要求を受理した後に
timeoutまたはnetwork failureが起きると、同じ文書を自動再送することが重複処理につながる可能性がある。

## Decision

BrowserはNext.js BFFの`/api/backend/document-analyses...`だけを呼び、Spring Boot、Blob Storage、
Azure AIへ直接接続しない。Spring BootはPostgreSQLの`document_analysis_jobs`へProvider-neutralな
非同期Job metadataを保存し、文書本体、Raw result、Normalized viewはDocument Analysis専用Blob Storageへ
保存する。

WorkerはSpring Boot process内で動作し、`FOR UPDATE SKIP LOCKED`で保持期限内の`QUEUED` Jobをclaimする。
Provider呼び出しとBlob I/O中にDB transactionを保持しない。Fake、Document Intelligence、Content
UnderstandingはProvider adapterとして分離し、Blob保存前に共通のresult contract validationを行う。
staleな`RUNNING`やProvider状態不明、result contract failureは`FAILED_RECOVERY_REQUIRED`へ遷移し、
自動retry、自動resume、manual retry API、repair screenは実装しない。

Azure modeではDocument Analysis専用のAI User Assigned Managed IdentityとStorage User Assigned Managed
Identityを使う。Document Intelligence、Foundry、Document Analysis StorageはPrivate EndpointとPrivate
DNSでBackendからだけ到達させ、local auth、shared key、public network accessを無効にする。Azure Providerの
runtime controlはstaging検証が終わるまで無効にし、正式提供後もoperational kill switchとして維持する。
Frontendの公開可否には使用しない。

## Rationale

BFF境界とBackend専用Blob Storageにより、Browserへcredentialや直接接続先を公開せずにpreviewと結果取得を
提供できる。PostgreSQL Jobはowner scope、status、attempt、lease、保持期限を一元管理でき、Provider
adapterはFake E2EとAzure smokeを同じ業務contractで扱える。状態不明時に自動再送しない方針は、Provider側で
処理済みの可能性がある文書の重複分析を避ける。

## Alternatives considered

- BrowserからAzure BlobまたはAzure AIへ直接接続する
- Spring Boot request内で同期的にProvider完了まで待つ
- Service Busや専用worker Container Appを追加する
- Provider operation IDから自動resumeまたは自動retryする
- Raw JSONやMarkdownをPostgreSQLへ保存する

## Consequences

Spring Boot process停止中は新規Jobの処理が進まない。process再起動後、lease切れの`RUNNING`は
`FAILED_RECOVERY_REQUIRED`になり、人手確認対象として残る。Provider operation IDは保存するが、
現在のcontractではresume APIを提供しない。

Blob cleanupはapplication retention cleanerが担当し、Blob削除成功後だけJobを`EXPIRED`へ更新する。
PostgreSQL metadataは削除しないため監査・調査には使える一方、Job rowの保持期間や運用上の集計方針は
将来別途決める必要がある。
