-- Additive: existing credentials and users are preserved.
ALTER TABLE users ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0;
