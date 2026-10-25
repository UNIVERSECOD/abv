ALTER TABLE leave_types ADD COLUMN short_name VARCHAR(3);
ALTER TABLE leave_types ADD COLUMN deduct_from_balance BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE leave_types ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;

-- Preserve the existing paid/unpaid policy and historical Tabel display.
UPDATE leave_types SET short_name = 'M',
    deduct_from_balance = (leave_code = 'ANNUAL');
UPDATE leave_types SET leave_name = 'Ödənişli məzuniyyət' WHERE leave_code = 'ANNUAL';
UPDATE leave_types SET leave_name = 'Ödənişsiz məzuniyyət' WHERE leave_code = 'UNPAID';

-- Hide unused built-in seeds; new installations start by creating their own types.
-- Referenced legacy types and every historical request remain intact.
UPDATE leave_types t SET archived = TRUE
WHERE t.leave_code IN ('ANNUAL', 'SICK', 'MATERNITY', 'PATERNITY', 'CHILDCARE', 'STUDY',
    'MARRIAGE', 'BEREAVEMENT', 'UNPAID', 'DISABILITY', 'MILITARY', 'ADDITIONAL')
AND NOT EXISTS (SELECT 1 FROM leave_requests r WHERE r.leave_type_id = t.id AND r.tenant_id = t.tenant_id);
