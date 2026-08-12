# AUTO_ENTRY v2.1 acceptance fixtures

These fixtures define the normalized AUTO_ENTRY v2.1 acceptance baseline.

このディレクトリは、5件の合成・匿名化済み帳票を使って、AUTO_ENTRY Analyzer の変更を
比較・検証するための固定 fixture である。directory名の`v2.1`は
`fields.autoEntry.schemaVersion="2.1"`と対応するNormalized contract versionであり、Analyzer IDの
patch versionではない。runtime Analyzerが`enterprise_workflow_auto_entry_v2.1.1`になっても
このdirectoryはrenameせず、`enterprise_workflow_auto_entry_v2.1`でcaptureした基準結果を履歴として
保持する。contract v2.2以降を検証するときは、同じ`documents/`を入力として
v2.1の基準結果と比較する。

## 構成

| 帳票 | `documents/` | `azure-results/` | `expected/` |
| --- | --- | --- | --- |
| 請求書 01 | `invoice-01.jpg` | `invoice-01.json` | `invoice-01.expected.json` |
| 請求書 02 | `invoice-02.jpg` | `invoice-02.json` | `invoice-02.expected.json` |
| 請求書 03 | `invoice-03.jpg` | `invoice-03.json` | `invoice-03.expected.json` |
| 注文書 03 | `purchase-order-03.jpg` | `purchase-order-03.json` | `purchase-order-03.expected.json` |
| 注文請書 04 | `order-confirmation-04.jpg` | `order-confirmation-04.json` | `order-confirmation-04.expected.json` |

`azure-results/` は Azure Content Understanding の実測結果を、回帰用に縮小したものとする。
`id`、`status`、`createdAt`、一時的な `projectAnalyzer_*`、usage、Markdown、paragraph、table、
`pages.words` / `pages.lines` は保存しない。抽出値、confidence、source、spans、ページ番号と寸法、
unitは残す。`contents[].kind=document`はJava SDKのtyped `DocumentContent`として読み込むために残す。
`source` の polygon は、source parser回帰で使用する。

`expected/` は Azure 応答の完全一致を求める golden file ではない。入力帳票、縮小済み Azure
結果、Normalizer、Spring の業務レビューを通したときに維持すべき、帳票種別・税区分表記・
業務上の検出結果を記録する。Content Understanding の出力は将来変動し得るため、テストはここに
定義した受入条件を評価する。

## Fixture policy

このディレクトリには、再配布が許可された合成・匿名化済み帳票だけを配置する。追加者は、画像と
Azure 結果の両方について、コミット前に出所と再配布権を確認する。

禁止する値は次のとおり。

- 実顧客名、実担当者名、実住所、実メールアドレス、実電話番号
- 実銀行口座、API key、token、SAS、private Blob URL
- 実取引帳票、および再配布が許可されていない第三者帳票
