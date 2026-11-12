package com.hic.service;

import com.hic.model.DeviceConfig;
import com.hic.repository.DeviceConfigRepository;
import com.hic.util.AppTimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnprAreaConfigurationTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DeviceConfigRepository devices = mock(DeviceConfigRepository.class);
    private final AnprAreaConfiguration configuration = new AnprAreaConfiguration(jdbc, devices);
    private final DeviceConfig entry = camera("11", "ENTRY", "ACTIVE");
    private final DeviceConfig exit = camera("12", "EXIT", "ACTIVE");

    @Test
    void closesPeriodWhenOneCameraWasDeleted() {
        when(devices.findByTenantIdAndBranchId(1L, 2L)).thenReturn(List.of(entry));
        configuration.reconcilePair(1L, 2L);
        verifyClosed();
    }

    @Test
    void closesPeriodWhenCameraIsDisabled() {
        exit.setStatus("INACTIVE");
        reconcileBoth();
        verifyClosed();
    }

    @Test
    void opensPeriodWhenCameraIsReenabledAndCompletesPair() {
        exit.setStatus("INACTIVE");
        when(devices.findByTenantIdAndBranchId(1L, 2L)).thenReturn(List.of(entry, exit));
        configuration.reconcilePair(1L, 2L);
        verifyClosed();

        exit.setStatus("ACTIVE");
        configuration.reconcilePair(1L, 2L);
        verify(jdbc).update(contains("INSERT INTO anpr_attendance_area_periods"),
                eq(1L), eq(2L), eq(AppTimeZone.today().plusDays(1)), eq(1L), eq(2L));
    }

    @Test
    void closesPeriodWhenCameraRoleIsEditedToDuplicateEntry() {
        exit.setDoorRole("ENTRY");
        reconcileBoth();
        verifyClosed();
    }

    @Test
    void rejectsTwoRolesUsingTheSameBridgeId() {
        exit.setDeviceId("011");
        reconcileBoth();
        verifyClosed();
    }

    private void reconcileBoth() {
        when(devices.findByTenantIdAndBranchId(1L, 2L)).thenReturn(List.of(entry, exit));
        configuration.reconcilePair(1L, 2L);
    }

    private void verifyClosed() {
        verify(jdbc).update(contains("UPDATE anpr_attendance_area_periods"),
                eq(AppTimeZone.today().plusDays(1)), eq(1L), eq(2L));
    }

    private static DeviceConfig camera(String bridgeId, String role, String status) {
        DeviceConfig camera = new DeviceConfig();
        camera.setAnpr(true);
        camera.setDeviceId(bridgeId);
        camera.setDoorRole(role);
        camera.setStatus(status);
        return camera;
    }
}
