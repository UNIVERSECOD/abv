CREATE TABLE anpr_archive_sync_progress (
    tenant_id BIGINT NOT NULL,
    area_id BIGINT NOT NULL,
    entry_bridge_id BIGINT NOT NULL,
    exit_bridge_id BIGINT NOT NULL,
    next_day DATE NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_id UUID,
    PRIMARY KEY (tenant_id, area_id, entry_bridge_id, exit_bridge_id)
);
