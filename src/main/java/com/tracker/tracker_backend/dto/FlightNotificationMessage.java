package com.tracker.tracker_backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Kafka message published to {@code flight.notifications}.
 * One message per (user, flight) pair that passed bbox match and Redis dedup.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlightNotificationMessage(
        UUID userId,
        String whatsappNumber,
        String icao24,
        String callsign,
        double latitude,
        double longitude,
        Double altitudeMeters,
        Double speedMs,
        Double headingDeg
) {}
