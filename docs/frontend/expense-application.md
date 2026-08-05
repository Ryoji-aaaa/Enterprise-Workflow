# 経費精算申請PoC画面

## 画面

| URL | 用途 |
| --- | --- |
| `/expenses` | 自分の申請一覧、状態絞込み、ページング |
| `/expenses/new` | 新規申請、下書き保存、申請 |
| `/expenses/{id}` | 申請内容、明細、申請時所属、承認経路、差戻し理由 |
| `/expenses/{id}/edit` | 下書き・差戻し申請の編集と再申請 |
| `/approvals` | ログインユーザーが現在Candidateの承認待ち一覧 |
| `/approvals/{id}` | 承認コメント、承認、理由必須の差戻し |

`/api/me.permissions`に応じてトップの経費申請・承認待ちリンクを表示する。非表示はUI制御だけで、
Backendも各APIでDB PermissionとCandidateを検証する。

## 入力と表示

共通項目は区分、件名、利用目的、利用日、備考、1件以上の明細である。明細は利用日、内容、
1円以上の整数金額、支払先を持つ。会食費では店舗名・参加者、交通費では交通手段・出発地・到着地、
研修費では主催者、資格受験費では試験実施団体をカテゴリ別必須項目として追加表示する。明細追加・削除の
たびに合計を再計算するが、保存金額の正本はBackendの再計算値である。

申請・再申請前に確認ダイアログを表示する。添付UIは持たず「領収書添付はPoC対象外です。」と
表示する。詳細の承認候補者名は一般申請者へ列挙せず、組織名とStep種別、処理済みの場合だけ
実処理者・日時・コメントを表示する。

## BFFとエラー

Browserは`/api/backend/expense-applications...`と`/api/backend/expense-approvals...`だけを
呼ぶ。catch-all Route Handlerのmethod/path allowlistに経費APIを明示し、server-sideで
access tokenを付けてSpring Bootへ転送する。tokenをClient Componentへ渡さない。

loading、empty、403、409、422、通信失敗を区別する。承認者不足、主所属・事業部不足、
Candidate外、自己承認、差戻し理由不足、楽観ロック競合などの業務エラーコードは日本語へ
変換し、未知のコードはBackendメッセージまたは汎用メッセージで表示する。
