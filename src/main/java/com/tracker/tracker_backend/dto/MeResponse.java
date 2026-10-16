package com.tracker.tracker_backend.dto;

import java.util.UUID;

public record MeResponse(
        UUID id,
        String displayName,
        boolean active,
        Location location,
        long pushDevices,
        boolean whatsappLinked
) {
    public record Location(String label, double latitude, double longitude, double radiusKm) {}
}
