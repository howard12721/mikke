ALTER TABLE identity_users
    ADD COLUMN session_version INT NOT NULL DEFAULT 0;
