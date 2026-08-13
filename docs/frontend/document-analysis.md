# Document Analysis画面

## 概要

`/document-intelligence`と`/content-understanding`は共通の3ペインWorkbenchを使用する。
左ペインでファイル選択と直近分析を扱い、中央ペインで入力文書をpreviewし、右ペインで
Job状態と分析結果を表示する。

BrowserはSpring Boot、Blob Storage、Azure AIへ直接接続しない。すべての業務通信は
Next.js BFFの`/api/backend/document-analyses...`を経由し、BFFがSpring Bootの
`/api/document-analyses...`へ転送する。

`/content-understanding/auto-entry`はContent Understanding配下のAUTO_ENTRY専用の
読み取り専用Review画面である。既存の`/content-understanding`はGENERAL profileの技術的な
Workbenchとして維持する。AUTO_ENTRY画面はUpload、Preview、Job状態表示を既存部品から再利用するが、
結果表示はGENERALのMarkdown/Paragraphs/Tables/Raw Resultとは分離する。

## 正式機能と権限

Document IntelligenceとContent Understandingは正式機能である。メニュー表示と直接URL表示は、
`/api/backend/me`のDB Permissionだけで制御する。

| 画面 | Permission |
| --- | --- |
| Document Intelligence | `DOCUMENT_INTELLIGENCE_ANALYZE` |
| Content Understanding | `CONTENT_UNDERSTANDING_ANALYZE` |

対象Permissionが不足する場合はメニューへ表示せず、直接URLを開いてもWorkbenchを操作可能状態で
表示しない。この制御はUX目的であり、Backend APIの認可が最終防御である。Azure Providerのruntime
enablementや全体kill switchはFrontend公開判定に使用しない。
`CurrentUser.features`の仕組み自体は将来の未公開機能に備えて維持し、現在は
`mailNotificationHistory`だけを受け取る。

## BFF

汎用BFF allowlistはDocument Analysisの次のrouteだけを許可する。

| Method | BFF path | timeout | response |
| --- | --- | ---: | --- |
| `GET` | `/api/backend/document-analyses` | 5秒 | JSON |
| `POST` | `/api/backend/document-analyses` | 30秒 | JSON |
| `GET` | `/api/backend/document-analyses/{analysisId}` | 5秒 | JSON |
| `GET` | `/api/backend/document-analyses/{analysisId}/source` | 30秒 | binary |
| `GET` | `/api/backend/document-analyses/{analysisId}/view` | 15秒 | JSON |
| `GET` | `/api/backend/document-analyses/{analysisId}/raw-result` | 15秒 | JSON |
| `GET` | `/api/backend/document-analyses/{analysisId}/auto-entry-review` | 15秒 | JSON |

`auto-entry-review`は保存済みの`AUTO_ENTRY`結果をReview / Validation responseへ変換するBackend APIを
BFF経由で取得するためのrouteであり、BrowserからAzure AI、Blob Storage、Spring Bootへ直接接続しない。
`analysisId`はUUID形式だけを許可する。任意サブパス、retry、cancel、PUT、PATCH、DELETEは
許可しない。`POST`のBFF request body上限はmultipart overheadを含めて11MiBであり、
`Content-Length`と実際に読み込んだbody sizeの両方を検査する。超過時は
`413 DOCUMENT_ANALYSIS_TOO_LARGE`を返す。経費添付APIの
`EXPENSE_ATTACHMENT_TOO_LARGE`は従来どおり維持する。

multipart uploadではBrowserが生成した`Content-Type`とboundaryを維持し、Frontend codeで
`Content-Type`を手動設定しない。source取得ではBrowserの`Accept`を維持し、PDFと画像previewに
対応する。

## 実行フロー

GENERALではFrontendは`provider`と`file`を`FormData`へ入れて
`POST /api/backend/document-analyses`へ送信する。
AUTO_ENTRYでは必ず`provider=CONTENT_UNDERSTANDING`、`profile=AUTO_ENTRY`、`file`を送信する。
`modelId`、API version、analyzer ID、endpoint、credential、Blob object name、保持期限は
Browserから送信しない。

`202 Accepted`で返るJob IDを正とし、URL queryへ`analysis={UUID}`として保存する。
`QUEUED`または`RUNNING`の間だけ1秒間隔で状態をpollする。pollは前回request完了後に次回を
予約し、unmount時に中断する。`SUCCEEDED`、`FAILED`、`FAILED_RECOVERY_REQUIRED`、`EXPIRED`では
pollを停止する。

`SUCCEEDED`後に`/view`を取得し、`schemaVersion: 1`だけを表示対象にする。未知schemaは画面を
crashさせず、「対応していない分析結果形式です。」として扱う。

## 結果表示

Normalized V1の`documents[].markdown`、`documents[].paragraphs`、`documents[].tables`を
Markdown、Paragraphs、Tablesタブへ表示する。Tablesタブはstructured tablesを使用し、
Markdownを再parseしてtableを作成しない。

DesktopではFile、Preview、Resultの各ペイン、mobileでは選択中ペインがWorkbenchの固定高さ内で
個別にスクロールする。ペイン見出しとResultのタブ・Copy操作は固定し、内容だけをスクロールする。
すべての結果タブにCopyを表示する。Markdownは表示中のMarkdown、Paragraphsは
`id,role,pageNumber,confidence,content`、Tablesは全表を`tableId,column1...columnN`へ統合した
RFC 4180形式CSV、Resultは取得済みRaw Result本文をコピーする。Tablesの結合セルに覆われた箇所と
欠損セルは空欄とし、Resultが読み込み中または取得失敗時はCopyを無効化する。

Raw Resultは`SUCCEEDED`直後には取得しない。Resultタブを初めて開いた時だけ
`/raw-result`を取得し、同じanalysis IDではタブを切り替えても再取得しない。analysis IDが変わると
Raw stateをresetする。
Raw Resultの成功responseは`response.text()`で取得し、Frontendの業務objectとして保持しない。
1MiB以下のRaw JSONだけ`JSON.parse`と`JSON.stringify(..., null, 2)`で整形し、1MiBを超える場合は
parseせず全文をそのまま`<pre>`のtext nodeとして表示する。`dangerouslySetInnerHTML`は使用しない。

## Previewと復元

新規ファイル選択直後は`URL.createObjectURL(file)`でlocal previewを表示し、変更時とunmount時に
object URLをrevokeする。reload後やRecent analysesから復元したJobは
`/api/backend/document-analyses/{analysisId}/source`をpreview sourceにする。Blob Storage URLや
SASはBrowserへ渡さない。

ページ表示時はBackendからprovider別に直近10件の分析履歴を取得する。履歴を選択するとURL queryを
更新し、Job状態、server source preview、結果を復元する。URL queryのJob providerが現在の画面と
一致しない場合は、その結果を現在画面の結果として描画しない。

AUTO_ENTRY画面では履歴を
`?provider=CONTENT_UNDERSTANDING&profile=AUTO_ENTRY`に固定し、Job、source、view系の取得にも
`profile=AUTO_ENTRY`を明示する。GENERAL JobをAUTO_ENTRY画面に混在または表示しない。

## AUTO_ENTRY Review

AUTO_ENTRY Jobが`SUCCEEDED`になった後、FrontendはBFFの
`GET /api/backend/document-analyses/{analysisId}/auto-entry-review`を取得する。Summary、各fieldの
`value`、`confidence`、`status`、`sources`、`findings`、派生fieldはReview API responseを正として
そのまま表示する。Frontendは業務validation、金額再計算、税率・Category・CategoryNotationからの
推測または補完を行わない。

finding codeは表示時だけ業務利用者向けの日本語へ変換する。Frontendはfindingを追加・削除・再判定
せず、Backendが返した順序と意味を維持する。confidenceとsourceは表示専用で、それぞれ「信頼度
93.2%」「参照ページ 1, 2」の形式で表示する。confidenceからFrontend独自の`OK`、`REVIEW`、
`MISSING`判定は行わない。

値が`null`で`status=MISSING`のfieldは「未取得」と表示する。特に`TaxRatePercent=null`は、
`Category=STANDARD`/`REDUCED`や`CategoryNotation`が`10%対象額`/`軽減8%対象額`であっても、
10または8へ補完せず「未取得」のまま表示する。sourceはpage numberとして表示するだけで、polygon
overlay、編集、修正保存、経費申請への転記は提供しない。

reloadまたはRecent analysesからの復元では、分析Jobの`originalFileName`と`contentType`を使って
BFFのsource previewを表示する。Browserの`File` objectは復元しないため、「分析を実行」は無効の
ままとする。mobileではFile、Preview、Resultタブで同じ操作とReview表示を提供し、横長の明細表は
表コンテナ内を横スクロールする。

## ローカルFake Provider

ローカルComposeではBackendのDocument Analysisが`execution-mode=fake`で有効になり、
Document IntelligenceとContent Understandingの両方を処理できる。Fake Providerは外部networkへ
接続せず、Raw resultに`source=backend-fake-provider`を含む。

staging/productionでも`APPLICATION_USER`のPermissionにより両メニューを表示する。通常提供時のBackendは
`execution-mode=azure`を使用する。運用上runtimeを停止した場合も正式機能としてのメニューは維持し、
Backendの安全なエラーを表示する。
