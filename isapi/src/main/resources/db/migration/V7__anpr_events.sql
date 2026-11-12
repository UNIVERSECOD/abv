ALTER TABLE devices ADD COLUMN anpr BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE anpr_events (
    id BIGSERIAL PRIMARY KEY,
    device_id BIGINT NOT NULL,
    event_key CHAR(64) NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    plate VARCHAR(64),
    confidence INTEGER CHECK (confidence BETWEEN 0 AND 100),
    channel VARCHAR(32),
    raw_xml TEXT NOT NULL,
    CONSTRAINT uq_anpr_device_event UNIQUE (device_id, event_key)
);

CREATE INDEX idx_anpr_events_device_id ON anpr_events (device_id, id);
