package com.tracker.tracker_backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Kafka message published to {@code flight.state.updates}.
 *
 * Partition key: regionKey (e.g. "37:-122") so all flights
 * in the same geographic grid cell go to the same partition.
 *
 * on_ground: null means unknown — we keep those; true means on the ground — we discard those.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlightStateUpdate(
        String icao24,
        String callsign,
        double latitude,
        double longitude,
        Double altitudeMeters,
        Double speedMs,
        Double headingDeg,
        Boolean onGround,
        String regionKey
) {}
