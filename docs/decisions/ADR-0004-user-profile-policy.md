# ADR-0004: User Profile設定方針

- Status: Accepted
- Date: 2026-07-26
- Related commits: `2512609`, `9284c3e`
- Related files: `keycloak/scripts/configure-keycloak.sh`, `keycloak/scripts/verify-keycloak.sh`

## Context

業務APIが信頼するemailをKeycloak側でも必須とし、許可会社ドメインへ制限する必要がある。
Keycloak 26.7.0では、GET結果に存在しない`unmanagedAttributePolicy`へ`DISABLED`を
追加してPUTするとHTTP 400になった。

## Decision

User Profileの現在値をAdmin REST APIでGETし、email属性を必須の`required={}`にして、
`ALLOWED_EMAIL_DOMAIN`から生成した正規表現を設定する。
`unmanagedAttributePolicy`は明示設定せず、未設定を維持する。

この判断はKeycloak 26.7.0に対する現行方針とし、Keycloak更新時に再評価する。

## Rationale

必要な制約だけを最小更新し、現行バージョンが拒否するpayloadを避けられる。
Spring Bootでもemail verifiedと許可ドメインを再検証するため、Keycloak設定だけを
唯一の認可境界にしない。

## Alternatives considered

- `unmanagedAttributePolicy=DISABLED`を常にpayloadへ追加する
- User Profile全体を固定JSONで上書きする
- email制約をSpring Bootだけで検証する

## Consequences

Keycloakのバージョン更新時は、User Profile schemaと
`unmanagedAttributePolicy=DISABLED`のPUT可否を確認する。検証ではemail必須、
許可ドメインpattern、同項目が未設定であることを確認する。

## Temporary measures

未設定維持は終了期限付きの暫定処理ではないが、Keycloak更新を再評価条件とする。
