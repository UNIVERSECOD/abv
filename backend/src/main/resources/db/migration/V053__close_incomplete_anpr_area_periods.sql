WITH active_cameras AS (
    SELECT tenant_id, branch_id, door_role,
           CASE WHEN device_id ~ '^[+]?[0-9]+$' THEN device_id::numeric END AS bridge_id
    FROM device_configs
    WHERE anpr AND status = 'ACTIVE'
), complete_pairs AS (
    SELECT tenant_id, branch_id
    FROM active_cameras
    GROUP BY tenant_id, branch_id
    HAVING COUNT(*) = 2
       AND COUNT(*) FILTER (WHERE door_role = 'ENTRY') = 1
       AND COUNT(*) FILTER (WHERE door_role = 'EXIT') = 1
       AND COUNT(DISTINCT bridge_id) FILTER (WHERE bridge_id BETWEEN 1 AND 9223372036854775807) = 2
)
UPDATE anpr_attendance_area_periods p
SET ends_on = GREATEST(p.starts_on, (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Baku')::date + 1)
WHERE p.ends_on IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM complete_pairs c WHERE c.tenant_id = p.tenant_id AND c.branch_id = p.area_id
  );
