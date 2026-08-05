# 経費精算申請PoC

## 対象と境界

会食費、交通費、研修費、資格受験費、その他経費について、下書き作成、編集、申請、
一覧・詳細、承認、差戻し、再申請、承認前の取下げを提供する。BrowserはNext.js BFFだけを
呼び、Spring BootだけがPostgreSQLへ接続する。添付、OCR、外貨、税・インボイス詳細、
会計・支払連携、金額別追加承認、承認済み取消はPoC対象外である。

## データモデル

V009は`expense_applications`、`expense_application_items`、`expense_approval_runs`、
`expense_approval_steps`、`expense_approval_candidates`と申請番号用sequenceを追加する。
申請番号は`EXP-YYYYMMDD-000001`形式で、明細合計をBackendが再計算する。通貨はJPYだけを
許可する。各明細と明細合計は1円以上999,999,999,999円以下の整数とし、合計超過は
`EXPENSE_APPLICATION_TOTAL_AMOUNT_EXCEEDED`の422業務エラーとして保存前に拒否する。

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
候補とする。全Stepで申請者本人を候補から除外し、候補が0人、主所属・事業部・経理課がない
場合は422で申請全体をロールバックする。

## スナップショットと認可

申請時に申請者の所属・役職・事業部をRunのJSONへ、組織名をApplication/Stepへ、候補者ID・
表示名・email・所属ID・役職名をCandidateへ保存する。承認時の正本は現在組織ではなくCandidate
であるため、その後の異動で進行中・完了済み経路は変わらない。

`EXPENSE_APPLICATION_CREATE`と`EXPENSE_APPLICATION_READ_OWN`は`APPLICATION_USER`、
`EXPENSE_APPLICATION_APPROVE`は`WORKFLOW_APPROVER`へ割り当てる。承認にはDB Permissionと
Candidate登録の両方を要求し、自己承認を拒否する。Keycloak Roleは使用しない。

## API

```text
POST /api/expense-applications
GET /api/expense-applications
GET/PUT /api/expense-applications/{id}
POST /api/expense-applications/{id}/submit|resubmit|cancel
GET /api/expense-approvals/pending
POST /api/expense-approvals/{stepId}/approve|return
```

一覧は`page`、`size`と任意の`status`を受け取る。他人の詳細は最新RunのCandidateに
限って参照でき、過去RunだけのCandidateには開示しない。通知は最初・次の候補、最終承認・差戻し時の申請者へ送る。メール失敗は
警告ログにして業務transactionをロールバックしない。

## 監査

作成、更新、申請、再申請、取下げ、Step承認、差戻し、最終承認を`audit_logs`へ追記する。
成功した状態変更と監査は同じtransactionで保存する。申請ID・番号、Run番号、Step ID・種別、
状態前後、差戻し理由の必要最小限だけを記録し、token、Cookie、認証ヘッダーは保存しない。
Candidate外・自己承認・所有者外の参照または更新は、既存の拒否監査方針に従って別transactionで
`DENIED`を記録する。
