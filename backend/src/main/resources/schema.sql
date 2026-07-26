CREATE TABLE IF NOT EXISTS app_users (
    id UUID PRIMARY KEY,
    identity_provider VARCHAR(50) NOT NULL,
    issuer VARCHAR(500) NOT NULL,
    external_subject VARCHAR(255),
    email VARCHAR(320) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    department_name VARCHAR(200) NOT NULL,
    business_role VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_app_users_issuer_subject UNIQUE (issuer, external_subject),
    CONSTRAINT uk_app_users_email UNIQUE (email),
    CONSTRAINT ck_app_users_role CHECK (business_role IN ('USER', 'ADMIN'))
);

CREATE TABLE IF NOT EXISTS access_requests (
    id UUID PRIMARY KEY,
    issuer VARCHAR(500) NOT NULL,
    external_subject VARCHAR(255) NOT NULL,
    email VARCHAR(320) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    request_status VARCHAR(20) NOT NULL,
    first_requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    notification_sent_at TIMESTAMP WITH TIME ZONE,
    request_count BIGINT NOT NULL,
    CONSTRAINT uk_access_requests_issuer_subject UNIQUE (issuer, external_subject),
    CONSTRAINT ck_access_requests_status
        CHECK (request_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_access_requests_count CHECK (request_count > 0)
);
