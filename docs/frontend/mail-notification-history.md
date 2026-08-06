# メール通知履歴画面

## 対象

ローカル開発環境の管理者・開発者が、業務eventから作成されたメール通知の送付状態と本文を確認する。
一覧は`/admin/mail-notifications`、詳細は
`/admin/mail-notifications/{notificationId}`で提供する。

トップの「送付済メール一覧」は、`/api/me.permissions`に`MAIL_NOTIFICATION_READ`があり、かつ
`/api/me.features.mailNotificationHistory=true`の場合だけdesktopとmobileへ表示する。機能フラグは
環境名をBrowserへ公開せず、Backendのdelivery modeから生成する。Frontend非表示は補助制御であり、
Backendは直接URLとAPIにもDB Permissionを必須とする。

## 一覧

初期状態は`SENT`で、`PENDING`、`PROCESSING`、`RETRY_WAIT`、`FAILED`または全状態へ変更できる。
通知種別、宛先email、申請番号、開始・終了日時を組み合わせ、50件単位でページ移動する。表には送付日時、
状態、種別、宛先、件名、対象申請番号・件名、試行回数、詳細操作を表示する。0件時は空状態、取得中は
loading、権限不足は403を明示し、通信・Backend障害では内部例外を表示しない。

## 詳細

宛先、申請、状態、試行回数、作成・送付・次回試行日時、本文を表示する。失敗情報がある場合だけ
sanitized error codeとmessageを表示する。不明な通知IDは404、権限不足は403として区別する。

## BFF境界

Next.js BFF allowlistは一覧とUUID形式の詳細に対するGETだけを許可する。POST、PATCH、DELETE、
UUIDでない詳細pathは認証処理前に404で拒否する。BrowserはSpring Bootを直接呼ばず、tokenは
従来どおりRoute Handler内だけで使用する。
