CREATE TABLE document_analysis_jobs (
    id UUID PRIMARY KEY,
    provider VARCHAR(40) NOT NULL,
    model_id VARCHAR(200) NOT NULL,
    provider_api_version VARCHAR(50) NOT NULL,
    normalized_schema_version INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(40) NOT NULL,
    requested_by_user_id UUID NOT NULL REFERENCES app_users (id),
    original_file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    input_object_name VARCHAR(500) NOT NULL,
    raw_result_object_name VARCHAR(500),
    normalized_result_object_name VARCHAR(500),
    provider_operation_id VARCHAR(500),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    error_code VARCHAR(100),
    error_message VARCHAR(500),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by UUID NOT NULL REFERENCES app_users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID NOT NULL REFERENCES app_users (id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_document_analysis_jobs_input_object UNIQUE (input_object_name),
    CONSTRAINT uk_document_analysis_jobs_raw_result_object UNIQUE (raw_result_object_name),
    CONSTRAINT uk_document_analysis_jobs_normalized_result_object UNIQUE (normalized_result_object_name),
    CONSTRAINT ck_document_analysis_jobs_provider CHECK (
        provider IN ('DOCUMENT_INTELLIGENCE', 'CONTENT_UNDERSTANDING')
    ),
    CONSTRAINT ck_document_analysis_jobs_status CHECK (
        status IN (
            'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED',
            'FAILED_RECOVERY_REQUIRED', 'EXPIRED'
        )
    ),
    CONSTRAINT ck_document_analysis_jobs_file_size CHECK (file_size > 0),
    CONSTRAINT ck_document_analysis_jobs_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_document_analysis_jobs_normalized_schema_version
        CHECK (normalized_schema_version >= 1),
    CONSTRAINT ck_document_analysis_jobs_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_document_analysis_jobs_error_pair
        CHECK ((error_code IS NULL) = (error_message IS NULL)),
    CONSTRAINT ck_document_analysis_jobs_result_objects_distinct CHECK (
        raw_result_object_name IS NULL
        OR normalized_result_object_name IS NULL
        OR raw_result_object_name <> normalized_result_object_name
    ),
    CONSTRAINT ck_document_analysis_jobs_lease_status CHECK (
        lease_expires_at IS NULL OR status = 'RUNNING'
    )
);

CREATE INDEX ix_document_analysis_jobs_dispatch
    ON document_analysis_jobs (status, lease_expires_at, created_at, id)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX ix_document_analysis_jobs_requested_provider_history
    ON document_analysis_jobs (requested_by_user_id, provider, created_at DESC, id DESC);

CREATE INDEX ix_document_analysis_jobs_retention
    ON document_analysis_jobs (expires_at, status)
    WHERE status <> 'EXPIRED';

INSERT INTO roles (
    id,
    role_code,
    role_name,
    description,
    role_type,
    enabled,
    system_role,
    created_by,
    created_at,
    updated_by,
    updated_at,
    version
)
VALUES (
    '30000000-0000-0000-0000-000000000010',
    'DOCUMENT_ANALYSIS_USER',
    'Document Analysis User',
    'Uses provider-neutral document analysis features',
    'BUSINESS',
    TRUE,
    TRUE,
    workflow_system_user_id(),
    CURRENT_TIMESTAMP,
    workflow_system_user_id(),
    CURRENT_TIMESTAMP,
    0
)
ON CONFLICT (role_code) DO UPDATE
SET role_name = EXCLUDED.role_name,
    description = EXCLUDED.description,
    role_type = EXCLUDED.role_type,
    enabled = EXCLUDED.enabled,
    system_role = EXCLUDED.system_role,
    updated_by = EXCLUDED.updated_by,
    updated_at = EXCLUDED.updated_at;

INSERT INTO permissions (
    id,
    permission_code,
    permission_name,
    resource_type,
    action_type,
    description,
    created_by,
    created_at,
    updated_by,
    updated_at,
    version
)
VALUES
    (
        '40000000-0000-0000-0000-000000000019',
        'DOCUMENT_ANALYSIS_READ_OWN',
        'Read Own Document Analysis Jobs',
        'DOCUMENT_ANALYSIS',
        'READ_OWN',
        'Read own document analysis job history and results metadata',
        workflow_system_user_id(), CURRENT_TIMESTAMP,
        workflow_system_user_id(), CURRENT_TIMESTAMP, 0
    ),
    (
        '40000000-0000-0000-0000-000000000020',
        'DOCUMENT_INTELLIGENCE_ANALYZE',
        'Analyze with Document Intelligence',
        'DOCUMENT_INTELLIGENCE',
        'ANALYZE',
        'Request analysis with Document Intelligence',
        workflow_system_user_id(), CURRENT_TIMESTAMP,
        workflow_system_user_id(), CURRENT_TIMESTAMP, 0
    ),
    (
        '40000000-0000-0000-0000-000000000021',
        'CONTENT_UNDERSTANDING_ANALYZE',
        'Analyze with Content Understanding',
        'CONTENT_UNDERSTANDING',
        'ANALYZE',
        'Request analysis with Content Understanding',
        workflow_system_user_id(), CURRENT_TIMESTAMP,
        workflow_system_user_id(), CURRENT_TIMESTAMP, 0
    )
ON CONFLICT (permission_code) DO UPDATE
SET permission_name = EXCLUDED.permission_name,
    resource_type = EXCLUDED.resource_type,
    action_type = EXCLUDED.action_type,
    description = EXCLUDED.description,
    updated_by = EXCLUDED.updated_by,
    updated_at = EXCLUDED.updated_at;

WITH document_analysis_mapping (role_code, permission_code) AS (
    VALUES
        ('DOCUMENT_ANALYSIS_USER', 'DOCUMENT_ANALYSIS_READ_OWN'),
        ('DOCUMENT_ANALYSIS_USER', 'DOCUMENT_INTELLIGENCE_ANALYZE'),
        ('DOCUMENT_ANALYSIS_USER', 'CONTENT_UNDERSTANDING_ANALYZE'),
        ('SYSTEM_ADMIN', 'DOCUMENT_ANALYSIS_READ_OWN'),
        ('SYSTEM_ADMIN', 'DOCUMENT_INTELLIGENCE_ANALYZE'),
        ('SYSTEM_ADMIN', 'CONTENT_UNDERSTANDING_ANALYZE')
)
INSERT INTO role_permissions (role_id, permission_id, created_by, created_at)
SELECT role.id, permission.id, workflow_system_user_id(), CURRENT_TIMESTAMP
FROM document_analysis_mapping mapping
JOIN roles role ON role.role_code = mapping.role_code
JOIN permissions permission ON permission.permission_code = mapping.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;
