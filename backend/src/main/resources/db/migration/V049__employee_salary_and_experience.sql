ALTER TABLE employees ADD COLUMN net_salary NUMERIC(12, 2);
ALTER TABLE employees ADD COLUMN actual_salary NUMERIC(12, 2);
ALTER TABLE employees ADD COLUMN prior_experience_years INTEGER;
ALTER TABLE employees ADD COLUMN prior_experience_months INTEGER;
ALTER TABLE employees ADD COLUMN prior_experience_days INTEGER;

ALTER TABLE employees ADD CONSTRAINT chk_employee_prior_experience_nonnegative CHECK (
    (prior_experience_years IS NULL OR prior_experience_years >= 0) AND
    (prior_experience_months IS NULL OR prior_experience_months BETWEEN 0 AND 11) AND
    (prior_experience_days IS NULL OR prior_experience_days BETWEEN 0 AND 30)
);
