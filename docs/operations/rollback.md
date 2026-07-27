# Container Apps Revisionの切り戻し

デプロイ失敗時は、まず新revisionのログとprobe失敗理由を保存する。Terraformの
`image_tag`へ直前の正常SHAを指定してplan/applyし、3アプリの互換性が保たれる組合せで
戻す。Portalでは`Container Apps > Revisions and replicas`から新旧revisionとtrafficを
確認する。

```bash
terraform plan -var='provision_workloads=true' \
  -var='image_tag=<last-known-good-40-char-sha>' -out=rollback.tfplan
terraform apply rollback.tfplan
```

Single revision modeのためapply後は正常revisionが100% trafficを受ける。イメージだけを
Portalで変更するとTerraform driftになるため行わない。緊急にPortal操作した場合は、
直後に同じSHAをTerraformへ反映する。

Flyway成功後にアプリだけ失敗した場合、revision rollbackではDB schemaは戻らない。
旧アプリが新schemaと互換であることを確認し、互換でなければ個別migration計画を使う。
