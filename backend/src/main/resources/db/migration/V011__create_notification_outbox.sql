ALTER TABLE access_requests
    ADD COLUMN notification_queued_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE notification_outbox (
    id UUID PRIMARY KEY,
    notification_type VARCHAR(100) NOT NULL,
    source_type VARCHAR(100) NOT NULL,
    source_id UUID NOT NULL,
    expense_application_id UUID,
    approval_run_id UUID,
    approval_step_id UUID,
    recipient_user_id UUID,
    recipient_name_snapshot VARCHAR(255),
    recipient_email_snapshot VARCHAR(320) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    body_text TEXT NOT NULL,
    deduplication_key VARCHAR(500) NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processing_started_at TIMESTAMP WITH TIME ZONE,
    sent_at TIMESTAMP WITH TIME ZONE,
    last_error_code VARCHAR(100),
    last_error_message VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_notification_outbox_deduplication UNIQUE (deduplication_key),
    CONSTRAINT ck_notification_outbox_status CHECK (status IN (
        'PENDING', 'PROCESSING', 'RETRY_WAIT', 'SENT', 'FAILED'
    )),
    CONSTRAINT ck_notification_outbox_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_notification_outbox_dispatch
    ON notification_outbox (status, next_attempt_at, created_at);

CREATE INDEX idx_notification_outbox_sent
    ON notification_outbox (sent_at DESC, id DESC);

CREATE INDEX idx_notification_outbox_recipient
    ON notification_outbox (recipient_email_snapshot, created_at DESC);

CREATE INDEX idx_notification_outbox_application
    ON notification_outbox (expense_application_id, created_at DESC);
