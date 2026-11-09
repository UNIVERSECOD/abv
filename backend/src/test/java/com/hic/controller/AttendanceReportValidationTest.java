package com.hic.controller;

import com.hic.exception.GlobalExceptionHandler;
import com.hic.service.AttendanceCalculationService;
import com.hic.service.AttendanceReportService;
import com.hic.service.TabelService;
import com.hic.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AttendanceReportValidationTest {
    // Real services: invalid requests must be rejected before any repository access.
    @InjectMocks private TabelService tabelService;
    @InjectMocks private AttendanceReportService reportService;
    @InjectMocks private AttendanceCalculationService calculationService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
        mvc = MockMvcBuilders.standaloneSetup(
                new TabelController(tabelService),
                new AttendanceController(null, calculationService, reportService, null, null))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "/api/tabel?year=2026&month=0 | Ay 1 ilə 12 arasında olmalıdır.",
            "/api/tabel?year=2026&month=0&mode=MCG | Ay 1 ilə 12 arasında olmalıdır.",
            "/api/tabel?year=2026&month=10&branchId=INVALID | 'branchId' parametrinin formatı yanlışdır.",
            "/api/tabel?year=2026&month=13 | Ay 1 ilə 12 arasında olmalıdır.",
            "/api/tabel/export?year=2026&month=13 | Ay 1 ilə 12 arasında olmalıdır.",
            "/api/tabel?year=0&month=10 | İl 1 ilə 9999 arasında olmalıdır.",
            "/api/tabel/export?year=10000&month=10 | İl 1 ilə 9999 arasında olmalıdır.",
            "/api/tabel?year=abc&month=10 | 'year' parametrinin formatı yanlışdır.",
            "/api/tabel/export?year=2026&month=2147483648 | 'month' parametrinin formatı yanlışdır.",
            "/api/tabel?month=10 | 'year' parametri mütləq göstərilməlidir.",
            "/api/attendance/report?start=2026-10-05&end=2026-10-09&page=-1 | Səhifə nömrəsi 0 və ya daha böyük olmalıdır.",
            "/api/attendance/report?start=2026-10-05&end=2026-10-09&size=0 | Səhifə ölçüsü ən azı 1 olmalıdır.",
            "/api/attendance/report?start=2026-10-05&end=2026-10-09&size=-1 | Səhifə ölçüsü ən azı 1 olmalıdır.",
            "/api/attendance/report?start=2026-10-09&end=2026-10-05 | Başlanğıc tarixi bitmə tarixindən sonra ola bilməz.",
            "/api/attendance/report/export?start=2026-10-09&end=2026-10-05 | Başlanğıc tarixi bitmə tarixindən sonra ola bilməz.",
            "/api/attendance/report?start=2026-10-05 | 'end' parametri mütləq göstərilməlidir.",
            "/api/attendance/report/export?start=2026-02-30&end=2026-10-09 | 'start' parametrinin formatı yanlışdır.",
            "/api/attendance/report?start=2026-10-05&end=2026-10-09&page=2147483648 | 'page' parametrinin formatı yanlışdır.",
            "/api/attendance/report?start=2026-10-05&end=2026-10-09&size=abc | 'size' parametrinin formatı yanlışdır."
    })
    void invalidReportRequestReturnsLocalizedBadRequest(String path, String message) throws Exception {
        mvc.perform(get(path).accept(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(message));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "2026-10-09 | 2026-10-05",
            "invalid | 2026-10-05"
    })
    void invalidRecalculationRangeIsRejected(String start, String end) throws Exception {
        mvc.perform(post("/api/attendance/recalculate").param("start", start).param("end", end)
                        .accept(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}
