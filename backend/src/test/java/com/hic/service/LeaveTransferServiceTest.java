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

    @Test void importsPaidDisplayNameAlongsideLegacyAnnualCode() {
        references();
        var result = service.importFile(file(List.of(List.of("HR-11", "Test Person", "FIN1234",
                "Ödənişli məzuniyyət", "2026-11-01", "2026-11-03", "APPROVED"))));
        assertThat(result.getImportedRows()).isEqualTo(1);
        verify(leaves).create(argThat(dto -> dto.getLeaveTypeId().equals(3L)));
    }

    @Test void importsUnpaidDisplayName() {
        references();
        var type = new LeaveType(); type.setId(4L); type.setLeaveCode("UNPAID"); type.setLeaveName("Ödənişsiz məzuniyyət");
        when(types.findByTenantId(7L)).thenReturn(List.of(type));
        var result = service.importFile(file(List.of(List.of("HR-11", "Test Person", "FIN1234",
                "Ödənişsiz məzuniyyət", "2026-11-01", "2026-11-03", "APPROVED"))));
        assertThat(result.getImportedRows()).isEqualTo(1);
        verify(leaves).create(argThat(dto -> dto.getLeaveTypeId().equals(4L)));
    }

    @Test void importsActiveReplacementWhenArchivedTypeHasTheSameName() {
        references();
        var archived = new LeaveType(); archived.setId(3L); archived.setLeaveCode("CUSTOM_OLD");
        archived.setLeaveName("Təhsil"); archived.setShortName("TƏ"); archived.setArchived(true);
        var active = new LeaveType(); active.setId(4L); active.setLeaveCode("CUSTOM_NEW");
        active.setLeaveName("Təhsil"); active.setShortName("TƏ");
        when(types.findByTenantId(7L)).thenReturn(List.of(archived, active));

        var result = service.importFile(file(List.of(List.of("HR-11", "Test Person", "FIN1234",
                "Təhsil", "2026-11-01", "2026-11-03", "APPROVED"))));

        assertThat(result.getImportedRows()).isEqualTo(1);
        verify(leaves).create(argThat(dto -> dto.getLeaveTypeId().equals(4L)));
    }

    @Test void importsLegacyAnnualNameAfterMigrationRenamesTheBuiltInType() {
        references();
        var migrated = new LeaveType(); migrated.setId(3L); migrated.setLeaveCode("ANNUAL");
        migrated.setLeaveName("Ödənişli məzuniyyət"); migrated.setShortName("M"); migrated.setDeductFromBalance(true);
        when(types.findByTenantId(7L)).thenReturn(List.of(migrated));

        var result = service.importFile(file(List.of(List.of("HR-11", "Test Person", "FIN1234",
                "İllik məzuniyyət", "2026-11-01", "2026-11-03", "APPROVED"))));

        assertThat(result.getImportedRows()).isEqualTo(1);
        verify(leaves).create(argThat(dto -> dto.getLeaveTypeId().equals(3L)));
    }

    @Test void currentCustomNameTakesPrecedenceOverLegacyAnnualAlias() {
        references();
        var migrated = new LeaveType(); migrated.setId(3L); migrated.setLeaveCode("ANNUAL");
        migrated.setLeaveName("Ödənişli məzuniyyət"); migrated.setShortName("M");
        var custom = new LeaveType(); custom.setId(4L); custom.setLeaveCode("CUSTOM_NEW");
        custom.setLeaveName("İllik məzuniyyət"); custom.setShortName("İM");
        when(types.findByTenantId(7L)).thenReturn(List.of(migrated, custom));

        var result = service.importFile(file(List.of(List.of("HR-11", "Test Person", "FIN1234",
                "İllik məzuniyyət", "2026-11-01", "2026-11-03", "APPROVED"))));

        assertThat(result.getImportedRows()).isEqualTo(1);
        verify(leaves).create(argThat(dto -> dto.getLeaveTypeId().equals(4L)));
    }

    @Test void archivedTypeCodeRejectsTheWholeFileBeforeWritingValidRows() {
        references();
        var active = new LeaveType(); active.setId(3L); active.setLeaveCode("ANNUAL");
        active.setLeaveName("İllik məzuniyyət");
        var archived = new LeaveType(); archived.setId(4L); archived.setLeaveCode("CUSTOM_OLD");
        archived.setLeaveName("Köhnə növ"); archived.setShortName("K"); archived.setArchived(true);
        when(types.findByTenantId(7L)).thenReturn(List.of(active, archived));

        var result = service.importFile(file(List.of(row("HR-11", "2026-11-01", "2026-11-03", "APPROVED"),
                List.of("HR-11", "Test Person", "FIN1234", "CUSTOM_OLD", "2026-11-05", "2026-11-06", "APPROVED"))));

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getRejectedRows()).isEqualTo(2);
        assertThat(result.getErrors()).singleElement().satisfies(error -> assertThat(error.getRow()).isEqualTo(3));
        verify(leaves, never()).create(any());
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

    @Test void insufficientBalanceThrowsWithExcelRowAndAbortsTheTransaction() {
        references();
        doThrow(new com.hic.exception.BadRequestException("Məzuniyyət balansı kifayət etmir"))
                .when(leaves).create(any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.importFile(file(List.of(
                row("HR-11", "2026-11-01", "2026-11-03", "APPROVED")))))
                .isInstanceOf(com.hic.exception.BadRequestException.class)
                .hasMessageContaining("Sətir 2").hasMessageContaining("Heç bir məlumat yazılmadı");
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
