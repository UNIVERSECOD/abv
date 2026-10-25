-- Existing approved leave rows predate balance accounting. Keep them uncharged,
-- so rejecting or deleting one cannot accidentally add days to a customer's balance.
ALTER TABLE leave_requests
    ADD COLUMN IF NOT EXISTS deduct_from_leave_balance BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE leave_requests
    ADD COLUMN IF NOT EXISTS balance_charged_days INTEGER NOT NULL DEFAULT 0;

ALTER TABLE leave_requests
    ADD CONSTRAINT chk_leave_balance_charged_days_nonnegative
        CHECK (balance_charged_days >= 0);
