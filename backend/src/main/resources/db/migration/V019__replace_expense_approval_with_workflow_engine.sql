DROP TABLE expense_approval_candidates;
DROP TABLE expense_approval_steps;
DROP TABLE expense_approval_runs;

ALTER TABLE notification_outbox
    RENAME COLUMN approval_run_id TO workflow_instance_id;
ALTER TABLE notification_outbox
    RENAME COLUMN approval_step_id TO workflow_step_id;

CREATE TABLE workflow_definitions (
    id UUID PRIMARY KEY,
    workflow_code VARCHAR(100) NOT NULL,
    workflow_name VARCHAR(200) NOT NULL,
    subject_type VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_workflow_definitions_code UNIQUE (workflow_code)
);

CREATE TABLE workflow_definition_versions (
    id UUID PRIMARY KEY,
    workflow_definition_id UUID NOT NULL REFERENCES workflow_definitions (id),
    version_number INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    effective_from TIMESTAMP WITH TIME ZONE,
    effective_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_workflow_definition_versions_number
        UNIQUE (workflow_definition_id, version_number),
    CONSTRAINT ck_workflow_definition_versions_number CHECK (version_number > 0),
    CONSTRAINT ck_workflow_definition_versions_status CHECK (status IN (
        'DRAFT', 'PUBLISHED', 'RETIRED'
    )),
    CONSTRAINT ck_workflow_definition_versions_period CHECK (
        effective_until IS NULL OR effective_from IS NULL OR effective_until > effective_from
    )
);

CREATE INDEX ix_workflow_definition_versions_published
    ON workflow_definition_versions (workflow_definition_id, status, effective_from DESC);

CREATE TABLE workflow_nodes (
    id UUID PRIMARY KEY,
    workflow_definition_version_id UUID NOT NULL
        REFERENCES workflow_definition_versions (id) ON DELETE CASCADE,
    node_key VARCHAR(100) NOT NULL,
    node_type VARCHAR(30) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    approval_mode VARCHAR(30),
    CONSTRAINT uk_workflow_nodes_key
        UNIQUE (workflow_definition_version_id, node_key),
    CONSTRAINT ck_workflow_nodes_type CHECK (node_type IN ('START', 'APPROVAL', 'END')),
    CONSTRAINT ck_workflow_nodes_approval_mode CHECK (
        (node_type = 'APPROVAL' AND approval_mode = 'ANY_ONE')
        OR (node_type IN ('START', 'END') AND approval_mode IS NULL)
    )
);

CREATE TABLE workflow_transitions (
    id UUID PRIMARY KEY,
    workflow_definition_version_id UUID NOT NULL
        REFERENCES workflow_definition_versions (id) ON DELETE CASCADE,
    transition_key VARCHAR(100) NOT NULL,
    from_node_id UUID NOT NULL REFERENCES workflow_nodes (id) ON DELETE CASCADE,
    to_node_id UUID NOT NULL REFERENCES workflow_nodes (id) ON DELETE CASCADE,
    condition_json JSONB,
    CONSTRAINT uk_workflow_transitions_key
        UNIQUE (workflow_definition_version_id, transition_key)
);

CREATE INDEX ix_workflow_transitions_from
    ON workflow_transitions (workflow_definition_version_id, from_node_id);

CREATE TABLE workflow_assignee_rules (
    id UUID PRIMARY KEY,
    workflow_node_id UUID NOT NULL REFERENCES workflow_nodes (id) ON DELETE CASCADE,
    resolver_type VARCHAR(100) NOT NULL,
    parameters_json JSONB NOT NULL,
    required_permission_code VARCHAR(100) NOT NULL REFERENCES permissions (permission_code),
    exclude_requester BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_workflow_assignee_rules_node UNIQUE (workflow_node_id)
);

CREATE TABLE workflow_instances (
    id UUID PRIMARY KEY,
    workflow_definition_version_id UUID NOT NULL REFERENCES workflow_definition_versions (id),
    subject_type VARCHAR(100) NOT NULL,
    subject_id UUID NOT NULL,
    run_number INTEGER NOT NULL,
    requester_user_id UUID NOT NULL REFERENCES app_users (id),
    status VARCHAR(30) NOT NULL,
    context_snapshot JSONB NOT NULL,
    resolution_snapshot JSONB NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_by UUID NOT NULL REFERENCES app_users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_workflow_instances_subject_run
        UNIQUE (subject_type, subject_id, run_number),
    CONSTRAINT ck_workflow_instances_run_number CHECK (run_number > 0),
    CONSTRAINT ck_workflow_instances_status CHECK (status IN (
        'PENDING', 'APPROVED', 'RETURNED', 'CANCELLED'
    ))
);

CREATE INDEX ix_workflow_instances_subject
    ON workflow_instances (subject_type, subject_id, run_number DESC);

CREATE TABLE workflow_instance_steps (
    id UUID PRIMARY KEY,
    workflow_instance_id UUID NOT NULL REFERENCES workflow_instances (id) ON DELETE CASCADE,
    step_order INTEGER NOT NULL,
    node_key_snapshot VARCHAR(100) NOT NULL,
    step_name_snapshot VARCHAR(200) NOT NULL,
    approval_mode_snapshot VARCHAR(30) NOT NULL,
    required_permission_code_snapshot VARCHAR(100) NOT NULL,
    assignee_rule_snapshot JSONB NOT NULL,
    status VARCHAR(30) NOT NULL,
    processed_by_user_id UUID REFERENCES app_users (id),
    processed_by_name_snapshot VARCHAR(200),
    processed_at TIMESTAMP WITH TIME ZONE,
    comment TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_workflow_instance_steps_order UNIQUE (workflow_instance_id, step_order),
    CONSTRAINT ck_workflow_instance_steps_order CHECK (step_order > 0),
    CONSTRAINT ck_workflow_instance_steps_mode CHECK (approval_mode_snapshot = 'ANY_ONE'),
    CONSTRAINT ck_workflow_instance_steps_status CHECK (status IN (
        'WAITING', 'PENDING', 'APPROVED', 'RETURNED', 'CANCELLED'
    ))
);

CREATE INDEX ix_workflow_instance_steps_status
    ON workflow_instance_steps (workflow_instance_id, status, step_order);

CREATE TABLE workflow_instance_candidates (
    id UUID PRIMARY KEY,
    workflow_instance_step_id UUID NOT NULL
        REFERENCES workflow_instance_steps (id) ON DELETE CASCADE,
    candidate_user_id UUID NOT NULL REFERENCES app_users (id),
    candidate_name_snapshot VARCHAR(200) NOT NULL,
    candidate_email_snapshot VARCHAR(320) NOT NULL,
    candidate_source_snapshot JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_workflow_instance_candidates_user
        UNIQUE (workflow_instance_step_id, candidate_user_id)
);

CREATE INDEX ix_workflow_instance_candidates_user
    ON workflow_instance_candidates (candidate_user_id, workflow_instance_step_id);

CREATE TABLE workflow_instance_actions (
    id UUID PRIMARY KEY,
    workflow_instance_id UUID NOT NULL REFERENCES workflow_instances (id) ON DELETE CASCADE,
    workflow_instance_step_id UUID REFERENCES workflow_instance_steps (id) ON DELETE CASCADE,
    action_type VARCHAR(30) NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES app_users (id),
    actor_name_snapshot VARCHAR(200) NOT NULL,
    comment TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_workflow_instance_actions_type CHECK (action_type IN (
        'APPROVE', 'RETURN', 'CANCEL'
    ))
);

CREATE INDEX ix_workflow_instance_actions_instance
    ON workflow_instance_actions (workflow_instance_id, created_at, id);
