# 汎用ワークフローエンジン

## 適用範囲

経費精算を最初の利用対象として、業務固有テーブルに承認経路を固定せず、版管理された定義から
申請時に実行計画を生成する。BrowserはNext.js BFFだけを呼び、定義、実行状態、候補者、操作履歴は
Spring BootだけがPostgreSQLへ保存する。Keycloakは認証だけを担当し、業務認可はDB Permissionで行う。

## 永続モデルと版管理

V019は旧経費専用のRun、Step、Candidateを削除し、次の汎用テーブルへ置き換える。

- `workflow_definitions`: 業務コードと対象種別を持つ定義の入口
- `workflow_definition_versions`: `DRAFT`、`PUBLISHED`、`RETIRED`の版とcontext schema
- `workflow_nodes`: `START`、`APPROVAL`、`END`のnode
- `workflow_transitions`: 条件付きの有向辺と評価順
- `workflow_assignee_rules`: APPROVAL nodeの候補者resolverと必須Permission
- `workflow_instances`: 対象、定義版、申請者context、解決結果のsnapshot
- `workflow_instance_steps`: 実行順、状態、node・担当組織・認可snapshot
- `workflow_instance_candidates`: 申請時候補者snapshot
- `workflow_instance_actions`: 申請、承認、差戻し、取下げの追記専用履歴

公開済みの定義版は実行時に更新せず、新しい要件は新しい版として追加する。Instanceは使用した
definition version IDを保持するため、定義の新版公開後も進行中・完了済み経路を再現できる。
V020は`EXPENSE_APPROVAL` version 1を`PUBLISHED`として投入する。
V021はCandidate選定時に使用したPermission scopeを`permission_scope_snapshot`へ保存し、
候補者の選定根拠である`candidate_source_snapshot`と操作時認可のscopeを分離する。

## 条件DSL

遷移条件はJSONで保存し、任意コードやSpELを評価しない。型付きcontext schemaに宣言されたfieldだけを
参照できる。比較は`EQ`、`NE`、`GT`、`GTE`、`LT`、`LTE`、`IN`、`NOT_IN`、`IS_NULL`、
`IS_NOT_NULL`、論理演算は`all`、`any`、`not`を使用する。型不一致、未宣言field、未知の演算子、
不正なJSONは定義検証または計画生成を失敗させる。
`GT`、`GTE`、`LT`、`LTE`はcontextの実値がnullなら常にfalseとし、定義側の比較値nullは
検証で拒否する。null判定には`IS_NULL`または`IS_NOT_NULL`だけを使用する。

経費精算では申請者の主所属、役職、所属長該当、親組織、事業部、法人をcontextとしてsnapshotする。
実行時の遷移は優先順に評価し、各nodeから一致する遷移がちょうど1本でなければ申請を422で拒否する。

## 定義検証と実行計画

公開定義の読込み時に、STARTとENDが各1つ、参照nodeの存在、APPROVAL nodeの担当者rule、resolver名と
parameter、条件schema、cycle、STARTからの到達可能性、ENDへの到達可能性を検証する。
計画生成は対象業務の`WorkflowContextProvider`、担当者の`WorkflowAssigneeResolver`をregistryから選び、
通過するAPPROVAL nodeと候補者をすべて解決する。候補者0人、主所属不足、必要組織不足はInstanceを
一部作成せずtransaction全体をロールバックする。

Workflow開始ごとに1つのevaluation timestampを確定し、公開版、context、経路、担当者、Permissionの
解決へ同じ時刻を渡す。経費申請の所属snapshotも同じ時刻で作る。申請者所属の`parent_unit_id`がnullの
場合だけ最上位組織として扱い、設定済みの親が不存在、別法人、無効、期間外なら
`PARENT_ORGANIZATION_UNIT_INVALID`の422で計画生成前に拒否する。

組織担当者resolverは有効期間内の所属、有効ユーザー、役職、申請時点のDB Permissionを確認し、
申請者本人を除外する。組織長resolverはさらに`positions.approval_level > 0`を要求する。
同一stepの候補者はany-one方式で、最初に確定した1人の操作だけを受理する。

## 実行時状態と同時実行

Instanceは`PENDING`、`APPROVED`、`RETURNED`、`CANCELLED`、Stepは`WAITING`、`PENDING`、
`APPROVED`、`RETURNED`、`CANCELLED`を取る。最初のStepだけを`PENDING`にし、承認ごとに次を
`PENDING`へ進める。最終Step承認でInstanceと対象業務を`APPROVED`にする。差戻しは現在Stepを
`RETURNED`、後続を`CANCELLED`にし、対象業務を`RETURNED`にする。再申請は古いInstanceを変更せず、
同じ対象へrun numberを増やした新Instanceを作る。

承認・差戻しはStepを悲観lockし、`PENDING`、Candidate、自己承認禁止、snapshotされた必須Permission、
CandidateごとにsnapshotされたglobalまたはOrganization Unit scopeでの現在DB Permissionを
同一transaction内で再確認する。現在組織からCandidateを再計算しない。二重送信や別Candidateの競合では最初の1件だけを
確定し、後続は409で拒否する。Step、Instance、対象業務の状態変更、action、監査、次候補通知を同じ
transaction境界で扱う。Actionは最低限`APPROVE`、`RETURN`、`CANCEL`を記録する。

## 業務統合

エンジン本体は対象業務の状態や表示内容を知らない。次の拡張点をregistryで解決する。

- `WorkflowContextProvider`: 対象から条件評価contextを作る
- `WorkflowSubjectLifecycleHandler`: submit、approve、return、cancelに合わせて対象状態、監査、通知を更新する
- `WorkflowSubjectSummaryProvider`: 汎用タスク一覧・詳細の件名、申請番号、金額などを返す
- `WorkflowSubjectAccessHandler`: 対象所有者または現在候補者によるtimeline参照を認可する

経費精算v1は一般社員を同一組織の所属長から経理へ、所属長を親方向で最初に見つかる所属長から経理へ、
親がない最上位所属長を経理だけへ送る。経理は同一法人の`ACCOUNTING_SECTION`所属者から解決する。
各候補者は`EXPENSE_APPLICATION_APPROVE`を申請時と操作時の両方で持つ必要がある。

## APIとFrontend境界

```text
GET  /api/workflow/tasks
GET  /api/workflow/tasks/{stepId}
POST /api/workflow/tasks/{stepId}/approve
POST /api/workflow/tasks/{stepId}/return
GET  /api/workflow/subjects/{subjectType}/{subjectId}/latest
```

APIは認証を必須とし、task操作はCandidateとstep固有Permission、latest参照は対象業務のaccess handlerで
認可する。経費API responseへworkflow内部のpending stepや候補者を混在させない。Frontendは汎用task
画面で操作し、経費詳細では独立したtimelineを表示する。access tokenは従来どおりNext.js server-side
だけが保持する。

## 定義追加手順

新しい業務では、対象種別固有のcontext schema/provider、summary/access/lifecycle handler、必要なら
assignee resolverを実装し、versioned migrationで定義版を追加する。公開前に分岐条件、経路到達性、
候補者0人、自己承認、権限失効、同時操作、差戻し・再申請をBackendテストで検証し、Frontendの表示制御と
Backendの直接API拒否をE2Eで確認する。
