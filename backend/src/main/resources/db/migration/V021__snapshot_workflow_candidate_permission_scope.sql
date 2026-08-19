ALTER TABLE workflow_instance_candidates
    ADD COLUMN permission_scope_snapshot JSONB;

UPDATE workflow_instance_candidates
SET permission_scope_snapshot = jsonb_build_object(
        'scopeType', 'ORGANIZATION_UNIT',
        'organizationUnitId', candidate_source_snapshot ->> 'organizationUnitId'
    )
WHERE candidate_source_snapshot ? 'organizationUnitId';

UPDATE workflow_instance_candidates
SET permission_scope_snapshot = '{"scopeType":"GLOBAL","organizationUnitId":null}'::jsonb
WHERE permission_scope_snapshot IS NULL;

ALTER TABLE workflow_instance_candidates
    ALTER COLUMN permission_scope_snapshot SET NOT NULL,
    ADD CONSTRAINT ck_workflow_instance_candidates_permission_scope CHECK (
        (permission_scope_snapshot ->> 'scopeType' = 'GLOBAL'
            AND (NOT permission_scope_snapshot ? 'organizationUnitId'
                OR permission_scope_snapshot -> 'organizationUnitId' = 'null'::jsonb))
        OR
        (permission_scope_snapshot ->> 'scopeType' = 'ORGANIZATION_UNIT'
            AND jsonb_typeof(permission_scope_snapshot -> 'organizationUnitId') = 'string')
    );
