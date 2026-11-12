CREATE TABLE anpr_attendance_area_periods (
    tenant_id BIGINT NOT NULL,
    area_id BIGINT NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE,
    PRIMARY KEY (tenant_id, area_id, starts_on),
    CHECK (ends_on IS NULL OR ends_on >= starts_on)
);

INSERT INTO anpr_attendance_area_periods (tenant_id, area_id, starts_on, ends_on)
SELECT a.tenant_id, a.area_id, a.starts_on,
       CASE WHEN NOT EXISTS (
           SELECT 1 FROM device_configs d
           WHERE d.tenant_id = a.tenant_id AND d.branch_id = a.area_id AND d.anpr
       ) THEN GREATEST(a.starts_on, (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Baku')::date + 1) END
FROM anpr_attendance_areas a;

CREATE UNIQUE INDEX uq_anpr_active_area_period
    ON anpr_attendance_area_periods (tenant_id, area_id) WHERE ends_on IS NULL;

ALTER TABLE attendance_logs ADD COLUMN anpr_deadline TIMESTAMP;
CREATE INDEX idx_anpr_log_work_date
    ON attendance_logs (tenant_id, anpr_work_date, employee_id) WHERE anpr_work_date IS NOT NULL;
