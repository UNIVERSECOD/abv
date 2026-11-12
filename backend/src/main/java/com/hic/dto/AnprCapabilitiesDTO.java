package com.hic.dto;

import java.time.Instant;

public record AnprCapabilitiesDTO(Instant checkedAt, String model, String firmware,
                                  Feature plateWrite, Feature ownerName, Feature archiveRead) {
    public record Feature(String status, String reason) {}

    public static AnprCapabilitiesDTO unavailable(String reason) {
        var feature = new Feature("UNKNOWN", reason);
        return new AnprCapabilitiesDTO(Instant.now(), "", "", feature, feature, feature);
    }
}
