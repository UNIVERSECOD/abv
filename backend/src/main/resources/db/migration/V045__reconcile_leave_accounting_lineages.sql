-- Both released V043 scripts remain immutable and are selected from recorded
-- Flyway history. Add compatibility metadata without rewriting existing amounts,
-- dates, request statuses, charges or successful migration history.
-- The equivalent additive proposal passed isolated main43/feature44 safety checks.
DO $$
DECLARE
    main_lineage BOOLEAN := '${leaveAccountingLineage}' = 'main';
BEGIN
    ALTER TABLE employees ADD COLUMN IF NOT EXISTS leave_balance_start_date DATE NOT NULL
        DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Baku')::date;
    ALTER TABLE employees ADD COLUMN IF NOT EXISTS legacy_leave_balance_offset INTEGER NOT NULL DEFAULT 0;
    ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS deduct_from_leave_balance BOOLEAN NOT NULL DEFAULT FALSE;
    ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS balance_charged_days INTEGER NOT NULL DEFAULT 0;
    ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS legacy_balance_accounting BOOLEAN NOT NULL DEFAULT FALSE;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'leave_requests'::regclass
                     AND conname = 'chk_leave_balance_charged_days_nonnegative') THEN
        ALTER TABLE leave_requests ADD CONSTRAINT chk_leave_balance_charged_days_nonnegative
            CHECK (balance_charged_days >= 0);
    END IF;

    -- The customizer supplies this only after matching the recorded script/checksum.
    IF main_lineage THEN
        -- The original annual_leave_balance remains byte-for-byte unchanged.
        -- Only new metadata bridges its old remaining-balance interpretation.
        UPDATE leave_requests SET legacy_balance_accounting = TRUE;
        UPDATE employees e SET legacy_leave_balance_offset = charged.days
        FROM (
            SELECT tenant_id, employee_id, SUM(balance_charged_days)::INTEGER AS days
            FROM leave_requests WHERE status = 'APPROVED'
            GROUP BY tenant_id, employee_id
        ) charged
        WHERE e.id = charged.employee_id AND e.tenant_id = charged.tenant_id;
    END IF;
END $$;
