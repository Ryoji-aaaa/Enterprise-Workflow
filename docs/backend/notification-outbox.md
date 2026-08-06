# ローカルメール通知Outbox

## 適用範囲

メール配送はローカル開発環境だけで使用する。`workflow.notification.delivery-mode`の既定値は
`disabled`であり、`local-mailpit`を明示した場合だけSMTP、Dispatcher、Outbox publisher、
メール通知履歴APIを登録する。stagingとproductionでは`disabled`を維持し、Mailpit、SMTP設定、
メール配送、履歴APIを提供しない。

## Transactional Outbox

V011で`notification_outbox`を追加し、通知種別、発生元、申請・承認ID、宛先snapshot、件名、本文、
重複排除key、状態、試行回数、次回試行日時、送付日時とsanitized errorを保存する。業務処理は同じ
transaction内でOutbox行を作成するため、業務更新だけがcommitされて通知要求が失われる状態を防ぐ。
`disabled`ではNo-op publisherを使用し、Outbox行も作成しない。

状態は`PENDING -> PROCESSING -> SENT`を基本とする。SMTP失敗時は設定済みの待機時間で
`RETRY_WAIT`へ移し、5回目の失敗で`FAILED`とする。処理中のままtimeoutした行は
`PROCESSING_TIMEOUT`として再試行へ戻す。DispatcherはPostgreSQLの
`FOR UPDATE SKIP LOCKED`でbatchを取得し、多重送信を抑制する。deliveryはat-least-onceであり、
重複排除keyは同一業務event・宛先のOutbox二重登録を防ぐ。

V012は既存`access_requests.notification_sent_at`を
`notification_queued_at`へbackfillする。利用申請のcooldownは配送完了時刻ではなくqueue時刻で判定し、
Dispatcherが送付成功した場合だけ互換列`notification_sent_at`を更新する。

## 通知対象

- 未登録ユーザーの利用申請: DB Permissionで管理対象となる有効ユーザーごと
- 経費申請: 最初および次の`PENDING` StepのCandidateごと
- 最終承認・差戻し: 申請者

Candidateが複数いる場合は宛先ごとにOutbox行を作る。承認、差戻し、利用申請記録がrollbackした場合は
対応するOutbox行もrollbackする。

## 履歴APIと認可

`local-mailpit`の場合だけ次を登録する。

```text
GET /api/admin/mail-notifications
GET /api/admin/mail-notifications/{notificationId}
```

DB Permission `MAIL_NOTIFICATION_READ`を必須とし、V013で`SYSTEM_ADMIN`へ割り当てる。一覧は状態、
通知種別、宛先email、申請ID・番号、期間、page、sizeで検索できる。既定は50件で、送付日時、作成日時、
IDの降順である。本文とerror詳細は詳細APIだけが返す。不明IDは404、権限なしは403、未認証は401とする。
一覧と詳細の成功読取はそれぞれ`MAIL_NOTIFICATION_HISTORY_READ`、
`MAIL_NOTIFICATION_DETAIL_READ`として監査する。SMTPの生例外、credential、tokenは保存・返却しない。

## 設定安全性

`local-mailpit`はdevelopment profile、loopbackまたはCompose内の`mailpit` host、認証なし、
STARTTLSなしをすべて満たす場合だけ起動する。条件外はstartupを失敗させる。Azure用Terraformと
GitHub ActionsにはSMTP変数を持たせず、静的境界検査で再混入を検知する。
