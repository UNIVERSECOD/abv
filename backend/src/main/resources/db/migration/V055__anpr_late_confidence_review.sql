ALTER TABLE anpr_attendance_punches ADD COLUMN late_confidence INTEGER CHECK (late_confidence BETWEEN 0 AND 100);
