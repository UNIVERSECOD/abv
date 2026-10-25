package com.hic.service;

import com.hic.exception.BadRequestException;
import com.hic.exception.ResourceNotFoundException;
import com.hic.model.LeaveType;
import com.hic.repository.LeaveTypeRepository;
import com.hic.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class LeaveTypeServiceTest {
    @Mock LeaveTypeRepository types;
    @Mock com.hic.repository.LeaveRequestRepository requests;
    @Mock jakarta.persistence.EntityManager entityManager;
    @Mock jakarta.persistence.Query tenantQuery;
    @InjectMocks LeaveTypeService service;
    @BeforeEach void tenantLock() {
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(tenantQuery);
        lenient().when(tenantQuery.setParameter("tenantId", 7L)).thenReturn(tenantQuery);
        lenient().when(tenantQuery.getResultList()).thenReturn(List.of(7L));
    }
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
        assertThatThrownBy(() -> service.delete(12L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.save(12L, new LeaveTypeService.Input("Test", "T", true)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(types, never()).save(any());
    }

    @Test void deletionRetainsHistoricalType() {
        TenantContext.setTenantId(7L);
        var type = new LeaveType(); type.setId(12L);
        when(types.lockForLeave(7L, 12L)).thenReturn(Optional.of(type));
        service.delete(12L);
        assertThat(type.isArchived()).isTrue();
        verify(types).save(type);
        verify(types, never()).delete(any());
    }

    @Test void tenantLockAllowsForeignKeyChecksAndPrecedesTypeLock() {
        TenantContext.setTenantId(7L);
        var type = new LeaveType(); type.setId(12L);
        when(types.lockForLeave(7L, 12L)).thenReturn(Optional.of(type));

        service.delete(12L);

        var order = inOrder(entityManager, tenantQuery, types);
        order.verify(entityManager).createNativeQuery("SELECT id FROM tenants WHERE id = :tenantId FOR NO KEY UPDATE");
        order.verify(tenantQuery).setParameter("tenantId", 7L);
        order.verify(tenantQuery).getResultList();
        order.verify(types).lockForLeave(7L, 12L);
    }

    @Test void waitingSaveRefreshesArchivedStateAndCannotResurrectDeletedType() {
        TenantContext.setTenantId(7L);
        var type = new LeaveType(); type.setId(12L); type.setLeaveName("Təhsil"); type.setShortName("TƏ");
        when(types.lockForLeave(7L, 12L)).thenReturn(Optional.of(type));
        when(types.findByTenantId(7L)).thenReturn(List.of(type));
        doAnswer(call -> { type.setArchived(true); return null; })
                .when(entityManager).refresh(type, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);

        assertThatThrownBy(() -> service.save(12L, new LeaveTypeService.Input("Yeni ad", "YA", false)))
                .hasMessageContaining("Silinmiş məzuniyyət növünü");
        verify(types, never()).save(any());
    }

    @Test void policyEditChecksCommittedUsageAfterTakingTypeLock() {
        TenantContext.setTenantId(7L);
        var type = new LeaveType(); type.setId(12L); type.setLeaveName("Təhsil"); type.setShortName("TƏ");
        var used = new com.hic.model.LeaveRequest(); used.setLeaveTypeId(12L);
        when(types.lockForLeave(7L, 12L)).thenReturn(Optional.of(type));
        when(types.findByTenantId(7L)).thenReturn(List.of(type));
        when(requests.findByTenantId(7L)).thenReturn(List.of(used));

        assertThatThrownBy(() -> service.save(12L, new LeaveTypeService.Input("Təhsil", "TƏ", true)))
                .hasMessageContaining("İstifadə edilmiş növün balans qaydasını");
        var order = inOrder(types, entityManager, requests);
        order.verify(types).lockForLeave(7L, 12L);
        order.verify(entityManager).refresh(type, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        order.verify(requests).findByTenantId(7L);
        verify(types, never()).save(any());
    }
}
