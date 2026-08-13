CREATE TABLE expense_application_auto_entry_contexts (
    id UUID PRIMARY KEY,
    expense_application_id UUID NOT NULL
        REFERENCES expense_applications (id) ON DELETE CASCADE,
    analysis_id UUID NOT NULL
        REFERENCES document_analysis_jobs (id),
    source_attachment_id UUID NOT NULL
        REFERENCES expense_application_attachments (id),
    context_schema_version INTEGER NOT NULL,
    auto_entry_schema_version VARCHAR(20) NOT NULL,
    review_snapshot JSONB NOT NULL,
    human_review_state JSONB NOT NULL,
    created_by UUID NOT NULL REFERENCES app_users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID NOT NULL REFERENCES app_users (id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_expense_auto_entry_context_application
        UNIQUE (expense_application_id),
    CONSTRAINT uk_expense_auto_entry_context_analysis
        UNIQUE (analysis_id),
    CONSTRAINT uk_expense_auto_entry_context_source_attachment
        UNIQUE (source_attachment_id),
    CONSTRAINT ck_expense_auto_entry_context_schema_version
        CHECK (context_schema_version = 1),
    CONSTRAINT ck_expense_auto_entry_context_auto_entry_schema_version
        CHECK (char_length(auto_entry_schema_version) > 0)
);
