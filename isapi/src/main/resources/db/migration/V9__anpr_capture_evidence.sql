ALTER TABLE anpr_events
    ADD COLUMN live_event_key CHAR(64),
    ADD COLUMN live_raw_xml TEXT,
    ADD COLUMN archive_raw_xml TEXT;

CREATE UNIQUE INDEX uq_anpr_live_evidence_key
    ON anpr_events (device_id, live_event_key) WHERE live_event_key IS NOT NULL;

CREATE INDEX idx_anpr_capture_time ON anpr_events (device_id, captured_at);
