CREATE TABLE identity_outbox (
    id BINARY(16) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_version INT NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BINARY(16) NOT NULL,
    payload_json TEXT NOT NULL,
    correlation_id VARCHAR(128) NULL,
    causation_id BINARY(16) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    KEY idx_identity_outbox_unpublished (published_at, created_at)
);
