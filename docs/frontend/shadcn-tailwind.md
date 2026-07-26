# shadcn/ui・Tailwind CSS仕様

## 採用構成

FrontendのUI基盤としてTailwind CSS v4とshadcn/uiを使用する。shadcn/uiは
コンポーネントライブラリをnpm packageとして利用する方式ではなく、CLIで生成した
コンポーネントのソースコードをリポジトリ内で管理する。

初期化にはshadcn/createで作成したプリセット`b7Vp4lXmq`を使用する。

| 項目 | 設定 |
| --- | --- |
| Style | Mira |
| Base | Base UI |
| Base color | Taupe |
| Theme | Cyan |
| Chart color | Taupe |
| Icon library | Lucide |
| Font | Inter |
| Radius | Large |
| Menu accent | Subtle |
| Menu color | Default |

プリセットの解決結果とCLI設定は`frontend/components.json`、テーマ変数は
`frontend/src/app/globals.css`で管理する。プリセットを変更する場合は、生成差分を
確認し、既存画面への影響とfrontendテストを確認してからコミットする。

## ファイル構成

- `frontend/components.json`: shadcn/ui CLI設定と生成先alias
- `frontend/postcss.config.mjs`: Tailwind CSS用PostCSS plugin設定
- `frontend/src/app/globals.css`: Tailwind CSS import、テーマ変数、共通スタイル
- `frontend/src/app/layout.tsx`: Inter fontの読込とルート要素への適用
- `frontend/src/lib/utils.ts`: class名を結合する`cn` utility
- `frontend/src/components/ui/`: CLIで追加するUIコンポーネント

import aliasは`@/*`を`frontend/src/*`へ割り当てる。shadcn/uiコンポーネントは
`@/components/ui`、共通utilityは`@/lib`から参照する。

## コンポーネントの追加

Node.jsとnpmはホストへ導入しない。コンポーネントを追加するときは、Node.js
コンテナからFrontendディレクトリを操作する。次の例はButtonを追加する。

```bash
docker run --rm \
  --user "$(id -u):$(id -g)" \
  --env HOME=/tmp \
  --volume "$PWD/frontend:/workspace" \
  --workdir /workspace \
  node:24.18.0-alpine \
  npx --yes shadcn@4.15.0 add button
```

CLI実行後は、生成ファイル、`package.json`、`package-lock.json`をまとめてレビューする。
UIコンポーネントを直接編集した場合、後から同じコンポーネントを再追加すると
上書き対象になる可能性があるため、CLIが表示する確認内容を確認する。

## プリセットの変更

このプロジェクトは初期化済みなので、別のプリセットへ変更するときは`init`ではなく
`apply`を使用する。現在のプリセットを再適用する例は次のとおり。

```bash
docker run --rm \
  --user "$(id -u):$(id -g)" \
  --env HOME=/tmp \
  --volume "$PWD/frontend:/workspace" \
  --workdir /workspace \
  node:24.18.0-alpine \
  npx --yes shadcn@4.15.0 apply --preset b7Vp4lXmq
```

`apply`はテーマ、フォント、アイコンと既存のshadcn/uiコンポーネントを更新する。
既存の業務画面を自動的にshadcn/uiコンポーネントへ置き換えるものではない。

## スタイル方針

- 色、border radius、foreground/backgroundはプリセットのCSS変数を使用する。
- 新規画面では`frontend/src/components/ui`の共通コンポーネントを優先する。
- 業務固有のレイアウトはTailwind utilityで実装し、汎用UIへ業務ロジックを含めない。
- dark theme用変数は保持するが、theme切替UIを追加するまではlight themeを標準とする。
- 既存の共通CSS classは、画面をshadcn/uiへ移行するまで互換性のため保持する。

## 検証

変更後は次を実行する。

```bash
make test-frontend
```

このテストはDocker内でlint、TypeScript、単体テスト、production buildを実行し、
production dependencyを`npm audit --omit=dev`で監査する。

## 公式資料

- [shadcn/ui CLI](https://ui.shadcn.com/docs/cli)
- [shadcn/ui Next.js installation](https://ui.shadcn.com/docs/installation/next)
- [Tailwind CSS PostCSS installation](https://tailwindcss.com/docs/installation/using-postcss)
