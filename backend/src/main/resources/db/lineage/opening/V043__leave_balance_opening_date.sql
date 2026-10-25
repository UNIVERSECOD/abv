-- Existing employee balances are opening balances, not annual entitlements.
-- Do not deduct leave before the installation's Baku activation date again.
ALTER TABLE employees ADD COLUMN leave_balance_start_date DATE NOT NULL
    DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Baku')::date;
