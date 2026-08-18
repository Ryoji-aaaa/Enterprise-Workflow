# ADR-0019: 版管理された汎用ワークフローエンジンを採用する

- Status: Accepted
- Date: 2026-08-18
- Supersedes: [ADR-0014](ADR-0014-expense-approval-route-resolution.md)

## Context

経費専用のRun、Step、CandidateとJavaで固定した経路は監査可能なsnapshotを実現した一方、業務追加や
経路変更ごとに専用schema、service、API、画面を増やす必要があった。今後の申請業務でも、版ごとの経路、
条件分岐、組織からの担当者解決、候補者snapshot、同時実行制御を共通利用する必要がある。

## Decision

ワークフロー定義、定義版、node、transition、assignee ruleをPostgreSQLで版管理し、申請時に公開版から
実行計画を生成する。条件は型付きJSON DSLに限定し、担当者解決はBackend registryに登録したresolverだけを
許可する。Instance、Step、Candidate、Actionへ定義と解決結果をsnapshotし、進行中の経路を現在マスタから
再計算しない。

業務固有処理はcontext、lifecycle、summary、accessのhandlerとしてエンジン境界へ接続する。認証は
Keycloak、業務認可はPostgreSQL Permissionを正本とし、Candidate snapshotだけで操作を許可せず、操作時の
現在Permissionも再確認する。経費精算をversion 1の最初の定義として移行し、旧経費専用実行テーブルとAPIを
削除する。

## Rationale

定義版と実行snapshotを分離すれば、新版公開が進行中Instanceを変えず、監査時に判断根拠を再現できる。
DSLとresolver allowlistは任意コード実行を避けつつ、組織・金額などの分岐を拡張できる。業務状態変更と
Action、監査、通知を同じtransactionに置くことで、汎用化後も既存の整合性方針を維持できる。

## Alternatives considered

- 経費専用実装を複製し、業務ごとに個別の承認テーブルとserviceを作る
- BPMN製品または外部ワークフローサービスを導入する
- 条件や担当者式として任意のSpELまたはscriptを保存する
- 操作時に現在組織から経路と候補者を毎回再計算する

## Consequences

定義の構造検証、resolver互換性、snapshot schemaの運用責任が増える。公開済み版は不変として扱い、変更は
新versionで行う必要がある。V019は旧PoCの経費承認実行データを移行せず削除するため、共有環境への適用前に
対象データが不要であることを確認し、必要ならbackupを取得する。定義管理UI、並列承認、委任、期限・督促は
今回の対象外である。
