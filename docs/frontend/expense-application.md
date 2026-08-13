# 経費精算申請PoC画面

## 画面

| URL | 用途 |
| --- | --- |
| `/expenses` | 自分の申請一覧、状態絞込み、ページング |
| `/expenses/new` | 新規申請、下書き保存、申請 |
| `/expenses/{id}` | 申請内容、明細、申請時所属、承認経路、差戻し理由 |
| `/expenses/{id}/edit` | 下書き・差戻し申請の編集と再申請 |
| `/expenses/auto-entry` | 請求書・注文書を分析して経費下書きを作成する補助入力 |
| `/expenses/auto-entry/confirm/{applicationId}` | 保存済みAUTO_ENTRY下書きの最終確認・編集・保存・申請 |
| `/approvals` | ログインユーザーが現在Candidateの承認待ち一覧 |
| `/approvals/{id}` | 承認コメント、承認、理由必須の差戻し |

`/api/me.permissions`に応じてトップの経費申請・承認待ちリンクを表示する。非表示はUI制御だけで、
Backendも各APIでDB PermissionとCandidateを検証する。

`/expenses/{id}/edit`は最初に保存済みAUTO_ENTRY contextを取得する。取得成功時は
`/expenses/auto-entry/confirm/{id}`へ置き換え遷移し、正確に
`404 EXPENSE_AUTO_ENTRY_DRAFT_NOT_FOUND`の場合だけ通常編集フォームを表示する。403、任意の404、5xxは
通常申請と推測せず安全にエラー表示する。これにより`DRAFT`と`RETURNED`のAUTO_ENTRY申請は専用PUTだけで
編集し、通常の経費PUTを使わない。

## 請求/注文書申請（自動入力）

`/expenses/auto-entry`は、請求書または注文書の値を確認しながら経費下書きを作成する業務画面である。
サイドメニューの表示と直接URLの操作可否は、`EXPENSE_APPLICATION_CREATE`、
`DOCUMENT_ANALYSIS_READ_OWN`、`CONTENT_UNDERSTANDING_ANALYZE`の3 Permissionすべてでfail closedにする。
これはUIの可用性制御であり、最終認可はBackendが行う。

PDF、JPEG、PNGを1件選択すると、選択直後にlocal object URLで`DocumentPreview`を表示し、
`CONTENT_UNDERSTANDING`の`AUTO_ENTRY`分析を自動開始する。別の分析開始操作は設けない。
`AnalysisStatus`で`QUEUED`、`RUNNING`、`SUCCEEDED`を表示し、成功後にAUTO_ENTRY Reviewを取得する。
BrowserはSpring Boot、Blob Storage、Azure AIへ直接接続しない。

Reviewから入力・編集する対象は、請求社 / 発行元、インボイス登録番号、総請求額、各明細の品名と金額だけに
限定する。`null`のAI値は空値のままにし、税率、用途、支払先その他の値を推測補完しない。AI明細は
`sourceLineItemIndex`を保持した経費明細へ1件ずつ対応付け、明細がない場合と人が追加する明細には
`sourceLineItemIndex=null`を使う。利用日を変更した場合、全経費明細の利用日も現在の利用日にする。

経費区分、件名、利用目的、利用日、備考、およびカテゴリ別必須項目は常に人が入力できる。
`REVIEW`のAI値が未変更なら「原本を確認しました」で確認でき、変更した値や人が入力した`MISSING`値は
「修正済み」と表示する。右上の「要確認のみ / すべて」はAI補助項目だけを切り替え、未解決の重要項目だけを
Attentionとして数える。AIの未確認はnon-blockingだが、件名・利用目的・明細・カテゴリ別必須項目などの
経費業務validationは「決定」をblockする。

請求書総額と経費明細合計は別の値である。差異は警告するが、自動補正や「決定」のblockはしない。
「決定」は未確認項目がある場合に最小の確認ダイアログを表示した後、
`POST /api/backend/expense-applications/from-auto-entry`を呼ぶ。payloadは現在の経費入力、文書入力、
有効な`confirmedFieldPaths`だけであり、AIのconfidence、status、findings、sources、polygon、original value、
resolutionは送らない。`201 Created`と同じanalysis IDの再試行による`200 OK`はともに成功として
`/expenses/auto-entry/confirm/{applicationId}`をtargetにする。

この画面はFormal Expense Applicationの`DRAFT`作成までを担当する。作成後は
`/expenses/auto-entry/confirm/{applicationId}`へ遷移する。

## AUTO_ENTRY確認・最終編集

`/expenses/auto-entry/confirm/{applicationId}`はReact stateやDocument Analysisの保持期限に依存せず、
`GET /api/backend/expense-applications/{applicationId}/auto-entry-draft`から保存済みのFormal Expense
下書き、AI原値snapshot、人の現在値、人間確認状態を復元する。画面利用には
`EXPENSE_APPLICATION_READ_OWN`と`EXPENSE_APPLICATION_CREATE`の両Permissionを要求し、分析用の
Permissionは要求しない。Backend認可が最終的な正本である。

原本プレビューはAUTO_ENTRY contextの`sourceAttachmentId`と既存の経費添付APIを使う。
`GET /expense-applications/{id}/attachments`で原本添付を特定し、既存`DocumentPreview`へ
`/api/backend/expense-applications/{id}/attachments/{attachmentId}/content`を渡す。Document Analysisの
source URL、BrowserのBlob URL、再アップロードは使わない。原本添付を取得できない場合はプレビューだけの
エラーを表示し、保存済みフォームは安全に表示を継続する。

AIの原値と`currentDocument`は別に保持する。初期の確認済みpathはBackend field stateが`CONFIRMED`のもの
だけを復元し、`EDITED`、`NOT_REQUIRED`、`UNRESOLVED`は確認済みにしない。編集時の表示規則、要確認のみの
filter、削除済みAI明細、`sourceLineItemIndex`、利用日変更時の明細利用日更新は補助入力画面と共通である。
AI未確認と請求書総額差異は非blockingであり、経費の必須入力・カテゴリ別必須項目・明細金額のvalidationは
保存と申請をblockする。

「下書き保存」は`PUT /api/backend/expense-applications/{id}/auto-entry-draft`だけを使用し、
`applicationVersion`と`contextVersion`、現在の業務入力、文書入力、有効な`confirmedFieldPaths`を送る。
AI metadata、原値、response専用の明細ID/display orderは送らない。成功時はBackend応答で両versionと
人間確認状態を置き換え、同じ確認画面に留まる。`409 OPTIMISTIC_LOCK_CONFLICT`は自動再試行せず、利用者が
明示的に再読み込みできる。503やtimeoutもPUTをblind retryせず、
「保存結果を確認できませんでした。最新内容を再読み込みしてください。」と表示して再読み込みを求める。

申請前に未解決項目があれば確認ダイアログを表示するが、継続できる。未保存の編集がある場合は専用PUTで
先に保存してから、`DRAFT`には既存`POST /expense-applications/{id}/submit`、`RETURNED`には既存
`POST /expense-applications/{id}/resubmit`を呼ぶ。成功時は`/expenses/{id}`へ遷移する。保存成功後に申請が
失敗しても保存済み下書きを戻さず、その状態を明示して確認画面に留まる。

作成、保存、申請、再申請にはReact state更新前にも作動する同期in-flight guardを置き、同じtickの連打を
1回へまとめる。guardは成功・失敗後に解除するが、Backendの冪等性、version、状態遷移を代替しない。
Formal Handoff POSTは`analysisId`が冪等性keyであるため、503やtimeout後も入力を保持して利用者が「決定」を
再実行できる。自動再送はしない。AUTO_ENTRY PUTは結果が不明なため自動再送せず、明示的に再読み込みする。
submit/resubmitの503または先行要求が成立した可能性のある`EXPENSE_APPLICATION_INVALID_STATUS`では、
POSTを再送せず現在申請を1回だけGETする。`PENDING_APPROVAL`、`APPROVED`、`CANCELLED`なら現在状態を正本として
詳細へ遷移し、`DRAFT`なら利用者が再試行できるエラーに留める。最初のsubmit後に`RETURNED`なら、submitは
成立後に差し戻されたものとして現在状態を正本に詳細へ遷移する。resubmit後の`RETURNED`は未実行と、
再申請成立後に新しいRunも差し戻された状態を区別できないため、再試行可能と断定せず結果不明とする。
GETも失敗した場合も結果不明と明示し、自動再申請しない。

## 入力と表示

共通項目は区分、件名、利用目的、利用日、備考、1件以上の明細である。明細は利用日、内容、
1円以上の整数金額、支払先を持つ。会食費では店舗名・参加者、交通費では交通手段・出発地・到着地、
研修費では主催者、資格受験費では試験実施団体をカテゴリ別必須項目として追加表示する。明細追加・削除の
たびに合計を再計算するが、保存金額の正本はBackendの再計算値である。

申請・再申請前に確認ダイアログを表示する。詳細画面の「領収書・証憑」ではPDF、JPEG、PNGを
追加し、ファイル名、形式、サイズ、登録者、登録日時を表示する。画像は画面内、PDFは別タブで
previewでき、すべてdownloadできる。申請者本人の`DRAFT`または`RETURNED`だけfile inputと
確認dialog付き削除ボタンを表示し、それ以外は閲覧操作だけを表示する。申請・再申請後や他人の
申請では追加・削除UIをfail closedで非表示にするが、認可の正本はBackendである。

詳細の承認候補者名は一般申請者へ列挙せず、組織名とStep種別、処理済みの場合だけ
実処理者・日時・コメントを表示する。

## BFFとエラー

Browserは`/api/backend/expense-applications...`と`/api/backend/expense-approvals...`だけを
呼ぶ。catch-all Route Handlerのmethod/path allowlistに経費APIを明示し、server-sideで
access tokenを付けてSpring Bootへ転送する。tokenをClient Componentへ渡さない。

AUTO_ENTRYのFormal Handoffでは`POST /api/backend/expense-applications/from-auto-entry`だけを30秒timeoutで
BFF allowlistへ追加する。確認画面用にはUUIDを固定した
`GET`/`PUT /expense-applications/{id}/auto-entry-draft`だけを追加する。POST/PATCH/DELETEや想定外suffixは
allowlistしない。

添付は`multipart/form-data`のboundaryを維持して転送し、11 MiBを超える既知の
`Content-Length`はbody読込み前に413で拒否する。BFFは固定UUIDを含む添付pathとmethodだけを
allowlistし、binary responseではContent-Type、Content-Length、Content-Disposition、
Cache-Control、`X-Content-Type-Options`だけをBrowserへ引き継ぐ。BrowserやNext.jsから
Azurite/Azure Blob Storageを直接呼ばず、Blob URLやSASを受け取らない。

loading、empty、403、409、422、通信失敗を区別する。承認者不足、主所属・事業部不足、
Candidate外、自己承認、差戻し理由不足、楽観ロック競合などの業務エラーコードは日本語へ
変換し、未知のコードはBackendメッセージまたは汎用メッセージで表示する。
添付についても形式不一致、1ファイル・件数・合計サイズ上限、変更不可、Storage障害を日本語で
表示する。Client側の拡張子、Content-Type、サイズ検査は早期feedbackであり、Backend検査を省略しない。
