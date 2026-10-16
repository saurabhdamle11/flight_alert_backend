package com.tracker.tracker_backend.dto;

import java.time.Instant;

public record NotificationView(
        String icao24,
        String callsign,
        Long altitudeFt,
        Long speedKmh,
        String heading,
        String channel,
        Instant sentAt
) {}
