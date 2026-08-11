ALTER TABLE document_analysis_jobs
    ADD COLUMN analysis_profile VARCHAR(40),
    ADD COLUMN completion_model_deployment_name VARCHAR(200),
    ADD COLUMN embedding_model_deployment_name VARCHAR(200);

UPDATE document_analysis_jobs
SET analysis_profile = 'GENERAL'
WHERE analysis_profile IS NULL;

ALTER TABLE document_analysis_jobs
    ALTER COLUMN analysis_profile SET NOT NULL,
    ADD CONSTRAINT ck_document_analysis_jobs_analysis_profile CHECK (
        analysis_profile IN ('GENERAL', 'AUTO_ENTRY')
    ),
    ADD CONSTRAINT ck_document_analysis_jobs_profile_provider_deployments CHECK (
        (
            analysis_profile = 'GENERAL'
            AND completion_model_deployment_name IS NULL
            AND embedding_model_deployment_name IS NULL
        )
        OR (
            analysis_profile = 'AUTO_ENTRY'
            AND provider = 'CONTENT_UNDERSTANDING'
            AND completion_model_deployment_name IS NOT NULL
            AND embedding_model_deployment_name IS NOT NULL
        )
    );

CREATE INDEX ix_document_analysis_jobs_requested_profile_provider_history
    ON document_analysis_jobs (
        requested_by_user_id,
        analysis_profile,
        provider,
        created_at DESC,
        id DESC
    );
