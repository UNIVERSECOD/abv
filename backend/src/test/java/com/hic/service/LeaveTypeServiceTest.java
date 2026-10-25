package com.hic.service;

import com.hic.exception.BadRequestException;
import com.hic.exception.ResourceNotFoundException;
import com.hic.model.LeaveType;
import com.hic.repository.LeaveTypeRepository;
import com.hic.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class LeaveTypeServiceTest {
    @Mock LeaveTypeRepository types;
    @Mock com.hic.repository.LeaveRequestRepository requests;
    @InjectMocks LeaveTypeService service;
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void validatesAbbreviationAndDoesNotWriteInvalidType() {
        TenantContext.setTenantId(7L);
        for (String code : List.of("", "ABCD", "12", "A/B")) {
            assertThatThrownBy(() -> service.save(null, new LeaveTypeService.Input("Təhsil", code, false)))
                    .isInstanceOf(BadRequestException.class);
        }
        verify(types, never()).save(any());
    }

    @Test void createsWithinCurrentTenantAndNormalizesAzerbaijaniLetters() {
        TenantContext.setTenantId(7L);
        when(types.findByTenantId(7L)).thenReturn(List.of());
        when(types.save(any())).thenAnswer(i -> i.getArgument(0));
        var result = service.save(null, new LeaveTypeService.Input(" Təhsil ", "tə", false));
        assertThat(result.getTenantId()).isEqualTo(7L);
        assertThat(result.getShortName()).isEqualTo("TƏ");
        assertThat(result.getLeaveName()).isEqualTo("Təhsil");
        assertThat(result.getLeaveCode()).startsWith("CUSTOM_");
    }

    @Test void cannotEditOrDeleteTypeFromAnotherTenant() {
        TenantContext.setTenantId(7L);
        when(types.findByTenantId(7L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.delete(12L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.save(12L, new LeaveTypeService.Input("Test", "T", true)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(types, never()).save(any());
    }

    @Test void deletionRetainsHistoricalType() {
        TenantContext.setTenantId(7L);
        var type = new LeaveType(); type.setId(12L);
        when(types.findByTenantId(7L)).thenReturn(List.of(type));
        service.delete(12L);
        assertThat(type.isArchived()).isTrue();
        verify(types).save(type);
        verify(types, never()).delete(any());
    }
}
