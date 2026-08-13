# ADR-0018: AUTO_ENTRYから経費下書きへの正式引継ぎを永続化する

- Status: Accepted
- Date: 2026-08-13
- Related files: `backend/src/main/java/jp/co/sdcj/workflow/service/ExpenseAutoEntryDraftService.java`,
  `backend/src/main/resources/db/migration/V017__create_expense_auto_entry_context.sql`

## Context

Document Analysis `AUTO_ENTRY`のReviewはAI抽出結果をBackendで正規化し、confidence、finding、sourceと
ともに返す。一方、Normalized result Blobと原本文書には既定7日のretentionがあり、経費申請の保持期間より
短い。人がAI値を確認・修正した後も、AI原値と人の判断を区別して説明できなければならない。

また、経費申請の正式金額は既存domainでは明細合計であり、請求書総額とは一致しない場合がある。
Blob StorageとPostgreSQLは分散transactionを提供せず、同じ分析への通信retryで複数draftや複数添付を
作らない仕組みも必要である。

## Decision

`POST /api/expense-applications/from-auto-entry`を正式引継ぎ境界とする。Backendは`analysisId`から
`AutoEntryReviewService`を直接呼び、BrowserからAI原値やmetadataを受け取らない。Handoff時点の
`AutoEntryReviewResponse`全体を`review_snapshot`へ保存し、人の現在値とBackend算出の
`NOT_REQUIRED`、`UNRESOLVED`、`CONFIRMED`、`EDITED`を`human_review_state`へ別保存する。

経費申請の正本は既存どおりExpense itemsとし、請求書総額はprovenanceと比較にだけ使う。差額は
非blocking warningであり、明細の自動補正やsubmit禁止を行わない。AIのmissing値も推測補完しない。

`analysis_id`のDB一意制約を冪等性の最終防衛線とする。同じ分析へのretryでは既存draftを返し、同時競合の
loserは先行保存したBlobを削除してwinnerを返す。原本文書はBackendがDocument Analysis input Blobから
経費証憑Blobへcopyし、BrowserへBlob URL、SAS、credentialを渡さない。

Blob I/O中にDB transactionを保持しない。経費Blobを先に保存し、Expense Application、items、attachment
metadata、AUTO_ENTRY context、成功監査を短い同一transactionでcommitする。DB失敗時はBlobを
best-effortで削除する。更新はapplication/context両versionを要求し、経費内容とhuman review stateを
同一transactionで更新する。

## Rationale

Backend Review snapshotによりDocument Analysis Blob cleanup後もAI原値と判断根拠を保持できる。
AI原値と人の現在値を分離すると、編集の有無をBrowser申告ではなくBackend比較で決定できる。
既存Expense validationと添付storage contractを再利用することで、通常作成・申請・承認の正本と
network/security境界を変更せずに正式引継ぎを追加できる。

## Alternatives considered

- BrowserからReview JSON、confidence、statusをPOSTして保存する
- Document Analysis result Blobを経費申請期間中ずっと保持する
- 請求書総額でExpense totalまたは明細を上書きする
- Browserに原本文書を再uploadさせる、またはSASで直接copyさせる
- Blob I/O全体をDB transaction内で行う

## Consequences

Review snapshotとhuman review stateのschema versionを後方互換に管理する必要がある。Blob保存後かつDB
commit前のprocess停止ではorphan Blobが残り得るため、補償失敗を運用ログで追跡し、将来のcleanup対象に
できるようにする。`UNRESOLVED`は業務warningであり、既存submitをblockしない。
