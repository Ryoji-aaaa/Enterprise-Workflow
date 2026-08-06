INSERT INTO permissions (
    id, permission_code, permission_name, resource_type, action_type, description,
    created_by, created_at, updated_by, updated_at, version
) VALUES (
    '40000000-0000-0000-0000-000000000018',
    'MAIL_NOTIFICATION_READ',
    'Read Mail Notification History',
    'MAIL_NOTIFICATION',
    'READ',
    'Read local development mail notification history',
    workflow_system_user_id(), CURRENT_TIMESTAMP,
    workflow_system_user_id(), CURRENT_TIMESTAMP, 0
)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, created_by, created_at)
SELECT role.id, permission.id, workflow_system_user_id(), CURRENT_TIMESTAMP
FROM roles role
CROSS JOIN permissions permission
WHERE role.role_code = 'SYSTEM_ADMIN'
  AND permission.permission_code = 'MAIL_NOTIFICATION_READ'
ON CONFLICT (role_id, permission_id) DO NOTHING;
