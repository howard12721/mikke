ALTER TABLE push_tokens
    CHANGE COLUMN token_hash registration_hash CHAR(64) NOT NULL,
    CHANGE COLUMN token_encrypted registration_encrypted TEXT NOT NULL;

ALTER TABLE notification_deliveries
    ADD COLUMN next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) AFTER attempt_count,
    ADD COLUMN lease_owner VARCHAR(128) NULL AFTER next_attempt_at,
    ADD COLUMN lease_expires_at TIMESTAMP(6) NULL AFTER lease_owner,
    ADD UNIQUE KEY uq_notification_deliveries_notification_push_token (notification_id, push_token_id),
    ADD KEY idx_notification_deliveries_ready (status, next_attempt_at, lease_expires_at);
