package com.hic.dto;

import com.hic.model.Employee.EmploymentStatus;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TabelMonthlyDTO {
    public enum CalculationMode { STANDARD, MCG }

    private CalculationMode calculationMode = CalculationMode.STANDARD;
    private int year;
    private int month;
    private int daysInMonth;
    private int employees;
    private List<RowDTO> rows;
    private Map<String, String> leaveLegend = Map.of();

    @Data
    public static class RowDTO {
        private Long employeePk;
        private String fin;
        private String fullName;
        private String position;
        private EmploymentStatus employmentStatus;
        private Map<Integer, Object> daily;
        private int workingDays;
        private double totalHours;
        private Map<Integer, McgDayDTO> mcgDaily;
        private int fullDays;
        private double partialHours;
        private double overtimeHours;
    }

    public record McgDayDTO(boolean fullDay, int workedMinutes, int regularMinutes, int overtimeMinutes,
            int permissionMinutes, int creditedPermissionMinutes, String status, boolean sourceVerified,
            int expectedMinutes, boolean scheduledDayOff) {}
}
