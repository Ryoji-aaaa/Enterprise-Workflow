CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_user_id UUID REFERENCES app_users (id),
    actor_type VARCHAR(30) NOT NULL,
    actor_display_name VARCHAR(200),
    action_type VARCHAR(50) NOT NULL,
    target_type VARCHAR(100) NOT NULL,
    target_id VARCHAR(100) NOT NULL,
    request_id UUID,
    correlation_id VARCHAR(100),
    source_ip INET,
    user_agent VARCHAR(1000),
    before_data JSONB,
    after_data JSONB,
    reason VARCHAR(1000),
    result VARCHAR(30) NOT NULL,
    CONSTRAINT ck_audit_logs_actor_type CHECK (
        actor_type IN ('USER', 'SYSTEM', 'BATCH', 'IDENTITY_PROVIDER')
    ),
    CONSTRAINT ck_audit_logs_result CHECK (
        result IN ('SUCCESS', 'FAILURE', 'DENIED')
    )
);

CREATE INDEX ix_audit_logs_occurred_at
    ON audit_logs (occurred_at DESC);
CREATE INDEX ix_audit_logs_actor_occurred_at
    ON audit_logs (actor_user_id, occurred_at DESC)
    WHERE actor_user_id IS NOT NULL;
CREATE INDEX ix_audit_logs_action_occurred_at
    ON audit_logs (action_type, occurred_at DESC);
CREATE INDEX ix_audit_logs_target_occurred_at
    ON audit_logs (target_type, target_id, occurred_at DESC);
CREATE INDEX ix_audit_logs_result_occurred_at
    ON audit_logs (result, occurred_at DESC);
CREATE INDEX ix_audit_logs_request_id
    ON audit_logs (request_id)
    WHERE request_id IS NOT NULL;
CREATE INDEX ix_audit_logs_correlation_id
    ON audit_logs (correlation_id)
    WHERE correlation_id IS NOT NULL;

CREATE TRIGGER tr_audit_logs_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION prevent_append_only_mutation();

CREATE TRIGGER tr_audit_logs_no_truncate
    BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_append_only_mutation();
