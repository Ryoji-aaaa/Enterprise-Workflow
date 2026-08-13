# 経費精算申請PoC

## 対象と境界

会食費、交通費、研修費、資格受験費、その他経費について、下書き作成、編集、申請、
一覧・詳細、承認、差戻し、再申請、承認前の取下げを提供する。BrowserはNext.js BFFだけを
呼び、Spring BootだけがPostgreSQLと証憑Blob Storageへ接続する。領収書・証憑の添付と
Document Analysis `AUTO_ENTRY`からの下書き確定を提供し、汎用OCR、外貨換算、正式申請への税・インボイス詳細保存、
会計・支払連携、金額別追加承認、承認済み取消はPoC対象外である。

## データモデル

V009は`expense_applications`、`expense_application_items`、`expense_approval_runs`、
`expense_approval_steps`、`expense_approval_candidates`と申請番号用sequenceを追加する。
申請番号は`EXP-YYYYMMDD-000001`形式で、明細合計をBackendが再計算する。通貨はJPYだけを
許可する。各明細と明細合計は1円以上999,999,999,999円以下の整数とし、合計超過は
`EXPENSE_APPLICATION_TOTAL_AMOUNT_EXCEEDED`の422業務エラーとして保存前に拒否する。

V010は`expense_application_attachments`を追加する。PostgreSQLには元ファイル名、正規化した
Content-Type、サイズ、SHA-256、Blob object名、登録者、論理削除情報だけを保存し、ファイル本体は
Blob Storageへ保存する。1申請につき有効な添付は10件、合計30 MiB、1ファイル10 MiBまでで、
PDF、JPEG、PNGだけを許可する。拡張子、申告Content-Type、magic numberを一致させ、空ファイル、
制御文字やpath separatorを含むファイル名、255文字を超えるファイル名を拒否する。

V017は`expense_application_auto_entry_contexts`を追加する。1つの経費申請、1つのDocument
Analysis Job、原本文書から複製した1つの経費添付にそれぞれ最大1行だけ対応させる。
`analysis_id`の一意制約を最終的な冪等性境界とし、同じAUTO_ENTRY分析から複数の経費下書きを
作成しない。Backendで生成した`AutoEntryReviewResponse`のsnapshotと、人間の現在値・確認状態を
別々のJSONBへ保存する。Azure Raw response、Blob URL、credentialは保存しない。

V018は`expense_application_attachments (id, expense_application_id)`へ一意制約を追加し、
AUTO_ENTRY contextの`(source_attachment_id, expense_application_id)`から複合外部キーで参照する。
これにより原本添付が同じ経費申請に属することをDBでも保証し、異なる申請の添付をcontextへ対応付けない。

Runは申請・再申請ごとに作成し、Stepは`DEPARTMENT_MANAGER`と`ACCOUNTING`、Candidateは
そのStepを処理できるユーザーを表す。再申請時も旧Run・Step・Candidateを更新せず、新しい
Runを追加する。Application、Run、Stepはversionを持ち、承認時にはStepとApplicationを
悲観ロックしてCandidateの最初の1名だけが確定できる。

## 状態遷移

```text
DRAFT -> PENDING_APPROVAL -> APPROVED
                         \-> RETURNED -> PENDING_APPROVAL（新Run）
                         \-> CANCELLED（承認済みStepがない場合だけ）
```

最初のStepは`PENDING`、後続は`WAITING`で作成する。現在Stepの承認後に次を`PENDING`へ
変更し、最後のStepが承認されたときだけApplicationとRunを`APPROVED`にする。差戻しでは
現在Stepを`RETURNED`、後続を`CANCELLED`にし、理由を必須保存する。

## 承認経路

基準時点の有効な`PRIMARY`所属を起点とし、親方向で最初の`DIVISION`を事業部とする。
部門長は有効な所属・ユーザー・役職のうち`positions.approval_level > 0`で判定し、ACTINGを
含む。同じ組織に複数候補がいる場合は全員をCandidateへ保存し、誰か1名の処理でStepを完了する。

- 一般ユーザー: 主所属部門の部門長、経理課
- 非DIVISIONの部門長: 親方向で最初に候補がいる組織の部門長、経理課
- DIVISIONの部門長: 経理課のみ

親探索は申請者の事業部を越えない。経理課は同じ法人の
`organization_units.unit_code = 'ACCOUNTING_SECTION'`で特定し、有効な所属ユーザー全員を
候補とする。ただし、全Stepで申請時点に有効な
`EXPENSE_APPLICATION_APPROVE`を持たないユーザーと申請者本人を候補から除外する。
候補が0人、主所属・事業部・経理課がない
場合は422で申請全体をロールバックする。

## スナップショットと認可

申請時に申請者の所属・役職・事業部をRunのJSONへ、組織名をApplication/Stepへ、候補者ID・
表示名・email・所属ID・役職名をCandidateへ保存する。承認時の正本は現在組織ではなくCandidate
であるため、その後の異動で進行中・完了済み経路は変わらない。

`EXPENSE_APPLICATION_CREATE`と`EXPENSE_APPLICATION_READ_OWN`は`APPLICATION_USER`、
`EXPENSE_APPLICATION_APPROVE`は`WORKFLOW_APPROVER`へ割り当てる。承認にはDB Permissionと
Candidate登録の両方を要求し、自己承認を拒否する。Keycloak Roleは使用しない。

AUTO_ENTRYの認可境界は次のとおりである。新しいAUTO_ENTRY専用Permissionは設けない。

| 操作 | 必須Permission | 所有・状態条件 |
| --- | --- | --- |
| 補助入力画面 | `EXPENSE_APPLICATION_CREATE`、`DOCUMENT_ANALYSIS_READ_OWN`、`CONTENT_UNDERSTANDING_ANALYZE` | Frontendの可用性制御。Backendが各操作を再認可する |
| Content Understanding分析作成 | `CONTENT_UNDERSTANDING_ANALYZE` | 認証済みユーザー |
| Formal Handoff | `EXPENSE_APPLICATION_CREATE`、`DOCUMENT_ANALYSIS_READ_OWN` | 本人所有の完了済みAUTO_ENTRY分析。`CONTENT_UNDERSTANDING_ANALYZE`は要求しない |
| 保存済みAUTO_ENTRY GET | `EXPENSE_APPLICATION_READ_OWN` | 申請者本人。分析Permissionは要求しない |
| 保存済みAUTO_ENTRY PUT | `EXPENSE_APPLICATION_CREATE` | 申請者本人、`DRAFT`または`RETURNED`、両version一致 |
| 原本添付一覧・content | `EXPENSE_APPLICATION_READ_OWN`または`EXPENSE_APPLICATION_APPROVE` | 申請者本人または現在RunのCandidate |
| 添付追加・削除 | `EXPENSE_APPLICATION_CREATE` | 申請者本人、`DRAFT`または`RETURNED`。原本添付は削除不可 |
| 申請・再申請 | `EXPENSE_APPLICATION_CREATE` | 申請者本人、対応する`DRAFT`または`RETURNED` |
| 承認・差戻し | `EXPENSE_APPLICATION_APPROVE` | 現在Candidateかつ自己承認ではない |

正式な経費証憑とAI原値・人間確認状態は開示境界が異なる。現在Candidateは原本添付を閲覧できるが、
申請者本人でないCandidateへ`auto-entry-draft` contextを返さない。過去RunだけのCandidateも閲覧できない。

添付の追加・削除は申請者本人かつ`DRAFT`または`RETURNED`の場合だけ許可する。閲覧は申請者本人と
現在RunのCandidateだけに許可し、Candidate外には申請の存在を開示しない。Frontendの表示制御に
依存せず、一覧、content取得、追加、削除の各Backend APIで同じ条件を検証する。

## API

```text
POST /api/expense-applications
GET /api/expense-applications
GET/PUT /api/expense-applications/{id}
POST /api/expense-applications/{id}/submit|resubmit|cancel
GET /api/expense-approvals/pending
POST /api/expense-approvals/{stepId}/approve|return
GET/POST /api/expense-applications/{id}/attachments
GET /api/expense-applications/{id}/attachments/{attachmentId}/content
DELETE /api/expense-applications/{id}/attachments/{attachmentId}
POST /api/expense-applications/from-auto-entry
GET/PUT /api/expense-applications/{id}/auto-entry-draft
```

一覧は`page`、`size`と任意の`status`を受け取る。他人の詳細は最新RunのCandidateに
限って参照でき、過去RunだけのCandidateには開示しない。通知は最初・次の候補、最終承認・差戻し時の
申請者について、業務transaction内で宛先ごとのOutbox行を作る。ローカルDispatcherのメール失敗は
再試行し、業務transactionをロールバックしない。Azureと`disabled` modeではOutbox行を作らない。

添付APIはBlob URL、SAS、接続文字列、object名、SHA-256をBrowserへ返さない。content取得だけが
BackendでBlob streamを開き、`Content-Type`、UTF-8の`Content-Disposition`、`Content-Length`、
`Cache-Control: private, no-store`、`X-Content-Type-Options: nosniff`を設定してBFFへ返す。
`download=true`の場合だけ`attachment`、それ以外は`inline`とする。

BlobとPostgreSQLは分散transactionではない。登録ではBlobを上書き禁止で先に保存し、申請lock後に
上限を再確認してメタデータと監査をcommitする。DB保存またはcommit失敗時はBlobをbest-effortで
削除する。削除では申請と添付をlockし、DBの論理削除と監査を同じtransactionで先にcommitした後、
Blobをbest-effortで削除する。Blob削除に失敗してもAPIは204を返し、論理削除済み添付は一覧、content
取得、再削除から除外する。残ったorphan Blobは運用ログと失敗監査で追跡し、将来の定期cleanupまたは
削除queueの対象とする。Azure Blob soft deleteは誤削除からの復旧手段であり、DBとの分散transactionを
提供するものではない。

## AUTO_ENTRY Formal Handoff

`POST /api/expense-applications/from-auto-entry`は、本人の`AUTO_ENTRY + CONTENT_UNDERSTANDING +
SUCCEEDED` Jobを正式な`ExpenseApplication DRAFT`へ確定する。`EXPENSE_APPLICATION_CREATE`と
`DOCUMENT_ANALYSIS_READ_OWN`の両方を要求する。Browserから受け取るのは現在の業務入力値、
AI明細との対応を示す`sourceLineItemIndex`、確認済みfield pathだけであり、AI原値、confidence、
status、findings、sourcesは受け取らない。Service層から`AutoEntryReviewService`を直接呼び、
Backend Reviewをsnapshotとして保存する。

対応するfield pathは次に限定する。

```text
document.issuerName
document.issuerTaxRegistrationNumber
document.totalAmount
document.lineItems[n].itemDescription
document.lineItems[n].lineAmount
```

`sourceLineItemIndex`はnullなら人が追加した明細、0以上ならReview上の明細indexである。負数、存在しない
index、重複index、未対応field pathは`400 EXPENSE_AUTO_ENTRY_SOURCE_MAPPING_INVALID`で拒否する。
文字列は前後空白を除去し、空文字をnullとして比較する。金額は`BigDecimal.compareTo`でscaleに依存せず
比較する。

人間の状態はAI Review statusと分離し、Backendが次のように決定する。

| 状態 | 判定 |
| --- | --- |
| `NOT_REQUIRED` | AI statusが`OK`で値が同じ |
| `UNRESOLVED` | `REVIEW`で値が同じかつ未確認、または`MISSING`の必須対象が未入力 |
| `CONFIRMED` | `REVIEW`で値が同じかつ確認済み |
| `EDITED` | AI原値と人の現在値が異なる、または対応するAI明細を人が削除した |

AI原値は人の値で上書きしない。`UNRESOLVED`は注意表示用であり、既存submit APIの追加gateにはしない。
Expenseの正式金額は従来どおり明細合計であり、AIの`taxAmount`は請求書照合用のread-only値としてだけ扱う。
正式申請金額や明細へ税額を自動追加せず、human-editable fieldや`confirmedFieldPaths`にも追加しない。

請求書総額の照合候補は、現在の正式申請明細合計、その合計へ安全に算出できた
`adjustments[].normalizedSignedAmount`を加えた額、AI `taxAmount`を加えた額、税額と調整額の両方を加えた額である。
Adjustmentは`normalizedSignedAmount.value`が存在し、かつ`normalizedSignedAmount.status=OK`の場合だけ安全とする。
符号が不明で`REVIEW`となったAdjustmentは、値が存在してもAdjustment合計とそれを使う候補を構成しない。
ただし、そのAdjustmentを使用しない別の安全な候補が一致すれば一致とする。
`taxMode`は候補の優先度にだけ使用し、税込・税抜のhard switchとして候補を除外しない。いずれかの候補と
請求書総額の差がinclusive ±1円以内なら一致とする。taxまたはadjustmentがmissingの場合は0へ補完しない。
有効候補が一致せず、未取得値によって別候補を作れる可能性が残る場合は照合不能とし、mismatch warningを返さない。
必要値が揃い全候補が不一致の場合だけ`INVOICE_TOTAL_DIFFERS_FROM_DRAFT_TOTAL`をnon-blocking warningとして返す。
このwarningは消費税・調整額を含む安全な照合候補を評価しても請求書総額と一致しなかったことを表し、
照合不能とともに申請をblockせず、明細や請求書総額を自動補正しない。最終的な申請値は人が確認・編集する。
Reviewの通貨が明示的にJPY以外なら`422 EXPENSE_AUTO_ENTRY_CURRENCY_UNSUPPORTED`とし、換算しない。
通貨や税率のmissing値も推測補完しない。

POSTは`analysisId`を冪等性keyとして扱う。既存contextが本人に存在すれば新しい申請・添付を作らず、
既存draftを返す。同時要求が`analysis_id`一意制約で競合した場合は、loser側のBlobを削除してwinnerを
返す。競合した要求のpayloadが異なってもwinnerを更新しない。原本文書はBrowserに再uploadさせず、BackendがDocument Analysis input Blobを読み、SHA-256、
Content-Type、sizeを維持して`expense-evidence/{applicationId}/{attachmentId}`へ保存する。

Blob読込・書込中にDB transactionを保持しない。target Blobを先に保存し、経費申請、明細、添付metadata、
AUTO_ENTRY context、成功監査を短い同一transactionでcommitする。DB失敗時はtarget Blobをbest-effortで
削除する。補償削除にも失敗した場合は元の業務エラーを維持し、固定理由コードを持つ独立した失敗監査で
orphan Blobの回収が必要なことを追跡する。contextが参照する原本添付は論理削除できず、添付一覧でも
その原本だけ`deletable=false`を返す。

`GET /api/expense-applications/{id}/auto-entry-draft`は申請者本人かつ
`EXPENSE_APPLICATION_READ_OWN`だけに、正式draft、対応するAI原値、現在値、人間状態、warning、添付ID、
application/context versionを返す。`PUT`は申請者本人、`DRAFT`または`RETURNED`、
`EXPENSE_APPLICATION_CREATE`を要求し、両versionのどちらかが古ければ
`409 OPTIMISTIC_LOCK_CONFLICT`にする。経費内容・明細とhuman review stateは同じtransactionで更新する。
GETとPUTではcontextの原本添付が同じ申請に属し、論理削除されていないことも検証する。不整合時は
`500 EXPENSE_AUTO_ENTRY_SOURCE_INTEGRITY_FAILURE`として安全に失敗する。AUTO_ENTRY contextを持つ申請を
通常PUTで更新することは拒否し、専用PUTでprovenanceとの整合を維持する。

## 監査

作成、更新、申請、再申請、取下げ、Step承認、差戻し、最終承認を`audit_logs`へ追記する。
成功した状態変更と監査は同じtransactionで保存する。申請ID・番号、Run番号、Step ID・種別、
状態前後、差戻し理由の必要最小限だけを記録し、token、Cookie、認証ヘッダーは保存しない。
Candidate外・自己承認・所有者外の参照または更新は、既存の拒否監査方針に従って別transactionで
`DENIED`を記録する。

添付では登録、content取得、削除、認可拒否、形式・上限拒否、Blob障害を既存`audit_logs`へ記録する。
申請ID、添付ID、元ファイル名、Content-Type、サイズ、SHA-256と理由コードを必要最小限として扱い、
ファイル内容、credential、接続文字列、SAS、SDKの生例外メッセージは記録しない。

Formal Handoffでは既存の`EXPENSE_APPLICATION_CREATED`、`EXPENSE_APPLICATION_UPDATED`、
`EXPENSE_ATTACHMENT_UPLOADED`に加え、`EXPENSE_AUTO_ENTRY_DRAFT_CREATED`と
`EXPENSE_AUTO_ENTRY_DRAFT_UPDATED`を記録する。追加監査はapplication ID、analysis ID、AUTO_ENTRY
schema version、source attachment ID、unresolved件数だけをallowlistで保存し、請求書field値とReview
snapshotを複製しない。Document Analysis原本の取得成功監査も、Formal Handoffのwinner transactionだけで
1件記録する。順次・同時の冪等再試行は新たな作成成功監査として記録しない。

AUTO_ENTRY申請・再申請の`after_data`には通常の申請番号、状態、Run番号に加えて、`autoEntry=true`、
未解決件数、AUTO_ENTRY schema versionだけを含める。発行元、明細、AI原値、request JSONは含めない。
原本添付の削除拒否は`EXPENSE_ATTACHMENT_DELETE_DENIED`と固定理由
`EXPENSE_AUTO_ENTRY_SOURCE_ATTACHMENT_REQUIRED`で記録する。補償削除失敗は
`EXPENSE_ATTACHMENT_STORAGE_FAILED`の`FAILURE`として固定理由だけを保存し、object名、Blob URL、
credential、SDKの生例外メッセージは保存しない。
