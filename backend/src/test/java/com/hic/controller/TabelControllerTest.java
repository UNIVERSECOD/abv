package com.hic.controller;

import com.hic.dto.TabelMonthlyDTO;
import com.hic.service.TabelService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TabelControllerTest {
    @Test
    void tabelAlwaysUsesStandardContractEvenWhenLegacyModeParameterIsSent() throws Exception {
        var service = mock(TabelService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new TabelController(service)).build();
        var data = new TabelMonthlyDTO(); data.setCalculationMode(TabelMonthlyDTO.CalculationMode.STANDARD);
        when(service.getMonthlyTabel(2026, 9, null, null, null, null)).thenReturn(data);
        mvc.perform(get("/api/tabel?year=2026&month=9&mode=MCG").accept(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.calculationMode").value("STANDARD"));
        verify(service).getMonthlyTabel(2026, 9, null, null, null, null);
    }

    @Test
    void exportUsesStandardModeAndFilters() throws Exception {
        var service = mock(TabelService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new TabelController(service)).build();
        when(service.exportMonthlyTabel(2026, 9, 2L, 3L, 4L, "MCG")).thenReturn(new byte[] {1, 2});
        mvc.perform(get("/api/tabel/export?year=2026&month=9&branchId=2&departmentId=3&positionId=4&search=MCG&mode=MCG"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=attendra-tabel.xlsx"))
                .andExpect(content().bytes(new byte[] {1, 2}));
    }
}
