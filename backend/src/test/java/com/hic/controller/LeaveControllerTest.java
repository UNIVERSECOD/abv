package com.hic.controller;

import com.hic.dto.DataImportErrorDTO;
import com.hic.dto.DataImportResultDTO;
import com.hic.service.LeaveService;
import com.hic.service.LeaveTransferService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LeaveControllerTest {
    @Test
    void templateAndExportRoutesReturnExcelAttachments() throws Exception {
        var leaves = mock(LeaveService.class);
        var transfer = mock(LeaveTransferService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new LeaveController(leaves, transfer)).build();
        when(transfer.export(true)).thenReturn(new byte[] {1, 2});
        when(transfer.export(false)).thenReturn(new byte[] {3, 4});
        mvc.perform(get("/api/leaves/template"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"leaves_template.xlsx\""))
                .andExpect(content().bytes(new byte[] {1, 2}));
        mvc.perform(get("/api/leaves/export"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(content().bytes(new byte[] {3, 4}));
        verifyNoInteractions(leaves);
    }

    @Test
    void importReturnsRowErrorsAndFalseSuccessWithoutClaimingPartialImport() throws Exception {
        var leaves = mock(LeaveService.class);
        var transfer = mock(LeaveTransferService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new LeaveController(leaves, transfer)).build();
        when(transfer.importFile(any())).thenReturn(DataImportResultDTO.builder().entity("leaves")
                .totalRows(2).rejectedRows(2).errors(List.of(new DataImportErrorDTO(3, "Məzuniyyət", "Yanlış tarix"))).build());
        mvc.perform(multipart("/api/leaves/import").file(new MockMultipartFile("file", "leaves.xlsx", "application/octet-stream", new byte[] {1}))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.importedRows").value(0)).andExpect(jsonPath("$.data.errors[0].row").value(3));
    }

    @Test
    void missingCreateFieldsAreRejectedBeforePersistence() throws Exception {
        var leaves = mock(LeaveService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new LeaveController(leaves, mock(LeaveTransferService.class))).build();
        mvc.perform(post("/api/leaves").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(leaves);
    }
}
