package com.abv.hrerpisapi.device;

import com.abv.hrerpisapi.dao.entity.DeviceEntity;
import com.abv.hrerpisapi.dao.repository.DeviceRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeviceTargetPolicyTest {
    @Test void separatorsOnlyMustNotEnableTheLegacyRegistrationFallback() {
        DeviceRepository repository = mock(DeviceRepository.class);
        var policy = new DeviceTargetPolicy(repository, " , , ", "");
        assertThatThrownBy(() -> policy.requireAllowed("192.168.1.10:80"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(repository);
    }
    @Test void permitsOnlyExplicitTargetsAndExistingRegistrations() {
        DeviceRepository repository = mock(DeviceRepository.class);
        DeviceEntity device = new DeviceEntity(); device.setIp("192.168.1.10");
        when(repository.findAll()).thenReturn(List.of(device));
        var policy = new DeviceTargetPolicy(repository, "", "");
        policy.requireAllowed("192.168.1.10:80");
        assertThatThrownBy(() -> policy.requireAllowed("10.20.0.5:80")).isInstanceOf(RuntimeException.class);
        var strict = new DeviceTargetPolicy(repository, "10.20.0.5:8000", "");
        strict.requireAllowed("10.20.0.5:8000");
        assertThatThrownBy(() -> strict.requireAllowed("192.168.1.10:80")).isInstanceOf(RuntimeException.class);
    }
    @Test void permitsConfiguredNetworksButNotAdjacentOrMalformedRanges() {
        DeviceRepository repository = mock(DeviceRepository.class);
        var policy = new DeviceTargetPolicy(repository, "", "10.100.140.0/24,2001:db8::/64");
        policy.requireAllowed("10.100.140.5:80");
        policy.requireAllowed("[2001:db8::5]:8080");
        assertThatThrownBy(() -> policy.requireAllowed("10.100.141.5:80")).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(repository);
        assertThatThrownBy(() -> new DeviceTargetPolicy(repository, "", "10.0.0.0/99"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsMetadataLoopbackDnsCredentialsAndAlternateNumericForms() {
        for (String input : List.of("127.0.0.1", "[::1]", "169.254.169.254", "0.0.0.0",
                "localhost", "device.example", "2130706433", "127.1", "010.0.0.1", "10.0.0.1/path",
                "user@10.0.0.1", "10.0.0.1:65536", "10.0.0.1?x=1", "10.0.0.1:0")) {
            assertThatThrownBy(() -> DeviceAddress.normalize(input)).as(input).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(DeviceAddress.normalize("10.20.0.5")).isEqualTo("10.20.0.5:80");
    }
}
