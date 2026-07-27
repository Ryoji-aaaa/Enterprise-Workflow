# ADR-0007: GraphifyおよびEntireの導入保留

- Status: Temporary
- Date: 2026-07-27
- Related files: `docs/README.md`, `docs/decisions/README.md`

## Context

AIエージェントによるコード理解と作業履歴の追跡を支援する候補として、
GraphifyとEntireを検討した。2026年7月27日時点ではリポジトリが小規模で、
README、`docs/`、ADR、テスト、通常のGit履歴から構造と判断を追跡できる。

開発環境完成とCI/CD導入に向けては、DB migrationと再現可能な検証を優先する必要がある。

## Decision

2026年7月27日時点で、GraphifyおよびEntireをEnterprise-Workflowの
標準開発環境には導入しない。依存関係、設定、Git hook、生成物を追加せず、
必要な条件が整った時点でそれぞれを再評価する。

## Graphify

### 想定用途

コード、設定、SQL、文書の関係をknowledge graphとして索引化し、
AIエージェントのコードベース理解、影響範囲調査、オンボーディングを支援する。

### メリット

- ファイル、クラス、DB、文書を横断した関係を問い合わせられる可能性がある
- 大規模なコードベースで調査範囲とAIエージェントの入力を絞れる可能性がある
- 構造や依存関係を可視化し、影響範囲調査を補助できる可能性がある

### デメリット

- 現在の規模では導入効果が限定的である
- 索引の更新、解析精度の検証、利用方法の保守が必要になる
- Java、TypeScript、設定、SQLを横断した結果が実装と一致する保証はなく、
  最終的にはソースとテストによる確認が必要になる
- ツール固有の依存と、生成物またはローカル状態の管理方針が増える

### 見送り理由

現状は標準の検索、文書、ADR、テストで構造を十分追跡できる。
導入効果を測定するPoCを実施しておらず、DB migrationと環境再現性より優先度が低い。

### 再検討条件

- コードベースまたはサービス数が増え、手作業の影響範囲調査が困難になる
- 対象言語・設定・SQLに対する解析精度と更新方法をPoCで確認できる
- 索引をGit管理するかローカル生成にするか決定できる
- 保守コストを上回る時間短縮または品質向上を測定できる

## Entire

### 想定用途

AIエージェントのprompt、transcript、tool実行、変更内容をcheckpointとして
Git commitへ関連付け、変更理由の追跡、監査、作業再開を支援する。

### メリット

- commitの差分だけでは分からないAI支援作業の背景を追跡できる可能性がある
- agent sessionとコード変更を関連付け、検索・振り返りに利用できる可能性がある
- AI利用の監査要件や教育用途を支援できる可能性がある

### デメリット

- promptやtranscriptにsecret、個人情報、社内情報が含まれる可能性がある
- 保存対象、アクセス制御、保持期間、削除方法の組織ルールが必要になる
- Git hook、checkpoint用の履歴、commitとの関連付けによりGit運用が複雑になる
- リポジトリ容量とレビュー・運用負担が増える可能性がある

### 見送り理由

AI session全文を保存する要件と情報管理ルールが未整備である。
現段階ではADR、PR本文、commit messageで必要な判断を記録でき、
Git演習を含む開発環境へ追加概念を導入する優先度が低い。

### 再検討条件

- AI利用履歴に対する監査・保存要件が定義される
- 保存可能・禁止情報、secret除外、アクセス制御、保持・削除方針が決定される
- private repositoryでGit運用への影響と復旧方法をPoCで確認できる
- ADRやPR要約では必要な追跡性を満たせないと判断される

## Consequences

当面は既存の文書、ADR、PR、commit、テストを変更理由と品質の正本とする。
Graphifyによるknowledge graphとEntireによるsession checkpointは利用できないが、
追加依存、Git運用の複雑化、未整理の情報保存リスクを持ち込まない。

このADRは、いずれかの再検討条件が成立してPoCと情報管理上の判断を完了するまで有効とする。
採用または継続見送りを決定した時点で、後続ADRにより置き換える。

## References

- [Graphify: What is Graphify?](https://graphify.com/what-is-graphify)
- [Entire: Overview](https://docs.entire.io/overview)
