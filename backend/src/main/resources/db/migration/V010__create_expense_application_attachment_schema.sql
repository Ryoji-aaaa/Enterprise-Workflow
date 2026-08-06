CREATE TABLE expense_application_attachments (
    id UUID PRIMARY KEY,
    expense_application_id UUID NOT NULL REFERENCES expense_applications (id),
    original_file_name VARCHAR(255) NOT NULL,
    uploaded_by_name_snapshot VARCHAR(200) NOT NULL,
    storage_object_name VARCHAR(500) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    deleted_by UUID REFERENCES app_users (id),
    deleted_at TIMESTAMP WITH TIME ZONE,
    created_by UUID NOT NULL REFERENCES app_users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID NOT NULL REFERENCES app_users (id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_expense_application_attachments_storage_object UNIQUE (storage_object_name),
    CONSTRAINT ck_expense_application_attachments_file_size CHECK (file_size > 0),
    CONSTRAINT ck_expense_application_attachments_sha256
        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_expense_application_attachments_deleted
        CHECK ((deleted_by IS NULL) = (deleted_at IS NULL))
);

CREATE INDEX ix_expense_application_attachments_active
    ON expense_application_attachments (expense_application_id, created_at, id)
    WHERE deleted_at IS NULL;
