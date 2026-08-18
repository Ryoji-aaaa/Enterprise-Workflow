# ADR-0014: 経費承認経路を申請時に確定する

- Status: Superseded
- Superseded by: [ADR-0019](ADR-0019-generic-workflow-engine.md)

- Status: Accepted
- Date: 2026-08-02
- Related files: `backend/src/main/resources/db/migration/V009__create_expense_application_schema.sql`,
  `backend/src/main/java/jp/co/sdcj/workflow/service/ExpenseApprovalRouteResolver.java`,
  `docs/backend/expense-application.md`

## Context

経費申請の進行中や完了後に組織、所属、役職が変更されても、誰に承認を依頼し、誰が判断したかを
再現できる必要がある。承認操作のたびに現在組織から経路を再計算すると、異動や組織改編によって
進行中の承認者が突然変わり、完了済み証跡も同じ条件で説明できない。

経理課には複数の担当者が所属し、業務継続のため特定1名へ固定せず、候補の誰か1名が処理できる
必要がある。

## Decision

申請・再申請時に有効な主所属、役職、事業部、部門長、経理課を解決し、Run、Step、Candidateへ
スナップショット保存する。承認時は現在の組織マスタを再計算せず、対象RunのCandidateを正本とする。
再申請では新しいRunを作り、その時点の組織から再解決する。

同じStepの候補者はAny-one方式とし、最初の1名の承認または差戻しでStepを確定する。Stepと申請を
悲観ロックし、後続候補や同じStepの別候補による二重処理を拒否する。経理Stepも申請者本人を除外し、
候補が残らなければ申請しない。

## Rationale

申請単位の経路が組織変更から独立し、処理時点の表示名と所属を証跡として説明できる。
Candidateを保存することで、Permissionを持つだけの第三者を拒否しながら、同じ部門・経理課の
複数候補による業務継続性を確保できる。

## Alternatives considered

- 承認操作のたびに現在組織から経路と候補を再計算する
- Stepへ承認者1名だけを保存する
- Keycloak GroupまたはRoleを承認候補の正本にする
- PoC段階で全業務共通の汎用ワークフローエンジンを構築する

## Consequences

RunごとにStep・Candidate行が増え、候補者の表示名・emailを必要最小限の個人情報として保持する。
現在マスタの修正は既存Runへ自動反映されないため、誤った経路は差戻し後の再申請で再解決する。
Any-one方式では他候補が同時処理できないようロックと状態検証が必須になる。

PoCでは経費申請専用のRun・Step・Candidateとし、金額条件、代理承認、経路管理UI、複数事業部は
将来要件として分離する。
