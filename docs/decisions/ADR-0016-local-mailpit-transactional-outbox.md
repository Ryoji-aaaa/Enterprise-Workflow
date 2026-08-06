# ADR-0016: メール配送をローカルMailpitとTransactional Outboxへ限定する

- Status: Accepted
- Date: 2026-08-06
- Related files: `backend/src/main/resources/db/migration/V011__create_notification_outbox.sql`,
  `backend/src/main/java/jp/co/sdcj/workflow/service/notification/`,
  `infra/modules/environment-stack/main.tf`

## Context

経費承認と未登録ユーザーの通知は、業務transaction内からSMTPを同期実行していた。SMTP障害を
業務処理と分離できず、複数候補者を同じメールの宛先へ設定するため、宛先ごとの配送結果と
重複防止を管理できなかった。また、Azure用Terraformにも未決定のSMTP接続値が残っており、
誤設定によって外部へメールを送る余地があった。

今回のメール通知は将来の正式配送方式を決めるものではなく、ローカル開発で通知内容と非同期処理を
検証することが目的である。stagingとproductionへSMTP資格情報、メール配送サービス、Mailpit、
履歴参照機能を持ち込まない境界が必要になる。

## Decision

通知配送は既定で`disabled`とし、`local-mailpit`は次の全条件を満たす場合だけ起動する。

- deployment environmentが`development`
- SMTP hostがCompose service名の`mailpit`
- SMTP portが`1025`
- Fromドメインが`workflow.local`
- SMTP認証、STARTTLS、username、passwordが未設定

条件違反はBackend起動失敗とする。Azure TerraformとGitHub Actionsからメール接続変数を削除し、
禁止設定が再導入されていないことを`make verify-infra`で検査する。`disabled`ではNoop Publisherを
使い、Outbox行、SMTP Bean、Dispatcherを作成しない。

ローカル通知はPostgreSQLの`notification_outbox`へ宛先ごとに1行保存する。業務更新とOutbox INSERTを
同じtransactionでcommitし、Dispatcherは`FOR UPDATE SKIP LOCKED`で対象をclaimする。claim、SMTP、
完了更新は別境界にしてSMTP通信中にDB lockを保持しない。失敗は1分、5分、15分、1時間後に再試行し、
5回目の失敗を`FAILED`とする。停止中の古い`PROCESSING`は`RETRY_WAIT`へ回収する。

## Rationale

業務commitと通知依頼の原子性をDB transactionで保証しつつ、SMTP障害や待機時間を利用者の申請・承認
処理から分離できる。宛先別の行とdeduplication keyによって、他候補者へのemail露出を防ぎ、配送結果と
重複を個別に追跡できる。Mailpit以外をコードで拒否することで、環境変数の誤追加だけでは外部SMTPへ
接続できない。

## Alternatives considered

- 業務transaction内の同期SMTP送信を継続する
- application eventをcommit前に発行して直接SMTP送信する
- Azureにも仮のSMTP endpointまたはMailpitを配置する
- 汎用message brokerを今回から追加する

## Consequences

ローカルDBにOutbox行が蓄積し、Dispatcherのretryと古い処理回収を運用・テストする必要がある。
SMTP accept後かつ`SENT`更新前にプロセスが停止した場合、SMTPには厳密なexactly-once保証がないため
重複配送の可能性は残る。初期実装では手動再送・キャンセルを提供せず、誤送信を増やす操作を避ける。

Azureでは通知を登録も配送もせず、申請・承認処理だけを継続する。正式な外部配送を導入する場合は、
provider、認証、TLS、宛先domain、監視、保持、個人情報、再送操作を別ADRと実装で決定する。
