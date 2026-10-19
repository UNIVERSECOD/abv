package com.hic.service;

import com.hic.dto.LeaveRequestDTO;
import com.hic.model.Employee;
import com.hic.model.LeaveRequest.LeaveStatus;
import com.hic.model.LeaveType;
import com.hic.repository.EmployeeRepository;
import com.hic.repository.LeaveTypeRepository;
import com.hic.util.TabularFileUtil;
import com.hic.util.TenantContext;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LeaveTransferServiceTest {
    @Mock LeaveService leaves;
    @Mock EmployeeRepository employees;
    @Mock LeaveTypeRepository types;
    TabularFileUtil files = new TabularFileUtil();
    LeaveTransferService service;

    @BeforeEach
    void setup() {
        TenantContext.setTenantId(7L);
        service = new LeaveTransferService(files, leaves, employees, types);
    }

    @AfterEach void cleanup() { TenantContext.clear(); }

    private void references() {
        var employee = new Employee(); employee.setId(11L); employee.setEmployeeId("HR-11");
        employee.setFirstName("Test"); employee.setLastName("Person"); employee.setFinNumber("FIN1234");
        var type = new LeaveType(); type.setId(3L); type.setLeaveCode("ANNUAL"); type.setLeaveName("İllik məzuniyyət");
        when(employees.findAllByTenantIdOrderByFirstNameAscLastNameAsc(7L)).thenReturn(List.of(employee));
        when(types.findByTenantId(7L)).thenReturn(List.of(type));
    }

    private MockMultipartFile file(List<List<String>> rows) {
        return new MockMultipartFile("file", "leaves.xlsx", "application/octet-stream",
                files.writeExcel("Məzuniyyətlər", LeaveTransferService.HEADERS, rows));
    }

    private List<String> row(String employee, String start, String end, String status) {
        return List.of(employee, "Test Person", "FIN1234", "ANNUAL", start, end, status);
    }

    @Test
    void importsValidRowsUsingHrCodeAndDefaultsToApproved() {
        references();
        var result = service.importFile(file(List.of(row("HR-11", "2026-10-18", "2026-10-20", ""))));
        assertThat(result.getImportedRows()).isEqualTo(1);
        verify(leaves).create(argThat(dto -> dto.getEmployeeId().equals(11L)
                && dto.getStatus() == LeaveStatus.APPROVED && dto.getEndDate().equals(LocalDate.of(2026, 10, 20))));
    }

    @Test
    void invalidEmployeeRejectsWholeFileWithPhysicalRowNumber() {
        references();
        var result = service.importFile(file(List.of(row("HR-11", "2026-10-18", "2026-10-20", ""),
                row("11", "2026-10-21", "2026-10-22", ""))));
        assertThat(result.getImportedRows()).isZero();
        assertThat(result.getRejectedRows()).isEqualTo(2);
        assertThat(result.getErrors().get(0).getRow()).isEqualTo(3);
        verify(leaves, never()).create(any());
    }

    @Test
    void duplicateOrOverlappingRowsAreNotInserted() {
        references();
        var result = service.importFile(file(List.of(row("HR-11", "2026-10-18", "2026-10-20", ""),
                row("HR-11", "2026-10-20", "2026-10-22", ""))));
        assertThat(result.isSuccessful()).isFalse();
        verify(leaves, never()).create(any());
    }

    @Test
    void duplicateCancelledRowsAreAlsoRejected() {
        references();
        var rejected = row("HR-11", "2026-10-18", "2026-10-20", "Rədd edilib");
        var result = service.importFile(file(List.of(rejected, rejected)));
        assertThat(result.isSuccessful()).isFalse();
        verify(leaves, never()).create(any());
    }

    @Test
    void rejectsImpossibleCalendarDateWithoutWrites() {
        references();
        var result = service.importFile(file(List.of(row("HR-11", "2026-02-30", "2026-03-01", ""))));
        assertThat(result.isSuccessful()).isFalse();
        verify(leaves, never()).create(any());
    }

    @Test
    void rejectsMismatchedFinAndUnknownStatus() {
        references();
        var result = service.importFile(file(List.of(List.of("HR-11", "Person", "WRONG", "ANNUAL", "2026-10-18", "2026-10-20", ""),
                row("HR-11", "2026-10-18", "2026-10-20", "unknown"))));
        assertThat(result.getErrors()).hasSize(2);
        verify(leaves, never()).create(any());
    }

    @Test
    void downloadedTemplateCanBeFilledAndImported() throws Exception {
        references();
        byte[] template = service.export(true);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(template)); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).contains("YYYY-MM-DD");
            assertThat(sheet.getDataValidations()).hasSize(3);
            assertThat(workbook.getSheet("Siyahılar")).isNotNull();
            var excelRow = sheet.createRow(2);
            var values = row("HR-11", "2026-10-18", "2026-10-20", "Təsdiqlənib");
            for (int i = 0; i < values.size(); i++) excelRow.createCell(i).setCellValue(values.get(i));
            workbook.write(output);
            var result = service.importFile(new MockMultipartFile("file", "template.xlsx", "application/octet-stream", output.toByteArray()));
            assertThat(result.getImportedRows()).isEqualTo(1);
        }
    }

    @Test
    void exportContainsAllStatusesAndInclusiveDates() throws Exception {
        var leave = new LeaveRequestDTO(); leave.setEmployeeCode("HR-11"); leave.setEmployeeName("Test Person");
        leave.setFinNumber("FIN1234"); leave.setLeaveTypeName("İllik məzuniyyət");
        leave.setStartDate(LocalDate.of(2026, 10, 18)); leave.setEndDate(LocalDate.of(2026, 10, 20));
        leave.setStatus(LeaveStatus.PENDING);
        when(leaves.getAll()).thenReturn(List.of(leave));
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(service.export(false)))) {
            var row = workbook.getSheetAt(0).getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("HR-11");
            assertThat(row.getCell(4).getStringCellValue()).isEqualTo("2026-10-18");
            assertThat(row.getCell(5).getStringCellValue()).isEqualTo("2026-10-20");
            assertThat(row.getCell(6).getStringCellValue()).isEqualTo("Gözləmədə");
        }
    }
}
