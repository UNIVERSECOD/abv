ALTER TABLE device_configs ADD COLUMN anpr BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE device_configs ADD CONSTRAINT ck_anpr_camera_role
    CHECK (NOT anpr OR (tenant_id IS NOT NULL AND branch_id IS NOT NULL
        AND door_role IS NOT NULL AND door_role IN ('ENTRY', 'EXIT')));
CREATE UNIQUE INDEX uq_anpr_camera_role ON device_configs (tenant_id, branch_id, door_role) WHERE anpr;

CREATE TABLE anpr_attendance_areas (
    tenant_id BIGINT NOT NULL,
    area_id BIGINT NOT NULL,
    starts_on DATE NOT NULL,
    PRIMARY KEY (tenant_id, area_id)
);

CREATE TABLE employee_vehicle_plates (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    employee_id BIGINT NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    plate VARCHAR(32) NOT NULL,
    valid_from TIMESTAMP NOT NULL,
    valid_until TIMESTAMP,
    CHECK (valid_until IS NULL OR valid_until >= valid_from)
);
CREATE UNIQUE INDEX uq_active_employee_vehicle_plate
    ON employee_vehicle_plates (tenant_id, plate) WHERE valid_until IS NULL;
CREATE INDEX idx_employee_vehicle_history ON employee_vehicle_plates (tenant_id, plate, valid_from);

CREATE TABLE anpr_attendance_punches (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    area_id BIGINT NOT NULL,
    employee_id BIGINT,
    work_date DATE,
    bridge_device_id BIGINT NOT NULL,
    device_name TEXT,
    source_id BIGINT NOT NULL,
    method VARCHAR(8) NOT NULL CHECK (method IN ('FACE', 'ANPR', 'CARD', 'ACCESS')),
    direction VARCHAR(8) NOT NULL CHECK (direction IN ('ENTRY', 'EXIT', 'BOTH')),
    occurred_at TIMESTAMP NOT NULL,
    plate VARCHAR(64),
    confidence INTEGER,
    rejection VARCHAR(32),
    UNIQUE (tenant_id, bridge_device_id, source_id)
);
CREATE INDEX idx_anpr_work_punches
    ON anpr_attendance_punches (tenant_id, area_id, employee_id, work_date);
CREATE INDEX idx_anpr_transport_report
    ON anpr_attendance_punches (tenant_id, occurred_at DESC, id DESC) WHERE method = 'ANPR';

ALTER TABLE attendance_logs
    ADD COLUMN anpr_area_id BIGINT,
    ADD COLUMN anpr_work_date DATE,
    ADD COLUMN entry_method VARCHAR(8),
    ADD COLUMN last_entry_method VARCHAR(8),
    ADD COLUMN exit_method VARCHAR(8),
    ADD COLUMN anpr_entry_id BIGINT,
    ADD COLUMN anpr_exit_id BIGINT;
CREATE UNIQUE INDEX uq_anpr_attendance_session
    ON attendance_logs (tenant_id, employee_id, anpr_area_id, anpr_work_date) WHERE anpr_area_id IS NOT NULL;
CREATE INDEX idx_anpr_selected_entry ON attendance_logs (tenant_id, anpr_entry_id) WHERE anpr_entry_id IS NOT NULL;
CREATE INDEX idx_anpr_selected_exit ON attendance_logs (tenant_id, anpr_exit_id) WHERE anpr_exit_id IS NOT NULL;
