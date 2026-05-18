ALTER TABLE friendship_outbox
    ADD COLUMN publishing_by VARCHAR(128) NULL AFTER published_at,
    ADD COLUMN publishing_until TIMESTAMP(6) NULL AFTER publishing_by,
    ADD COLUMN publish_attempts INT NOT NULL DEFAULT 0 AFTER publishing_until,
    ADD COLUMN last_publish_error TEXT NULL AFTER publish_attempts,
    ADD KEY idx_friendship_outbox_claim (published_at, publishing_until, created_at);
