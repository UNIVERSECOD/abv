ALTER TABLE anpr_events ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_anpr_events_device_time ON anpr_events (device_id, captured_at);
