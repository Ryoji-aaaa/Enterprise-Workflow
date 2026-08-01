# 組織図

`/organization-chart`はBrowserから`/api/backend/organization-chart`を呼び、BFFが
アクセストークンを保持したままSpring Bootの`/api/organization-chart`へ中継する。
汎用BFF Route HandlerはHTTPメソッドとパスのallowlistを適用し、組織図とユーザー管理で
使用しないBackend APIは認証処理前に404で拒否する。

`/api/me.permissions`に`ORGANIZATION_CHART_READ`があり、雇用区分が正社員または準社員の
場合だけトップのサイドメニューへリンクを表示する。直接アクセス時もBackendが権限、
アカウント状態、有効期間、雇用区分を検証し、拒否時は画面に403を表示する。

画面は`parentUnitId`から階層を構築し、社長、責任者、一般ユーザー、所属人数を表示する。
プロジェクトは専用Badgeと配色で区別し、配下組織と一般ユーザーを折りたためる。小画面では
縦方向、大画面では横方向に並べ、全体は横スクロール可能とする。

Backendは組織、組織単位、現行所属、有効ユーザー、役職を組織図専用Repositoryの
単一JPQLで取得する。組織数・所属人数が増えてもSQL発行数は1件であり、統合テストで
HibernateのPrepared Statement数を固定している。
