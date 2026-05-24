CREATE TABLE media_processed_events (
    event_id BINARY(16) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    failed_at TIMESTAMP(6) NULL,
    last_error VARCHAR(512) NULL,
    PRIMARY KEY (event_id)
);

CREATE INDEX idx_media_processed_events_processed_at
    ON media_processed_events (processed_at);

CREATE INDEX idx_media_processed_events_failed_at
    ON media_processed_events (failed_at);
