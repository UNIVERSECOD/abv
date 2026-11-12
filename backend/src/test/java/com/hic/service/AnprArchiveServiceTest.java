package com.hic.service;

import com.hic.model.DeviceConfig;
import com.hic.repository.DeviceConfigRepository;
import com.hic.util.AppTimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AnprArchiveServiceTest {
    @Test
    void failedExitArchiveNeverChangesAttendanceEvenIfEntryWasRead() {
        var devices = mock(DeviceConfigRepository.class);
        var bridge = mock(AnprBridgeClient.class);
        var attendance = mock(AnprAttendanceService.class);
        var transactions = mock(TransactionTemplate.class);
        when(devices.findByTenantIdAndBranchId(1L, 4L)).thenReturn(List.of(camera(12, 22, "EXIT"), camera(11, 21, "ENTRY")));
        when(bridge.fetchArchive(eq(21L), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(new AnprBridgeClient.ArchiveResult(1, 1));
        when(bridge.fetchArchive(eq(22L), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenThrow(new ResourceAccessException("synthetic connection failure"));
        var service = new AnprArchiveService(devices, bridge, attendance, mock(AttendanceCalculationService.class),
                mock(AttendanceService.class), transactions);
        var today = AppTimeZone.today();
        assertThatThrownBy(() -> service.readAndApply(1, 4, today.minusDays(40), today))
                .hasMessageContaining("Çıxış");
        verifyNoInteractions(transactions, attendance);
    }

    private DeviceConfig camera(long id, long bridge, String role) {
        var camera = new DeviceConfig();
        camera.setId(id);
        camera.setDeviceId(Long.toString(bridge));
        camera.setDoorRole(role);
        camera.setAnpr(true);
        camera.setStatus("ACTIVE");
        return camera;
    }
}
