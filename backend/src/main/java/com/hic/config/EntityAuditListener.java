package com.hic.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hic.util.AppTimeZone;
import com.hic.util.TenantContext;
import org.hibernate.event.spi.*;
import org.hibernate.persister.entity.EntityPersister;
import java.sql.Timestamp;
import java.util.*;

/** Records committed entity changes in the SAME database transaction. No raw DTOs or secrets. */
public class EntityAuditListener implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {
    private static final Map<String, Set<String>> FIELDS = Map.ofEntries(
        fields("Employee", "employeeId firstName lastName fatherName branchId departmentId positionId hireDate contractEndDate terminationDate annualLeaveDuration annualLeaveBalance salary hourlyRate shiftType timetableId employmentStatus"),
        fields("Branch", "name code city address status isHeadOffice"),
        fields("Department", "departmentName branchId timetableId"),
        fields("Position", "positionName departmentId"),
        fields("DeviceConfig", "deviceName deviceIp devicePort branchId doorId doorRole status"),
        fields("Door", "name branchId"),
        fields("EmployeeArea", "employeeId branchId primary"),
        fields("EmployeeDeviceAccess", "employeeId deviceConfigId assignmentSource sourceBranchId"),
        fields("EmployeeShiftAssignment", "employeeId timetableId effectiveStartDate effectiveEndDate status"),
        fields("Timetable", "name startTime endTime crossesMidnight allowedLateMinutes allowedEarlyLeaveMinutes shiftType breakMinutes"),
        fields("TimetableDayRule", "dayOfWeek workingDay startTime endTime breakMinutes allowedLateMinutes allowedEarlyLeaveMinutes"),
        fields("EmployeePermission", "employeeId permissionTypeId startDate endDate startTime endTime deductFromWorkHours status"),
        fields("HolidayPermission", "name startDate endDate applyScope status"),
        fields("LeaveRequest", "employeeId leaveTypeId startDate endDate status"),
        fields("FaceData", "employeeId status"),
        fields("AttendanceLogAdjustment", "attendanceLogId previousCheckInTime previousCheckOutTime newCheckInTime newCheckOutTime"),
        fields("EmployeeDeviceRemovalJob", "employeeId deviceConfigId completed attempts"),
        fields("User", "username firstName lastName userType branchId departmentId"),
        fields("Tenant", "companyName attendanceSyncIntervalMinutes")
    );
    private static final Set<String> REDACTED = Set.of("passwordHash", "passwordEncrypted", "faceImage", "faceTemplate", "imagePath",
            "faceImageUrl", "faceId", "cardId", "finNumber", "deviceEmployeeNo", "birthDate", "gender", "mobilePhone", "email",
            "serialNumber", "contractNumber", "allowance", "emergencyContact", "address", "notes", "reason", "description");
    private final ObjectMapper json = new ObjectMapper();

    private static Map.Entry<String, Set<String>> fields(String entity, String names) {
        return Map.entry(entity, Set.of(names.split(" ")));
    }

    public List<Map<String, Object>> changes(String entity, String[] names, Object[] before, Object[] after) {
        Set<String> allowed = FIELDS.getOrDefault(entity, Set.of());
        List<Map<String, Object>> changes = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            Object oldValue = before == null ? null : before[i];
            Object newValue = after == null ? null : after[i];
            if (Objects.deepEquals(oldValue, newValue)) continue;
            if (!allowed.contains(names[i]) && !REDACTED.contains(names[i])) continue;
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("field", names[i]);
            change.put("before", REDACTED.contains(names[i]) ? null : safeValue(oldValue));
            change.put("after", REDACTED.contains(names[i]) ? "Dəyişdirildi (məxfi dəyər saxlanılmır)" : safeValue(newValue));
            changes.add(change);
        }
        return changes;
    }

    private String safeValue(Object value) {
        if (value == null) return null;
        if (!(value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                || value instanceof Enum<?> || value instanceof java.time.temporal.TemporalAccessor)) return null;
        String text = value.toString();
        return text.length() > 500 ? text.substring(0, 500) + "…" : text;
    }

    @Override public void onPostInsert(PostInsertEvent e) { record(e.getPersister(), e.getId(), null, e.getState(), "CREATE", e.getSession()); }
    @Override public void onPostUpdate(PostUpdateEvent e) { record(e.getPersister(), e.getId(), e.getOldState(), e.getState(), "UPDATE", e.getSession()); }
    @Override public void onPostDelete(PostDeleteEvent e) { record(e.getPersister(), e.getId(), e.getDeletedState(), null, "DELETE", e.getSession()); }
    @Override public boolean requiresPostCommitHandling(EntityPersister persister) { return false; }

    private void record(EntityPersister persister, Object id, Object[] before, Object[] after, String action, EventSource session) {
        String entity = persister.getMappedClass().getSimpleName();
        if (!FIELDS.containsKey(entity)) return;
        String[] names = persister.getPropertyNames();
        Object[] state = after == null ? before : after;
        Long tenantId = null;
        for (int i = 0; i < names.length; i++) if ("tenantId".equals(names[i])) tenantId = (Long) state[i];
        if ("Tenant".equals(entity)) tenantId = (Long) id;
        if (tenantId == null) tenantId = TenantContext.getTenantId();
        if (tenantId == null) return;
        var changes = changes(entity, names, before, after);
        if (changes.isEmpty()) return; // e.g. health polls updating only online/lastSyncTime
        String details;
        try { details = json.writeValueAsString(Map.of("changes", changes)); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Audit serialization failed", ex); }
        Long company = tenantId;
        Long userId = TenantContext.getUserId();
        String actor = TenantContext.getUsername() != null ? TenantContext.getUsername() : "Sistem";
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement("INSERT INTO audit_logs (tenant_id,user_id,username,action,entity_type,entity_id,details,created_at) VALUES (?,?,?,?,?,?,?,?)")) {
                stmt.setLong(1, company);
                stmt.setObject(2, userId);
                stmt.setString(3, actor);
                stmt.setString(4, action);
                stmt.setString(5, entity);
                stmt.setString(6, String.valueOf(id));
                stmt.setString(7, details);
                stmt.setTimestamp(8, Timestamp.valueOf(AppTimeZone.now()));
                stmt.executeUpdate();
            }
        });
    }
}
