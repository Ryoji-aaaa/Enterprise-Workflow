UPDATE access_requests
SET notification_queued_at = notification_sent_at
WHERE notification_queued_at IS NULL
  AND notification_sent_at IS NOT NULL;
