# ADR-0003: Keycloak設定をAdmin REST APIへ統一

- Status: Accepted
- Date: 2026-07-26
- Related commits: `2512609`, `9284c3e`
- Related files: `keycloak/scripts/configure-keycloak.sh`, `keycloak/scripts/verify-keycloak.sh`, `keycloak/scripts/initialize-keycloak.sh`

## Context

Realmの初回作成と、起動済みRealmのClient・ユーザー・User Profile更新では
ライフサイクルが異なる。以前の`kcadm.sh`を使う手順では、認証直後の不安定なHTTP 401と
CLI固有の状態管理が冪等初期化を複雑にした。

## Decision

Realmの初回作成はKeycloak startup importへ限定する。既存RealmのClient、開発ユーザー、
User Profileの設定と検証は、内部ネットワーク上のAdmin REST APIへ統一する。
管理tokenは一時的な`keycloak-init`コンテナ内だけで取得し、`kcadm.sh`は使用しない。

これは暫定回避ではなく現在の正式方針である。

## Rationale

HTTP statusとresponse bodyを明示的に検証でき、処理を冪等に保てる。CLIの設定ファイルや
認証状態へ依存せず、秘密情報を一時コンテナ外へ残さない。認証直後の401を伴う方式を
最終構成から除外できる。

## Alternatives considered

- すべての設定をRealm importだけで管理する
- `kcadm.sh`によるログインと更新を継続する
- Keycloak管理画面から手動設定する

## Consequences

Keycloak Admin REST APIの互換性を更新時に確認する必要がある。設定スクリプトと
検証スクリプトは分離し、検証処理は状態を変更しない。

## Temporary measures

なし。
