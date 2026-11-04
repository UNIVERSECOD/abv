package com.hic.dto;

import com.hic.model.Employee.EmploymentStatus;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.time.LocalDate;

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
        private String employeeCode;
        private String fin;
        private String fullName;
        private String position;
        private String areaName;
        private String workTime;
        private String workSchedule;
        private EmploymentStatus employmentStatus;
        private LocalDate terminationDate;
        private Map<Integer, Object> daily;
        private int normWorkingDays;
        private double normHours;
        private int workingDays;
        private double totalHours;
        private int extraWorkDays;
        private int totalWorkedDays;
        private int leaveDays;
        private int restHolidayDays;
        private int businessTripDays;
        private int sickDays;
        private Map<Integer, McgDayDTO> mcgDaily;
        private int fullDays;
        private double partialHours;
        private double overtimeHours;
    }

    public record McgDayDTO(boolean fullDay, int workedMinutes, int regularMinutes, int overtimeMinutes,
            int permissionMinutes, int creditedPermissionMinutes, String status, boolean sourceVerified,
            int expectedMinutes, boolean scheduledDayOff) {}
}
