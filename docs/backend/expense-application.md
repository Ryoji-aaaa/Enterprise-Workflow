# 経費精算申請PoC

## 対象と境界

会食費、交通費、研修費、資格受験費、その他経費について、下書き作成、編集、申請、
一覧・詳細、承認、差戻し、再申請、承認前の取下げを提供する。BrowserはNext.js BFFだけを
呼び、Spring BootだけがPostgreSQLと証憑Blob Storageへ接続する。領収書・証憑の添付を提供し、
OCR、外貨、税・インボイス詳細、
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
```

一覧は`page`、`size`と任意の`status`を受け取る。他人の詳細は最新RunのCandidateに
限って参照でき、過去RunだけのCandidateには開示しない。通知は最初・次の候補、最終承認・差戻し時の申請者へ送る。メール失敗は
警告ログにして業務transactionをロールバックしない。

添付APIはBlob URL、SAS、接続文字列、object名、SHA-256をBrowserへ返さない。content取得だけが
BackendでBlob streamを開き、`Content-Type`、UTF-8の`Content-Disposition`、`Content-Length`、
`Cache-Control: private, no-store`、`X-Content-Type-Options: nosniff`を設定してBFFへ返す。
`download=true`の場合だけ`attachment`、それ以外は`inline`とする。

BlobとPostgreSQLは分散transactionではない。登録ではBlobを上書き禁止で先に保存し、申請lock後に
上限を再確認してメタデータと監査をcommitする。DB保存またはcommit失敗時はBlobをbest-effortで
削除する。削除では申請と添付をlockし、Blob削除成功後だけDBの論理削除と監査を確定する。

## 監査

作成、更新、申請、再申請、取下げ、Step承認、差戻し、最終承認を`audit_logs`へ追記する。
成功した状態変更と監査は同じtransactionで保存する。申請ID・番号、Run番号、Step ID・種別、
状態前後、差戻し理由の必要最小限だけを記録し、token、Cookie、認証ヘッダーは保存しない。
Candidate外・自己承認・所有者外の参照または更新は、既存の拒否監査方針に従って別transactionで
`DENIED`を記録する。

添付では登録、content取得、削除、認可拒否、形式・上限拒否、Blob障害を既存`audit_logs`へ記録する。
申請ID、添付ID、元ファイル名、Content-Type、サイズ、SHA-256と理由コードを必要最小限として扱い、
ファイル内容、credential、接続文字列、SAS、SDKの生例外メッセージは記録しない。
