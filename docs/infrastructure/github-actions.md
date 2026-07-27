# GitHub ActionsとAzure OIDC

ワークフローは次の責務に分離する。

- `ci.yml`: backend、frontend、Playwright、境界検証、3イメージbuild
- `dependency-review.yml`: PR dependency review
- `terraform-plan.yml`: fmt、validate、staging/production plan
- `deploy-staging.yml`: main CI成功後のSHA image build/pushと自動apply
- `deploy-production.yml`: 指定済みSHA imageを再buildせず手動昇格

各deploy jobは`contents: read`と`id-token: write`だけを宣言し、
`azure/login`でGitHub OIDC tokenをAzure短期tokenへ交換する。長期client secretは保存しない。
外部fork PRではAzure plan jobを実行しない。production jobはGitHub Environmentの承認規則を
通す。

GitHub Environment variablesは次を登録する。

```text
AZURE_CLIENT_ID
AZURE_TENANT_ID
AZURE_SUBSCRIPTION_ID
AZURE_LOCATION
AZURE_RESOURCE_GROUP
AZURE_CONTAINER_REGISTRY_NAME
AZURE_CONTAINER_REGISTRY_RESOURCE_GROUP
AZURE_CONTAINER_APPS_ENVIRONMENT_NAME
AZURE_FRONTEND_CONTAINER_APP_NAME
AZURE_BACKEND_CONTAINER_APP_NAME
AZURE_KEYCLOAK_CONTAINER_APP_NAME
AZURE_GITHUB_IDENTITY_PRINCIPAL_ID
AZURE_KEY_VAULT_NAME
AZURE_POSTGRES_SERVER_NAME
ALLOWED_EMAIL_DOMAIN
MAIL_FROM
PROVISION_WORKLOADS
TF_STATE_RESOURCE_GROUP
TF_STATE_STORAGE_ACCOUNT
TF_STATE_CONTAINER
TF_STATE_KEY
```

Azure識別子はsecretではなくEnvironment variableとする。DB、Keycloak、Better Authの
秘密値をGitHub Secretsへ複製しない。`production`にはrequired reviewerとprevent
self-reviewを設定し、利用プランで使えない場合はworkflow実行権限を限定する。
