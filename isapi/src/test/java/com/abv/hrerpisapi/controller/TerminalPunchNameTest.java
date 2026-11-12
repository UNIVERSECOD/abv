package com.abv.hrerpisapi.controller;

import com.abv.hrerpisapi.dao.entity.AcsRawEventEntity;
import com.abv.hrerpisapi.dao.entity.AttendancePunchEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalPunchNameTest {
    @Test
    void readsNameFromMatchingLiveOrHistoryEventOnly() {
        var punch = new AttendancePunchEntity();
        punch.setDeviceId(9L);
        punch.setEmployeeNo("123");
        var raw = new AcsRawEventEntity();
        raw.setDeviceId(9L);
        String name = "İlqar Məmmədov";
        raw.setRawJson("""
                {"AccessControllerEvent":{"employeeNoString":"123","name":"İlqar Məmmədov"}}
                """);
        assertThat(EventReadController.terminalName(raw, punch)).isEqualTo(name);
        raw.setRawJson("""
                {"employeeNoString":"123","name":"İlqar Məmmədov"}
                """);
        assertThat(EventReadController.terminalName(raw, punch)).isEqualTo(name);
        raw.setDeviceId(8L);
        assertThat(EventReadController.terminalName(raw, punch)).isNull();
        raw.setDeviceId(9L);
        punch.setEmployeeNo("124");
        assertThat(EventReadController.terminalName(raw, punch)).isNull();
    }
}
