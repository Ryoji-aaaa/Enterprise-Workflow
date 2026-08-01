# 組織図

`/organization-chart`はBrowserから`/api/backend/organization-chart`を呼び、BFFが
アクセストークンを保持したままSpring Bootの`/api/organization-chart`へ中継する。
汎用BFF Route HandlerはHTTPメソッドとパスのallowlistを適用し、組織図とユーザー管理で
使用しないBackend APIは認証処理前に404で拒否する。

`/api/me.permissions`に`ORGANIZATION_CHART_READ`があり、雇用区分が正社員または準社員の
場合だけトップのサイドメニューへリンクを表示する。直接アクセス時もBackendが権限、
アカウント状態、有効期間、雇用区分を検証し、拒否時は画面に403を表示する。

画面は`parentUnitId`から階層を構築し、株主総会、監査役会、取締役会など`OTHER`種別の
統治機関を別枠にする。業務執行組織は社長を最上層として配下組織を縦方向のツリーで表示し、
責任者、一般ユーザー、所属人数を表示する。プロジェクトは専用Badgeと配色で区別し、配下組織と
一般ユーザーを折りたためる。4階層までは表示領域へ収め、5階層目以降だけ横スクロール可能とする。
`/api/me.permissions`に`USER_UPDATE`がある場合は、社長、責任者、一般ユーザーの各表示に
鉛筆アイコン付きの編集ボタンを表示し、対象ユーザーの`/admin/users/{userId}/edit`へ遷移する。
ユーザー名自体はリンクにしない。

トップ画面のサイドバーが非表示になる`md`未満では、ヘッダーに権限連動のモバイルナビゲーションを
表示し、組織図とユーザー管理へ移動できるようにする。

Backendは組織、組織単位、現行所属、有効ユーザー、役職を組織図専用Repositoryの
単一JPQLで取得する。組織数・所属人数が増えてもSQL発行数は1件であり、統合テストで
HibernateのPrepared Statement数を固定している。
